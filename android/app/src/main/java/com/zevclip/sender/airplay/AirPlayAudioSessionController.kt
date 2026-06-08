package com.zevclip.sender.airplay

class AirPlayAudioSessionController(
    private val target: AirPlayTarget,
    private val transport: AirPlayRtspTransport,
    private val pairVerifySession: AirPlayPairVerifier.Session,
    private val localRtspHost: String = target.host,
    private val deviceId: String = "AA:BB:CC:DD:EE:FF"
) {
    data class SetupResult(
        val ports: AirPlayAudioSetup.StreamPorts,
        val audioSharedKey: ByteArray,
        val sessionIds: AirPlayAudioSetup.SessionIds,
        val setupOne: AirPlayRtspClient.Response,
        val setupTwo: AirPlayRtspClient.Response
    )

    sealed class Result {
        data class Success(val setup: SetupResult) : Result()
        data class Failure(val message: String, val statusCode: Int? = null, val cause: Throwable? = null) : Result()
    }

    private var cseq = 1

    fun setup(
        timingPort: Int,
        controlPort: Int,
        format: AirPlayAudioSetup.AudioFormat = AirPlayAudioSetup.AudioFormat(),
        ids: AirPlayAudioSetup.SessionIds = AirPlayAudioSetup.SessionIds()
    ): Result {
        return runCatching {
            val setupOneBody = AirPlayAudioSetup.setupTimingPayload(
                deviceId = deviceId,
                timingPort = timingPort,
                ids = ids
            )
            val setupOne = rtsp(
                method = "SETUP",
                body = setupOneBody,
                contentType = BINARY_PLIST_CONTENT_TYPE,
                ids = ids
            )
            if (!setupOne.isSuccessful()) {
                return Result.Failure("AirPlay SETUP#1 failed.", setupOne.statusCode)
            }

            val audioSharedKey = AirPlayAudioSetup.deriveAudioSharedKey(pairVerifySession.sharedSecret)
            val setupTwoBody = AirPlayAudioSetup.setupStreamPayload(
                audioSharedKey = audioSharedKey,
                controlPort = controlPort,
                ids = ids,
                format = format
            )
            val setupTwo = rtsp(
                method = "SETUP",
                body = setupTwoBody,
                contentType = BINARY_PLIST_CONTENT_TYPE,
                ids = ids
            )
            if (!setupTwo.isSuccessful()) {
                return Result.Failure("AirPlay SETUP#2 failed.", setupTwo.statusCode)
            }

            Result.Success(
                SetupResult(
                    ports = AirPlayAudioSetup.parseSetupPorts(setupOne.body, setupTwo.body),
                    audioSharedKey = audioSharedKey,
                    sessionIds = ids,
                    setupOne = setupOne,
                    setupTwo = setupTwo
                )
            )
        }.getOrElse { error ->
            Result.Failure("AirPlay audio setup failed: ${error.message ?: "unknown error"}", cause = error)
        }
    }

    fun record(ids: AirPlayAudioSetup.SessionIds): AirPlayRtspClient.Response {
        return rtsp(
            method = "RECORD",
            body = ByteArray(0),
            contentType = null,
            ids = ids
        )
    }

    private fun rtsp(
        method: String,
        body: ByteArray,
        contentType: String?,
        ids: AirPlayAudioSetup.SessionIds
    ): AirPlayRtspClient.Response {
        return transport.request(
            method = method,
            uri = AirPlayRtspClient.rtspUrl(localRtspHost, target.port, "/${ids.streamConnectionId}"),
            headers = linkedMapOf(
                "CSeq" to (cseq++).toString(),
                "User-Agent" to "AirPlay/950.7.1 ZevClip",
                "DACP-ID" to "0000000000000001",
                "Active-Remote" to "1",
                "Client-Instance" to "0000000000000001"
            ),
            body = body,
            contentType = contentType
        )
    }

    private companion object {
        const val BINARY_PLIST_CONTENT_TYPE = "application/x-apple-binary-plist"
    }
}
