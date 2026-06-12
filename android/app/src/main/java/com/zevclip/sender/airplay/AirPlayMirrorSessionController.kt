package com.zevclip.sender.airplay

import android.util.Log
import java.io.Closeable
import java.util.UUID
import kotlin.concurrent.thread

class AirPlayMirrorSessionController(
    private val target: AirPlayTarget,
    private val identity: AirPlayIdentity,
    private val passcode: String
) : Closeable {
    data class PreparedMirror(
        val dataPort: Int,
        val streamConnectionId: Long,
        val dataStreamKey: ByteArray
    )

    private var connected: AirPlayPairVerifier.ConnectedSession? = null
    private var eventChannel: AirPlayEventChannel? = null
    private var timingServer: AirPlayTimingServer? = null
    @Volatile
    private var keepAliveRunning = false
    private var keepAliveWorker: Thread? = null
    private val requestLock = Any()
    private var cseq = 1
    private val rtspSessionId = CryptoPrimitives.randomBytes(4).toUInt32Be()
    private val sessionUuid = UUID.randomUUID().toString().uppercase()

    fun prepare(): PreparedMirror {
        val session = connectPairVerified()
        val timing = AirPlayTimingServer()
        val timingPort = timing.start(0)
        timingServer = timing

        val setup1 = session.transport.requestRaw(
            method = "SETUP",
            uri = rtspUri(session.localRtspHost),
            protocol = "RTSP/1.0",
            headers = rtspHeaders(),
            body = BPlist.encode(
                BPlist.dict(
                    "deviceID" to BPlist.string(identity.deviceId),
                    "macAddress" to BPlist.string(identity.deviceId),
                    "sessionUUID" to BPlist.string(sessionUuid),
                    "isMultiSelectAirPlay" to BPlist.bool(true),
                    "timingProtocol" to BPlist.string("NTP"),
                    "timingPort" to BPlist.int(timingPort.toLong()),
                    "name" to BPlist.string(identity.senderName),
                    "model" to BPlist.string("iPhone14,3"),
                    "osName" to BPlist.string("iPhone OS"),
                    "osVersion" to BPlist.string("17.0"),
                    "osBuildVersion" to BPlist.string("21A329"),
                    "sourceVersion" to BPlist.string("950.7.1"),
                    "senderSupportsRelay" to BPlist.bool(true),
                    "statsCollectionEnabled" to BPlist.bool(false),
                    "groupContainsGroupLeader" to BPlist.bool(false)
                )
            ),
            contentType = BPLIST_CONTENT_TYPE
        )
        if (!setup1.isSuccessful()) {
            error("AirPlay mirror SETUP #1 failed: ${setup1.statusCode} ${setup1.reasonPhrase}")
        }
        val eventPort = setup1.body.intAt("eventPort")
        if (eventPort > 0) {
            eventChannel = startEventChannel(eventPort, session.session.sharedSecret)
        }

        val streamConnectionId = rtspSessionId
        val controlKeys = AirPlayEncryptedChannel.controllerKeys(session.session.sharedSecret)
        val advertisedStreamKey = controlKeys.writeKey.copyOfRange(0, STREAM_AES_KEY_SIZE)
        val advertisedStreamIv = controlKeys.readKey.copyOfRange(0, STREAM_AES_KEY_SIZE)
        val dataStreamKey = CryptoPrimitives.hkdfSha512(
            inputKeyMaterial = session.session.sharedSecret,
            salt = "DataStream-Salt$streamConnectionId".toByteArray(Charsets.US_ASCII),
            info = "DataStream-Output-Encryption-Key".toByteArray(Charsets.US_ASCII),
            outputSize = DATA_STREAM_KEY_SIZE
        )
        val setup2 = session.transport.requestRaw(
            method = "SETUP",
            uri = rtspUri(session.localRtspHost),
            protocol = "RTSP/1.0",
            headers = rtspHeaders(),
            body = BPlist.encode(
                BPlist.dict(
                    "deviceID" to BPlist.string(identity.deviceId),
                    "macAddress" to BPlist.string(identity.deviceId),
                    "sessionUUID" to BPlist.string(sessionUuid),
                    "sourceVersion" to BPlist.string("950.7.1"),
                    "isScreenMirroringSession" to BPlist.bool(true),
                    "timingProtocol" to BPlist.string("NTP"),
                    "timingPort" to BPlist.int(timingPort.toLong()),
                    "osBuildVersion" to BPlist.string("21A329"),
                    "model" to BPlist.string("iPhone14,3"),
                    "name" to BPlist.string(identity.senderName),
                    "streams" to BPlist.array(
                        BPlist.dict(
                            "type" to BPlist.int(MIRROR_STREAM_TYPE),
                            "streamConnectionID" to BPlist.int(streamConnectionId),
                            "shk" to BPlist.data(advertisedStreamKey),
                            "shiv" to BPlist.data(advertisedStreamIv),
                            "timestampInfo" to namedInfoArray("SubSu", "BePxT", "AfPxT", "BefEn", "EmEnc")
                        )
                    )
                )
            ),
            contentType = BPLIST_CONTENT_TYPE
        )
        if (!setup2.isSuccessful()) {
            error("AirPlay mirror SETUP #2 failed: ${setup2.statusCode} ${setup2.reasonPhrase}")
        }
        val dataPort = setup2.body.firstStreamInt("dataPort")
        if (dataPort <= 0) {
            error("AirPlay mirror SETUP #2 did not return a dataPort.")
        }
        Log.i(TAG, "AirPlay mirror SETUP complete: dataPort=$dataPort streamConnectionID=$streamConnectionId")
        return PreparedMirror(dataPort, streamConnectionId, dataStreamKey)
    }

    fun recordBestEffort() {
        val session = connected ?: return
        sendRecordBestEffort(session)
        startKeepAlive(session)
    }

    private fun connectPairVerified(): AirPlayPairVerifier.ConnectedSession {
        when (val verified = AirPlayPairVerifier(target, identity).verifyConnected()) {
            is AirPlayPairVerifier.ConnectedResult.Success -> {
                connected = verified.connectedSession
                return verified.connectedSession
            }
            is AirPlayPairVerifier.ConnectedResult.Failure -> {
                if (passcode.isBlank()) error("Enter the screen AirPlay code shown on the Mac.")
                val setup = AirPlayPairSetupClient(target, identity)
                try {
                    when (val result = setup.pairSetup(
                        passcode = passcode,
                        transient = false,
                        oneTime = false,
                        preferredHkpHeader = "3"
                    )) {
                        is AirPlayPairSetupClient.Result.PersistentSuccess -> Unit
                        is AirPlayPairSetupClient.Result.TransientSuccess -> result.session.close()
                        is AirPlayPairSetupClient.Result.Failure -> {
                            error("AirPlay pair-setup failed: ${result.message}. The screen AirPlay code was rejected or expired. Tap AirPlay Screen to Mac again and enter the newest code shown on the Mac.")
                        }
                    }
                } finally {
                    setup.close()
                }
                return when (val retry = AirPlayPairVerifier(target, identity).verifyConnected()) {
                    is AirPlayPairVerifier.ConnectedResult.Success -> {
                        connected = retry.connectedSession
                        retry.connectedSession
                    }
                    is AirPlayPairVerifier.ConnectedResult.Failure -> error(retry.message)
                }
            }
        }
    }

    private fun rtspHeaders(vararg extraHeaders: Pair<String, String>): Map<String, String> {
        val instanceId = identity.deviceId.filter { it.isLetterOrDigit() }.take(16).uppercase()
        return linkedMapOf(
            "CSeq" to (cseq++).toString(),
            "User-Agent" to USER_AGENT,
            "DACP-ID" to instanceId,
            "Active-Remote" to "1",
            "Client-Instance" to instanceId
        ).apply { putAll(extraHeaders) }
    }

    private fun rtspUri(localHost: String): String {
        return AirPlayRtspClient.rtspUrl(localHost, target.port, "/$rtspSessionId")
    }

    private fun startEventChannel(eventPort: Int, sharedSecret: ByteArray): AirPlayEventChannel {
        var lastError: Throwable? = null
        repeat(EVENT_CHANNEL_ATTEMPTS) { attempt ->
            val channel = AirPlayEventChannel(target.host, eventPort, sharedSecret)
            val started = runCatching { channel.start() }
                .onFailure { lastError = it }
                .getOrDefault(false)
            if (started) {
                Log.i(TAG, "AirPlay mirror event channel connected on attempt ${attempt + 1}.")
                return channel
            }
            runCatching { channel.stop() }
            Thread.sleep(EVENT_CHANNEL_RETRY_MS)
        }
        error("AirPlay mirror event channel did not connect to port $eventPort.${lastError?.message?.let { " $it" } ?: ""}")
    }

    private fun namedInfoArray(vararg names: String): BPlist.Value.ArrayValue {
        return BPlist.Value.ArrayValue(names.map { name ->
            BPlist.dict("name" to BPlist.string(name))
        })
    }

    private fun sendRecordBestEffort(session: AirPlayPairVerifier.ConnectedSession) {
        runCatching {
            val record = synchronized(requestLock) {
                session.transport.requestRaw(
                    method = "RECORD",
                    uri = rtspUri(session.localRtspHost),
                    protocol = "RTSP/1.0",
                    headers = rtspHeaders(
                        "Session" to sessionUuid,
                        "Range" to "npt=0-",
                        "RTP-Info" to "seq=0;rtptime=0"
                    ),
                    body = ByteArray(0),
                    contentType = null
                )
            }
            Log.i(TAG, "AirPlay mirror RECORD response: ${record.statusCode} ${record.reasonPhrase}")
        }.onFailure { error ->
            Log.i(TAG, "AirPlay mirror RECORD did not return a response; continuing: ${error.message}")
        }
    }

    private fun startKeepAlive(session: AirPlayPairVerifier.ConnectedSession) {
        if (keepAliveRunning) return
        keepAliveRunning = true
        keepAliveWorker = thread(name = "zevclip-airplay-mirror-rtsp-keepalive", isDaemon = true) {
            var lastParameterAt = 0L
            sendFeedbackBestEffort(session)
            while (keepAliveRunning) {
                Thread.sleep(FEEDBACK_INTERVAL_MS)
                sendFeedbackBestEffort(session)
                val now = System.currentTimeMillis()
                if (now - lastParameterAt >= GET_PARAMETER_INTERVAL_MS) {
                    sendGetParameterBestEffort(session)
                    lastParameterAt = now
                }
            }
        }
    }

    private fun sendFeedbackBestEffort(session: AirPlayPairVerifier.ConnectedSession) {
        runCatching {
            synchronized(requestLock) {
                session.transport.requestRaw(
                    method = "POST",
                    uri = "/feedback",
                    protocol = "RTSP/1.0",
                    headers = rtspHeaders("Session" to sessionUuid),
                    body = ByteArray(0),
                    contentType = null
                )
            }
        }.onFailure { error ->
            if (keepAliveRunning) Log.d(TAG, "AirPlay mirror feedback keepalive failed: ${error.message}")
        }
    }

    private fun sendGetParameterBestEffort(session: AirPlayPairVerifier.ConnectedSession) {
        runCatching {
            synchronized(requestLock) {
                session.transport.requestRaw(
                    method = "GET_PARAMETER",
                    uri = rtspUri(session.localRtspHost),
                    protocol = "RTSP/1.0",
                    headers = rtspHeaders("Session" to sessionUuid),
                    body = ByteArray(0),
                    contentType = null
                )
            }
        }.onFailure { error ->
            if (keepAliveRunning) Log.d(TAG, "AirPlay mirror GET_PARAMETER keepalive failed: ${error.message}")
        }
    }

    override fun close() {
        keepAliveRunning = false
        keepAliveWorker?.interrupt()
        keepAliveWorker = null
        runCatching { eventChannel?.stop() }
        eventChannel = null
        runCatching { timingServer?.stop() }
        timingServer = null
        runCatching { connected?.close() }
        connected = null
    }

    private companion object {
        const val TAG = "ZevClipAirPlayMirrorSetup"
        const val BPLIST_CONTENT_TYPE = "application/x-apple-binary-plist"
        const val USER_AGENT = "AirPlay/950.7.1 ZevClip"
        const val MIRROR_STREAM_TYPE = 110L
        const val EVENT_CHANNEL_ATTEMPTS = 6
        const val EVENT_CHANNEL_RETRY_MS = 350L
        const val STREAM_AES_KEY_SIZE = 16
        const val DATA_STREAM_KEY_SIZE = 32
        const val FEEDBACK_INTERVAL_MS = 2_000L
        const val GET_PARAMETER_INTERVAL_MS = 15_000L
    }
}

private fun ByteArray.toUInt32Be(): Long {
    var value = 0L
    take(4).forEach { byte ->
        value = (value shl 8) or (byte.toLong() and 0xFFL)
    }
    return value
}

private fun ByteArray.intAt(key: String): Int {
    val dict = runCatching { BPlist.decode(this) }.getOrNull() as? BPlist.Value.DictValue
        ?: return 0
    return (dict.values[key] as? BPlist.Value.IntValue)?.value?.toInt() ?: 0
}

private fun ByteArray.firstStreamInt(key: String): Int {
    val dict = runCatching { BPlist.decode(this) }.getOrNull() as? BPlist.Value.DictValue
        ?: return 0
    val streams = dict.values["streams"] as? BPlist.Value.ArrayValue ?: return 0
    val first = streams.values.firstOrNull() as? BPlist.Value.DictValue ?: return 0
    return (first.values[key] as? BPlist.Value.IntValue)?.value?.toInt() ?: 0
}
