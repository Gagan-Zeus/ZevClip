package com.zevclip.sender

import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID
import kotlin.concurrent.thread

class AirPlayTargetDiscoveryManager(
    context: Context,
    private val onStatusChanged: (String, Boolean) -> Unit
) {
    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(ConnectivityManager::class.java)
    private val nsdManager = appContext.getSystemService(NsdManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private val services = linkedMapOf<String, AirPlayService>()
    private val discoveryListeners = mutableMapOf<String, NsdManager.DiscoveryListener>()
    private var pendingServices = mutableListOf<AirPlayService>()
    private var airPlayPassword = ""
    private var probeMode = ProbeMode.BASIC
    private var resolving = false

    private val selectService = Runnable { selectAndResolveService() }
    private val discoveryTimeout = Runnable {
        if (services.isEmpty()) {
            stopDiscovery()
            updateStatus(
                "No AirPlay receiver found for your paired Mac. Enable AirPlay Receiver on the Mac and keep both devices on the same network.",
                false
            )
        } else {
            selectAndResolveService()
        }
    }

    fun discover(password: String = ZevClipPreferences.airPlayPassword(appContext)) {
        startDiscovery(password, ProbeMode.BASIC)
    }

    fun testAudioSession(password: String = ZevClipPreferences.airPlayPassword(appContext)) {
        startDiscovery(password, ProbeMode.AUDIO_SESSION)
    }

    private fun startDiscovery(password: String, mode: ProbeMode) {
        stop()
        airPlayPassword = password
        probeMode = mode

        if (!LocalNetworkAccess.canReachLocalPeers(connectivityManager)) {
            updateStatus(LocalNetworkAccess.unavailableMessage(), false)
            return
        }

        if (ZevClipPreferences.endpoint(appContext)?.ipAddress.isNullOrBlank()) {
            updateStatus("Pair your Mac in ZevClip before testing AirPlay.", false)
            return
        }

        services.clear()
        pendingServices.clear()
        resolving = false
        updateStatus(
            if (mode == ProbeMode.AUDIO_SESSION) {
                "Searching for paired Mac before testing audio session..."
            } else {
                "Searching for AirPlay on your paired Mac..."
            },
            true
        )

        SERVICE_TYPES.forEach { serviceType ->
            val listener = discoveryListenerFor(serviceType)
            discoveryListeners[serviceType] = listener
            try {
                nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
            } catch (error: RuntimeException) {
                discoveryListeners.remove(serviceType)
                Log.w(TAG, "AirPlay discovery failed for $serviceType", error)
            }
        }

        if (discoveryListeners.isEmpty()) {
            updateStatus("Could not start AirPlay discovery.", false)
        } else {
            handler.postDelayed(discoveryTimeout, DISCOVERY_TIMEOUT_MS)
        }
    }

    fun stop() {
        handler.removeCallbacks(selectService)
        handler.removeCallbacks(discoveryTimeout)
        stopDiscovery()
        services.clear()
        pendingServices.clear()
        resolving = false
    }

    private fun discoveryListenerFor(serviceType: String): NsdManager.DiscoveryListener {
        return object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {
                Log.i(TAG, "AirPlay discovery started for $type")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                handler.post { handleServiceFound(serviceInfo, serviceType) }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                handler.post {
                    services.remove(serviceKey(serviceInfo, serviceType))
                    Log.i(TAG, "AirPlay service lost: ${serviceInfo.serviceName}")
                }
            }

            override fun onDiscoveryStopped(type: String) {
                Log.i(TAG, "AirPlay discovery stopped for $type")
            }

            override fun onStartDiscoveryFailed(type: String, errorCode: Int) {
                handler.post {
                    discoveryListeners.remove(serviceType)
                    updateStatus("Could not search $type AirPlay services (error $errorCode).", false)
                    Log.w(TAG, "AirPlay discovery start failed for $type: $errorCode")
                }
            }

            override fun onStopDiscoveryFailed(type: String, errorCode: Int) {
                handler.post {
                    discoveryListeners.remove(serviceType)
                    Log.w(TAG, "AirPlay discovery stop failed for $type: $errorCode")
                }
            }
        }
    }

    private fun handleServiceFound(serviceInfo: NsdServiceInfo, fallbackServiceType: String) {
        val serviceType = normalizedServiceType(serviceInfo.serviceType)
            .takeIf { it in SERVICE_TYPES_NORMALIZED }
            ?: normalizedServiceType(fallbackServiceType)

        if (serviceType !in SERVICE_TYPES_NORMALIZED) {
            return
        }
        if (isThirdPartyAirPlayReceiver(serviceInfo.serviceName)) {
            Log.i(TAG, "Ignoring third-party AirPlay receiver: ${serviceInfo.serviceName}")
            return
        }

        services[serviceKey(serviceInfo, serviceType)] = AirPlayService(serviceInfo, serviceType)

        val count = services.size
        updateStatus(
            if (count == 1) {
                "Found 1 AirPlay receiver. Looking for your paired Mac..."
            } else {
                "Found $count AirPlay receivers. Looking for your paired Mac..."
            },
            true
        )

        handler.removeCallbacks(selectService)
        handler.postDelayed(selectService, SELECTION_DELAY_MS)
    }

    @Suppress("DEPRECATION")
    private fun selectAndResolveService() {
        if (resolving || services.isEmpty()) {
            return
        }

        resolving = true
        handler.removeCallbacks(selectService)
        handler.removeCallbacks(discoveryTimeout)

        pendingServices = preferredServices().toMutableList()
        stopDiscovery()
        resolveNextService()
    }

    @Suppress("DEPRECATION")
    private fun resolveNextService() {
        val service = if (pendingServices.isNotEmpty()) {
            pendingServices.removeAt(0)
        } else {
            resolving = false
            updateStatus(
                "Found ${services.size} AirPlay receivers, but none matched your paired ZevClip Mac.",
                false
            )
            return
        }

        updateStatus("Checking ${service.info.serviceName}...", true)

        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                handler.post {
                    updateStatus("Skipping ${serviceInfo.serviceName}; Android could not resolve it.", true)
                    Log.w(TAG, "AirPlay resolve failed for ${serviceInfo.serviceName}: $errorCode")
                    resolveNextService()
                }
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                handler.post { handleResolvedService(serviceInfo, service.serviceType) }
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                nsdManager.resolveService(service.info, appContext.mainExecutor, listener)
            } else {
                nsdManager.resolveService(service.info, listener)
            }
        } catch (error: RuntimeException) {
            resolving = false
            updateStatus("Could not resolve ${service.info.serviceName}: ${error.message ?: "unknown error"}", false)
            Log.w(TAG, "AirPlay resolve threw an exception", error)
        }
    }

    private fun handleResolvedService(serviceInfo: NsdServiceInfo, serviceType: String) {
        val port = serviceInfo.port
        val hosts = EndpointSelector.serviceHosts(serviceInfo)
        val isRaopService = normalizedServiceType(serviceType) == RAOP_SERVICE_TYPE.trimEnd('.')

        if (probeMode == ProbeMode.AUDIO_SESSION && !isRaopService) {
            Log.i(TAG, "Skipping non-RAOP AirPlay service for audio session: ${serviceInfo.serviceName} on $port")
            resolveNextService()
            return
        }

        if (hosts.isEmpty() || NetworkInputValidator.parsePort(port.toString()) == null) {
            updateStatus("Skipping ${serviceInfo.serviceName}; it has no usable AirPlay endpoint.", true)
            Log.w(TAG, "AirPlay resolved without a usable endpoint: $serviceInfo")
            resolveNextService()
            return
        }

        val probeHosts = probeHostsForPairedMac(hosts)
        if (probeHosts.isEmpty()) {
            Log.i(TAG, "Skipping non-paired AirPlay receiver ${serviceInfo.serviceName}: $hosts")
            resolveNextService()
            return
        }

        val receiverInfo = AirPlayReceiverInfo(
            serviceName = serviceInfo.serviceName,
            txtSummary = txtSummary(serviceInfo)
        )
        updateStatus("Probing paired Mac AirPlay receiver...", true)
        thread(name = "ZevClipAirPlayProbe") {
            val probeResult = probeBestEndpoint(probeHosts, port, serviceType, airPlayPassword, probeMode)
            handler.post {
                resolving = false
                updateStatus(probeResult.message(receiverInfo, port), false)
            }
        }
    }

    private fun preferredServices(): List<AirPlayService> {
        return services.values.sortedWith(
            compareBy<AirPlayService> {
                if (it.serviceType == RAOP_SERVICE_TYPE.trimEnd('.')) 0 else 1
            }.thenBy {
                if (it.info.port == NATIVE_RAOP_PORT) 0 else 1
            }.thenBy {
                if (it.info.serviceName.contains('@')) 0 else 1
            }.thenBy { it.info.serviceName }
        )
    }

    private fun isThirdPartyAirPlayReceiver(serviceName: String): Boolean {
        val normalizedName = serviceName.lowercase(Locale.US)
        return THIRD_PARTY_RECEIVER_NAME_MARKERS.any { marker ->
            marker in normalizedName
        }
    }

    private fun probeHostsForPairedMac(hosts: List<String>): List<String> {
        val pairedHost = ZevClipPreferences.endpoint(appContext)?.ipAddress ?: return emptyList()
        val pairedAddresses = resolveHostAddresses(pairedHost)
        val candidateAddresses = hosts.flatMap { resolveHostAddresses(it) }.toSet()

        val matchedByAddress = pairedAddresses.isNotEmpty() &&
            candidateAddresses.isNotEmpty() &&
            candidateAddresses.any { it in pairedAddresses }

        val candidates = if (matchedByAddress) {
            listOf(pairedHost) + hosts
        } else {
            // Some Android NSD results expose only IPv4 even when the paired Mac
            // works over IPv6. Keep the trusted ZevClip-paired host as the probe.
            listOf(pairedHost)
        }

        return candidates
            .map { NetworkInputValidator.normalizeHost(it) }
            .filter { NetworkInputValidator.validateHost(it) }
            .distinct()
            .sortedWith(
                compareByDescending<String> { it == pairedHost }
                    .thenByDescending { it.contains(':') }
                    .thenBy { it }
            )
    }

    private fun resolveHostAddresses(host: String): Set<String> {
        val normalizedHost = NetworkInputValidator.normalizeHost(host)
        val directAddress = normalizedHost.substringBefore('%').lowercase()
        val resolvedAddresses = try {
            InetAddress.getAllByName(normalizedHost)
                .mapNotNull { it.hostAddress?.substringBefore('%')?.lowercase() }
        } catch (_: Exception) {
            emptyList()
        }

        return (resolvedAddresses + directAddress)
            .filter { it.isNotBlank() }
            .toSet()
    }

    private fun probeBestEndpoint(
        hosts: List<String>,
        port: Int,
        serviceType: String,
        password: String,
        mode: ProbeMode
    ): AirPlayProbeResult {
        val validHosts = hosts
            .map { NetworkInputValidator.normalizeHost(it) }
            .filter { NetworkInputValidator.validateHost(it) }
            .distinct()

        validHosts.forEach { host ->
            val result = when {
                mode == ProbeMode.AUDIO_SESSION -> probeAudioSession(host, port, password)
                else -> probeRaopVisibility(host, port)
            }

            if (
                result is AirPlayProbeResult.Answered ||
                result is AirPlayProbeResult.Authenticated ||
                result is AirPlayProbeResult.AuthFailed ||
                result is AirPlayProbeResult.AudioSessionProbe
            ) {
                return result
            }
        }

        return AirPlayProbeResult.NoAnswer(validHosts.firstOrNull())
    }

    private fun probeRaop(host: String, port: Int, password: String): AirPlayProbeResult {
        val method = "OPTIONS"
        val uri = "*"
        val request = "$method $uri RTSP/1.0\r\n" +
            "CSeq: 1\r\n" +
            "User-Agent: AirPlay/409.16\r\n" +
            "\r\n"
        val firstResponse = probeSocketWithRetry(host, port, request)
        return retryWithDigestIfNeeded(host, port, method, uri, password, firstResponse) {
            "$method $uri RTSP/1.0\r\n" +
                "CSeq: 2\r\n" +
                "User-Agent: AirPlay/409.16\r\n" +
                "Authorization: $it\r\n" +
                "\r\n"
        }
    }

    private fun probeRaopVisibility(host: String, port: Int): AirPlayProbeResult {
        val request = "OPTIONS * RTSP/1.0\r\n" +
            "CSeq: 1\r\n" +
            "User-Agent: AirPlay/409.16\r\n" +
            "\r\n"
        return probeSocketWithRetry(host, port, request)
    }

    private fun probeAudioSession(host: String, port: Int, password: String): AirPlayProbeResult {
        return try {
            val address = InetAddress.getByName(host)
            Socket().use { socket ->
                socket.soTimeout = READ_TIMEOUT_MS
                socket.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)

                val reader = BufferedReader(
                    InputStreamReader(socket.getInputStream(), Charsets.UTF_8)
                )
                val sessionId = UUID.randomUUID().toString().replace("-", "")
                val clientInstance = randomHexId(16)
                val activeRemote = randomActiveRemote()
                val localAddress = socket.localAddress.hostAddress
                    ?.substringBefore('%')
                    ?.takeIf { it.isNotBlank() }
                    ?: if (host.contains(':')) "::1" else "127.0.0.1"
                val announceUri = "rtsp://${formatEndpointHost(host)}/$sessionId"
                val announceBody = audioSessionSdp(localAddress)
                var cseq = 1
                var digestChallenge: DigestChallenge? = null
                var digestUsername: String? = null
                var digestNonceCounter = 1

                fun configureDigest(response: RtspResponse): Boolean {
                    val challenge = response.authenticationChallenge()
                        ?.let { DigestChallenge.parse(it) }
                        ?: return false
                    digestChallenge = challenge
                    digestUsername = usernamesForRealm(challenge.realm).firstOrNull()
                    return !password.isBlank() && digestUsername != null
                }

                fun nextAuthorization(method: String, uri: String): String? {
                    val challenge = digestChallenge ?: return null
                    val username = digestUsername ?: return null
                    if (password.isBlank()) {
                        return null
                    }
                    val nonceCount = "%08x".format(digestNonceCounter++)
                    return digestAuthorization(
                        username = username,
                        password = password,
                        method = method,
                        uri = uri,
                        challenge = challenge,
                        nonceCount = nonceCount,
                        clientNonce = DIGEST_CLIENT_NONCE
                    )
                }

                fun sendAuthenticatedRtsp(
                    method: String,
                    uri: String,
                    headers: List<String> = emptyList(),
                    body: String = ""
                ): RtspResponse {
                    var response = sendRtspOnSession(
                        socket = socket,
                        reader = reader,
                        method = method,
                        uri = uri,
                        cseq = cseq++,
                        clientInstance = clientInstance,
                        activeRemote = activeRemote,
                        authorization = nextAuthorization(method, uri),
                        headers = headers,
                        body = body
                    )

                    if (response.requiresAuthentication() && configureDigest(response)) {
                        response = sendRtspOnSession(
                            socket = socket,
                            reader = reader,
                            method = method,
                            uri = uri,
                            cseq = cseq++,
                            clientInstance = clientInstance,
                            activeRemote = activeRemote,
                            authorization = nextAuthorization(method, uri),
                            headers = headers,
                            body = body
                        )
                    }

                    return response
                }

                val optionsResponse = sendAuthenticatedRtsp("OPTIONS", "*")
                if (!optionsResponse.isSuccess()) {
                    return AirPlayProbeResult.AudioSessionProbe(
                        host = host,
                        step = "OPTIONS",
                        firstLine = optionsResponse.firstLine,
                        detail = "The Mac did not accept the basic RAOP control probe, so the audio session was not started."
                    )
                }

                Thread.sleep(SESSION_STEP_DELAY_MS)
                val announceResponse = sendAuthenticatedRtsp(
                    method = "ANNOUNCE",
                    uri = announceUri,
                    headers = listOf("Content-Type: application/sdp"),
                    body = announceBody
                )
                if (!announceResponse.isSuccess()) {
                    return AirPlayProbeResult.AudioSessionProbe(
                        host = host,
                        step = "ANNOUNCE",
                        firstLine = announceResponse.firstLine,
                        detail = "macOS did not accept the minimal RAOP audio description. It may require encrypted AirPlay setup before audio packets."
                    )
                }

                Thread.sleep(AIRPLAY_APPROVAL_WAIT_MS)
                var setupResponse = RtspResponse("No response", emptyMap())
                var setupAccepted = false
                repeat(SETUP_RETRY_ROUNDS) { round ->
                    if (setupAccepted) {
                        return@repeat
                    }
                    setupVariants().forEach { setupVariant ->
                        setupResponse = sendAuthenticatedRtsp(
                            method = "SETUP",
                            uri = announceUri,
                            headers = listOf("Transport: ${setupVariant.transport}")
                        )
                        if (setupResponse.isSuccess()) {
                            setupAccepted = true
                            return@forEach
                        }
                    }
                    if (!setupResponse.isSuccess() && setupResponse.firstLine.contains("455")) {
                        Thread.sleep(AIRPLAY_APPROVAL_RETRY_WAIT_MS + (round * 1_000L))
                    }
                }

                AirPlayProbeResult.AudioSessionProbe(
                    host = host,
                    step = "SETUP",
                    firstLine = setupResponse.firstLine,
                    detail = if (setupResponse.isSuccess()) {
                        "RTSP audio setup was accepted on one AirPlay control session. Next step is Android playback capture and RTP audio packets."
                    } else if (setupResponse.firstLine.contains("520")) {
                        return probeAirPlay2Setup(
                            host = host,
                            port = port,
                            password = password,
                            classicFailure = setupResponse.firstLine,
                            localAddress = localAddress
                        )
                    } else {
                        "ANNOUNCE was accepted on one AirPlay control session, but SETUP was refused. The transport/auth details need adjustment before audio packets."
                    }
                )
            }
        } catch (error: Exception) {
            Log.w(TAG, "AirPlay audio session probe failed for $host:$port", error)
            AirPlayProbeResult.NoAnswer(host)
        }
    }

    private fun probeAirPlay2Setup(
        host: String,
        port: Int,
        password: String,
        classicFailure: String,
        localAddress: String
    ): AirPlayProbeResult.AudioSessionProbe {
        return try {
            val address = InetAddress.getByName(host)
            Socket().use { socket ->
                socket.soTimeout = READ_TIMEOUT_MS
                socket.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)

                val reader = BufferedReader(
                    InputStreamReader(socket.getInputStream(), Charsets.UTF_8)
                )
                val sessionId = UUID.randomUUID().toString().replace("-", "")
                val uri = "rtsp://${formatEndpointHost(host)}/$sessionId"
                val dacpId = randomHexId(16)
                val activeRemote = randomActiveRemote()
                var cseq = 1
                var digestChallenge: DigestChallenge? = null
                var digestUsername: String? = null
                var digestNonceCounter = 1

                fun configureDigest(response: RtspResponse): Boolean {
                    val challenge = response.authenticationChallenge()
                        ?.let { DigestChallenge.parse(it) }
                        ?: return false
                    digestChallenge = challenge
                    digestUsername = usernamesForRealm(challenge.realm).firstOrNull()
                    return !password.isBlank() && digestUsername != null
                }

                fun nextAuthorization(method: String, requestUri: String): String? {
                    val challenge = digestChallenge ?: return null
                    val username = digestUsername ?: return null
                    if (password.isBlank()) {
                        return null
                    }
                    val nonceCount = "%08x".format(digestNonceCounter++)
                    return digestAuthorization(
                        username = username,
                        password = password,
                        method = method,
                        uri = requestUri,
                        challenge = challenge,
                        nonceCount = nonceCount,
                        clientNonce = DIGEST_CLIENT_NONCE
                    )
                }

                fun sendAuthenticatedBytes(
                    method: String,
                    requestUri: String,
                    headers: List<String> = emptyList(),
                    body: ByteArray = ByteArray(0)
                ): RtspResponse {
                    var response = sendRtspBytesOnSession(
                        socket = socket,
                        reader = reader,
                        method = method,
                        uri = requestUri,
                        cseq = cseq++,
                        dacpId = dacpId,
                        activeRemote = activeRemote,
                        authorization = nextAuthorization(method, requestUri),
                        headers = headers,
                        body = body
                    )

                    if (response.requiresAuthentication() && configureDigest(response)) {
                        response = sendRtspBytesOnSession(
                            socket = socket,
                            reader = reader,
                            method = method,
                            uri = requestUri,
                            cseq = cseq++,
                            dacpId = dacpId,
                            activeRemote = activeRemote,
                            authorization = nextAuthorization(method, requestUri),
                            headers = headers,
                            body = body
                        )
                    }

                    return response
                }

                val infoResponse = sendAuthenticatedBytes(
                    method = "GET",
                    requestUri = "/info",
                    headers = listOf(
                        "X-Apple-ProtocolVersion: 1",
                        "Content-Type: application/x-apple-binary-plist"
                    ),
                    body = BinaryPlistWriter.write(airPlayInfoQualifierPlist())
                )
                if (!infoResponse.isSuccess()) {
                    return AirPlayProbeResult.AudioSessionProbe(
                        host = host,
                        step = "AIRPLAY2 INFO",
                        firstLine = infoResponse.firstLine,
                        detail = "Classic RAOP SETUP returned $classicFailure. macOS did not accept the AirPlay 2 /info preflight."
                    )
                }

                val setupResponse = sendAuthenticatedBytes(
                    method = "SETUP",
                    requestUri = uri,
                    headers = listOf(
                        "X-Apple-ProtocolVersion: 1",
                        "Content-Type: application/x-apple-binary-plist"
                    ),
                    body = BinaryPlistWriter.write(airPlay2SetupInfoPlist(localAddress))
                )

                AirPlayProbeResult.AudioSessionProbe(
                    host = host,
                    step = "AIRPLAY2 SETUP",
                    firstLine = setupResponse.firstLine,
                    detail = if (setupResponse.isSuccess()) {
                        "Classic RAOP SETUP returned $classicFailure, but AirPlay 2 setup was accepted after /info. Next step is the event channel and audio stream setup."
                    } else {
                        "Classic RAOP SETUP returned $classicFailure. AirPlay 2 /info was accepted, but setup was refused."
                    }
                )
            }
        } catch (error: Exception) {
            Log.w(TAG, "AirPlay 2 setup probe failed for $host:$port", error)
            AirPlayProbeResult.AudioSessionProbe(
                host = host,
                step = "AIRPLAY2 SETUP",
                firstLine = "No response",
                detail = "Classic RAOP SETUP returned $classicFailure. The AirPlay 2 control session could not be completed."
            )
        }
    }

    private fun sendRtspBytesOnSession(
        socket: Socket,
        reader: BufferedReader,
        method: String,
        uri: String,
        cseq: Int,
        dacpId: String,
        activeRemote: String,
        authorization: String?,
        headers: List<String>,
        body: ByteArray
    ): RtspResponse {
        val authHeader = authorization?.let { "Authorization: $it\r\n" }.orEmpty()
        val hostHeader = socket.inetAddress.hostAddress
            ?.let { formatEndpointHost(it) }
            ?: socket.inetAddress.hostName
        val request = buildString {
            append("$method $uri RTSP/1.0\r\n")
            append("CSeq: $cseq\r\n")
            append("DACP-ID: $dacpId\r\n")
            append("Active-Remote: $activeRemote\r\n")
            append("User-Agent: AirPlay/409.16\r\n")
            append("Host: $hostHeader:${socket.port}\r\n")
            append("Connection: keep-alive\r\n")
            headers.forEach { append("$it\r\n") }
            append(authHeader)
            if (body.isNotEmpty()) {
                append("Content-Length: ${body.size}\r\n")
            }
            append("\r\n")
        }

        socket.getOutputStream().write(request.toByteArray(Charsets.UTF_8))
        if (body.isNotEmpty()) {
            socket.getOutputStream().write(body)
        }
        socket.getOutputStream().flush()

        return readRtspResponse(reader)
    }

    private fun sendRtspOnSession(
        socket: Socket,
        reader: BufferedReader,
        method: String,
        uri: String,
        cseq: Int,
        clientInstance: String,
        activeRemote: String,
        authorization: String?,
        headers: List<String>,
        body: String
    ): RtspResponse {
        val authHeader = authorization?.let { "Authorization: $it\r\n" }.orEmpty()
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val request = buildString {
            append("$method $uri RTSP/1.0\r\n")
            append("CSeq: $cseq\r\n")
            append("User-Agent: iTunes/12.11.3 (Macintosh; OS X) AppleWebKit/605.1.15\r\n")
            append("Client-Instance: $clientInstance\r\n")
            append("DACP-ID: $clientInstance\r\n")
            append("Active-Remote: $activeRemote\r\n")
            append("Connection: keep-alive\r\n")
            headers.forEach { append("$it\r\n") }
            append(authHeader)
            if (bodyBytes.isNotEmpty()) {
                append("Content-Length: ${bodyBytes.size}\r\n")
            }
            append("\r\n")
        }

        socket.getOutputStream().write(request.toByteArray(Charsets.UTF_8))
        if (bodyBytes.isNotEmpty()) {
            socket.getOutputStream().write(bodyBytes)
        }
        socket.getOutputStream().flush()

        return readRtspResponse(reader)
    }

    private fun readRtspResponse(reader: BufferedReader): RtspResponse {
        val firstLine = reader.readLine().orEmpty()
        val headers = mutableMapOf<String, String>()
        var line = reader.readLine().orEmpty()

        while (line.isNotEmpty()) {
            val separatorIndex = line.indexOf(':')
            if (separatorIndex > 0) {
                headers[line.substring(0, separatorIndex).trim()] =
                    line.substring(separatorIndex + 1).trim()
            }
            line = reader.readLine().orEmpty()
        }

        val contentLength = headers.entries
            .firstOrNull { it.key.equals("Content-Length", ignoreCase = true) }
            ?.value
            ?.toIntOrNull()
            ?: 0
        if (contentLength > 0) {
            val bodyBuffer = CharArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val count = reader.read(bodyBuffer, read, contentLength - read)
                if (count <= 0) {
                    break
                }
                read += count
            }
        }

        return RtspResponse(firstLine, headers)
    }

    private fun sendSetupVariants(
        host: String,
        port: Int,
        uri: String,
        password: String,
        clientInstance: String,
        activeRemote: String
    ): AirPlayProbeResult {
        var lastResult: AirPlayProbeResult = AirPlayProbeResult.NoAnswer(host)
        var cseq = 4

        repeat(SETUP_RETRY_ROUNDS) { round ->
            setupVariants().forEach { setupVariant ->
                val result = sendRtspWithDigestRetry(
                    host = host,
                    port = port,
                    method = "SETUP",
                    uri = uri,
                    password = password,
                    requestBuilder = { authorization ->
                        val authHeader = authorization?.let { "Authorization: $it\r\n" }.orEmpty()
                        "SETUP $uri RTSP/1.0\r\n" +
                            "CSeq: ${cseq++}\r\n" +
                            clientHeaders(clientInstance, activeRemote) +
                            "Transport: ${setupVariant.transport}\r\n" +
                            authHeader +
                            "\r\n"
                    }
                )

                if (result.isSuccess()) {
                    return result
                }
                lastResult = result
            }

            if (lastResult.statusLine().contains("455")) {
                Thread.sleep(AIRPLAY_APPROVAL_RETRY_WAIT_MS + (round * 1_000L))
            }
        }

        return lastResult
    }

    private fun setupVariants(): List<SetupVariant> {
        return listOf(
            SetupVariant(
                cseq = 4,
                transport = "RTP/AVP/UDP;unicast;interleaved=0-1;mode=record;control_port=6001;timing_port=6002"
            ),
            SetupVariant(
                cseq = 5,
                transport = "RTP/AVP/UDP;unicast;mode=record;control_port=6001;timing_port=6002"
            ),
            SetupVariant(
                cseq = 6,
                transport = "RTP/AVP/UDP;unicast;mode=record;server_port=6000;control_port=6001;timing_port=6002"
            )
        )
    }

    private fun clientHeaders(clientInstance: String, activeRemote: String): String {
        return "User-Agent: iTunes/12.11.3 (Macintosh; OS X) AppleWebKit/605.1.15\r\n" +
            "Client-Instance: $clientInstance\r\n" +
            "DACP-ID: $clientInstance\r\n" +
            "Active-Remote: $activeRemote\r\n"
    }

    private fun randomHexId(length: Int): String {
        val bytes = ByteArray(length / 2)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02X".format(it) }
    }

    private fun randomActiveRemote(): String {
        return (1_000_000_000L + kotlin.random.Random.nextLong(8_000_000_000L)).toString()
    }

    private fun sendRtspWithDigestRetry(
        host: String,
        port: Int,
        method: String,
        uri: String,
        password: String,
        requestBuilder: (String?) -> String
    ): AirPlayProbeResult {
        val firstResponse = probeSocketWithRetry(host, port, requestBuilder(null))
        return retryWithDigestIfNeeded(host, port, method, uri, password, firstResponse) {
            requestBuilder(it)
        }
    }

    private fun sendRtspBytesWithDigestRetry(
        host: String,
        port: Int,
        method: String,
        uri: String,
        password: String,
        requestBuilder: (String?) -> ByteArray
    ): AirPlayProbeResult {
        val firstResponse = probeSocketWithRetry(host, port, requestBuilder(null))
        return retryWithDigestBytesIfNeeded(host, port, method, uri, password, firstResponse) {
            requestBuilder(it)
        }
    }

    private fun airPlay2SetupInfoPlist(localAddress: String): PlistValue.DictValue {
        val senderAddress = localAddress.substringBefore('%')
        val sessionUuid = UUID.randomUUID().toString().uppercase(Locale.US)
        val groupUuid = UUID.randomUUID().toString().uppercase(Locale.US)
        val senderMac = randomMacAddress()

        return PlistValue.DictValue(
            linkedMapOf(
                "deviceID" to PlistValue.StringValue(senderMac),
                "et" to PlistValue.IntValue(0),
                "groupContainsGroupLeader" to PlistValue.BoolValue(false),
                "groupUUID" to PlistValue.StringValue(groupUuid),
                "isMultiSelectAirPlay" to PlistValue.BoolValue(false),
                "macAddress" to PlistValue.StringValue(senderMac),
                "model" to PlistValue.StringValue("iPhone10,6"),
                "name" to PlistValue.StringValue("ZevClip"),
                "osBuildVersion" to PlistValue.StringValue(Build.DISPLAY.orEmpty()),
                "osName" to PlistValue.StringValue("iPhone OS"),
                "osVersion" to PlistValue.StringValue("17.0"),
                "senderSupportsRelay" to PlistValue.BoolValue(true),
                "sessionUUID" to PlistValue.StringValue(sessionUuid),
                "sourceVersion" to PlistValue.StringValue("409.16"),
                "timingPeerInfo" to PlistValue.DictValue(
                    linkedMapOf(
                        "Addresses" to PlistValue.ArrayValue(listOf(PlistValue.StringValue(senderAddress))),
                        "ID" to PlistValue.StringValue(senderAddress),
                        "SupportsClockPortMatchingOverride" to PlistValue.BoolValue(true)
                    )
                ),
                "timingPeerList" to PlistValue.ArrayValue(
                    listOf(
                        PlistValue.DictValue(
                            linkedMapOf(
                                "Addresses" to PlistValue.ArrayValue(listOf(PlistValue.StringValue(senderAddress))),
                                "ID" to PlistValue.StringValue(senderAddress),
                                "SupportsClockPortMatchingOverride" to PlistValue.BoolValue(true)
                            )
                        )
                    )
                ),
                "timingProtocol" to PlistValue.StringValue("NTP")
            )
        )
    }

    private fun airPlayInfoQualifierPlist(): PlistValue.DictValue {
        return PlistValue.DictValue(
            linkedMapOf(
                "qualifier" to PlistValue.ArrayValue(
                    listOf(PlistValue.StringValue("txtAirPlay"))
                )
            )
        )
    }

    private fun randomBytes(size: Int): ByteArray {
        return ByteArray(size).also { SecureRandom().nextBytes(it) }
    }

    private fun randomMacAddress(): String {
        val bytes = randomBytes(6)
        bytes[0] = (bytes[0].toInt() and 0xFE or 0x02).toByte()
        return bytes.joinToString(":") { "%02X".format(it) }
    }

    private fun audioSessionSdp(localAddress: String): String {
        val connectionAddress = localAddress.substringBefore('%')
        val addressFamily = if (connectionAddress.contains(':')) "IP6" else "IP4"
        return "v=0\r\n" +
            "o=ZevClip 0 0 IN $addressFamily $connectionAddress\r\n" +
            "s=ZevClip AirPlay Audio\r\n" +
            "c=IN $addressFamily $connectionAddress\r\n" +
            "t=0 0\r\n" +
            "m=audio 0 RTP/AVP 96\r\n" +
            "a=rtpmap:96 AppleLossless\r\n" +
            "a=fmtp:96 352 0 16 40 10 14 2 255 0 0 44100\r\n"
    }

    private fun probeAirPlayInfo(host: String, port: Int, password: String): AirPlayProbeResult {
        val method = "GET"
        val uri = "/info"
        val request = "$method $uri RTSP/1.0\r\n" +
            "Host: ${formatEndpointHost(host)}:$port\r\n" +
            "CSeq: 1\r\n" +
            "User-Agent: AirPlay/409.16\r\n" +
            "Connection: close\r\n" +
            "\r\n"
        val firstResponse = probeSocketWithRetry(host, port, request)
        return retryWithDigestIfNeeded(host, port, method, uri, password, firstResponse) {
            "$method $uri RTSP/1.0\r\n" +
                "Host: ${formatEndpointHost(host)}:$port\r\n" +
                "CSeq: 2\r\n" +
                "User-Agent: AirPlay/409.16\r\n" +
                "Authorization: $it\r\n" +
                "Connection: close\r\n" +
                "\r\n"
        }
    }

    private fun retryWithDigestIfNeeded(
        host: String,
        port: Int,
        method: String,
        uri: String,
        password: String,
        firstResponse: AirPlayProbeResult,
        authenticatedRequest: (String) -> String
    ): AirPlayProbeResult {
        if (firstResponse !is AirPlayProbeResult.Answered || !firstResponse.requiresAuthentication()) {
            return firstResponse
        }

        val challengeHeader = firstResponse.authenticationChallenge() ?: return firstResponse
        val challenge = DigestChallenge.parse(challengeHeader) ?: return firstResponse
        val usernames = usernamesForRealm(challenge.realm)
        var lastNonAuthFailure: AirPlayProbeResult.AuthFailed? = null

        usernames.forEach { username ->
            val authorization = digestAuthorization(
                username = username,
                password = password,
                method = method,
                uri = uri,
                challenge = challenge
            )
            val retryResponse = probeSocket(host, port, authenticatedRequest(authorization))
            if (retryResponse is AirPlayProbeResult.Answered) {
                when {
                    retryResponse.isSuccess() ->
                        return AirPlayProbeResult.Authenticated(host, username, retryResponse.firstLine)
                    !retryResponse.requiresAuthentication() ->
                        lastNonAuthFailure = AirPlayProbeResult.AuthFailed(
                            host = host,
                            username = username,
                            firstLine = retryResponse.firstLine
                        )
                }
            }
        }

        if (lastNonAuthFailure != null) {
            return lastNonAuthFailure!!
        }

        return AirPlayProbeResult.AuthRejected(host, challenge.realm, password.isNotEmpty())
    }

    private fun retryWithDigestBytesIfNeeded(
        host: String,
        port: Int,
        method: String,
        uri: String,
        password: String,
        firstResponse: AirPlayProbeResult,
        authenticatedRequest: (String) -> ByteArray
    ): AirPlayProbeResult {
        if (firstResponse !is AirPlayProbeResult.Answered || !firstResponse.requiresAuthentication()) {
            return firstResponse
        }

        val challengeHeader = firstResponse.authenticationChallenge() ?: return firstResponse
        val challenge = DigestChallenge.parse(challengeHeader) ?: return firstResponse
        val usernames = usernamesForRealm(challenge.realm)
        var lastNonAuthFailure: AirPlayProbeResult.AuthFailed? = null

        usernames.forEach { username ->
            val authorization = digestAuthorization(
                username = username,
                password = password,
                method = method,
                uri = uri,
                challenge = challenge
            )
            val retryResponse = probeSocket(host, port, authenticatedRequest(authorization))
            if (retryResponse is AirPlayProbeResult.Answered) {
                when {
                    retryResponse.isSuccess() ->
                        return AirPlayProbeResult.Authenticated(host, username, retryResponse.firstLine)
                    !retryResponse.requiresAuthentication() ->
                        lastNonAuthFailure = AirPlayProbeResult.AuthFailed(
                            host = host,
                            username = username,
                            firstLine = retryResponse.firstLine
                        )
                }
            }
        }

        if (lastNonAuthFailure != null) {
            return lastNonAuthFailure!!
        }

        return AirPlayProbeResult.AuthRejected(host, challenge.realm, password.isNotEmpty())
    }

    private fun probeSocketWithRetry(host: String, port: Int, request: String): AirPlayProbeResult {
        return probeSocketWithRetry(host, port, request.toByteArray(Charsets.UTF_8))
    }

    private fun probeSocketWithRetry(host: String, port: Int, request: ByteArray): AirPlayProbeResult {
        val firstResult = probeSocket(host, port, request)
        if (firstResult !is AirPlayProbeResult.NoAnswer) {
            return firstResult
        }

        Thread.sleep(SOCKET_RETRY_DELAY_MS)
        return probeSocket(host, port, request)
    }

    private fun probeSocket(host: String, port: Int, request: String): AirPlayProbeResult {
        return probeSocket(host, port, request.toByteArray(Charsets.UTF_8))
    }

    private fun probeSocket(host: String, port: Int, request: ByteArray): AirPlayProbeResult {
        return try {
            val address = InetAddress.getByName(host)
            Socket().use { socket ->
                socket.soTimeout = READ_TIMEOUT_MS
                socket.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
                socket.getOutputStream().write(request)
                socket.getOutputStream().flush()

                val reader = BufferedReader(
                    InputStreamReader(socket.getInputStream(), Charsets.UTF_8)
                )
                val firstLine = reader.readLine().orEmpty()
                val headers = mutableMapOf<String, String>()
                var line = reader.readLine().orEmpty()

                while (line.isNotEmpty()) {
                    val separatorIndex = line.indexOf(':')
                    if (separatorIndex > 0) {
                        headers[line.substring(0, separatorIndex).trim()] =
                            line.substring(separatorIndex + 1).trim()
                    }
                    line = reader.readLine().orEmpty()
                }

                if (firstLine.isNotBlank()) {
                    AirPlayProbeResult.Answered(host, firstLine, headers)
                } else {
                    AirPlayProbeResult.NoAnswer(host)
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "AirPlay probe failed for $host:$port", error)
            AirPlayProbeResult.NoAnswer(host)
        }
    }

    private fun digestAuthorization(
        username: String,
        password: String,
        method: String,
        uri: String,
        challenge: DigestChallenge,
        nonceCount: String = DIGEST_NONCE_COUNT,
        clientNonce: String = DIGEST_CLIENT_NONCE
    ): String {
        val ha1 = md5Hex("$username:${challenge.realm}:$password")
        val ha2 = md5Hex("$method:$uri")
        val qop = challenge.qop
            ?.split(',')
            ?.map { it.trim().trim('"') }
            ?.firstOrNull { it.equals("auth", ignoreCase = true) }

        val response = if (qop == null) {
            md5Hex("$ha1:${challenge.nonce}:$ha2")
        } else {
            md5Hex("$ha1:${challenge.nonce}:$nonceCount:$clientNonce:$qop:$ha2")
        }

        val parts = mutableListOf(
            "username=\"$username\"",
            "realm=\"${challenge.realm}\"",
            "nonce=\"${challenge.nonce}\"",
            "uri=\"$uri\"",
            "response=\"$response\""
        )

        if (challenge.algorithm.isNotBlank()) {
            parts += "algorithm=${challenge.algorithm}"
        }

        if (qop != null) {
            parts += "qop=$qop"
            parts += "nc=$nonceCount"
            parts += "cnonce=\"$clientNonce\""
        }

        return "Digest ${parts.joinToString(", ")}"
    }

    private fun usernamesForRealm(realm: String): List<String> {
        val normalizedRealm = realm.lowercase(Locale.US)
        return when {
            "raop" in normalizedRealm -> listOf("iTunes", "AirPlay")
            "airplay" in normalizedRealm -> listOf("AirPlay", "iTunes")
            else -> listOf("AirPlay", "iTunes")
        }
    }

    private fun md5Hex(value: String): String {
        val digest = MessageDigest.getInstance("MD5")
            .digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun stopDiscovery() {
        val listeners = discoveryListeners.toMap()
        discoveryListeners.clear()

        listeners.values.forEach { listener ->
            try {
                nsdManager.stopServiceDiscovery(listener)
            } catch (error: IllegalArgumentException) {
                Log.w(TAG, "AirPlay discovery was already stopped", error)
            }
        }
    }

    private fun updateStatus(status: String, isDiscovering: Boolean) {
        handler.post { onStatusChanged(status, isDiscovering) }
    }

    private fun serviceKey(serviceInfo: NsdServiceInfo, serviceType: String): String {
        return "${normalizedServiceType(serviceType)}:${serviceInfo.serviceName}"
    }

    private fun normalizedServiceType(serviceType: String): String {
        return serviceType.trimEnd('.')
    }

    private fun txtSummary(serviceInfo: NsdServiceInfo): String {
        val attributes = serviceInfo.attributes
        if (attributes.isEmpty()) {
            return "TXT: none"
        }

        val preferredKeys = listOf("model", "srcvers", "vv", "ft", "sf", "flags", "pk", "pw", "am")
        val pairs = preferredKeys.mapNotNull { key ->
            val value = attributes[key] ?: return@mapNotNull null
            "$key=${value.toString(Charsets.UTF_8).ifBlank { "<binary>" }}"
        }

        return if (pairs.isEmpty()) {
            "TXT: ${attributes.keys.sorted().joinToString(", ")}"
        } else {
            "TXT: ${pairs.joinToString(", ")}"
        }
    }

    private data class AirPlayService(
        val info: NsdServiceInfo,
        val serviceType: String
    )

    private data class AirPlayReceiverInfo(
        val serviceName: String,
        val txtSummary: String
    )

    private data class SetupVariant(
        val cseq: Int,
        val transport: String
    )

    private data class RtspResponse(
        val firstLine: String,
        val headers: Map<String, String>
    ) {
        fun isSuccess(): Boolean {
            return firstLine.contains(" 200 ")
        }

        fun requiresAuthentication(): Boolean {
            return firstLine.contains("401")
        }

        fun authenticationChallenge(): String? {
            return headers.entries
                .firstOrNull { it.key.equals("WWW-Authenticate", ignoreCase = true) }
                ?.value
        }
    }

    private sealed class PlistValue {
        data class BoolValue(val value: Boolean) : PlistValue()
        data class IntValue(val value: Long) : PlistValue()
        data class StringValue(val value: String) : PlistValue()
        data class DataValue(val value: ByteArray) : PlistValue() {
            override fun equals(other: Any?): Boolean {
                return other is DataValue && value.contentEquals(other.value)
            }

            override fun hashCode(): Int {
                return value.contentHashCode()
            }
        }
        data class ArrayValue(val values: List<PlistValue>) : PlistValue()
        data class DictValue(val values: LinkedHashMap<String, PlistValue>) : PlistValue()
    }

    private sealed class PlistObject {
        data class BoolObject(val value: Boolean) : PlistObject()
        data class IntObject(val value: Long) : PlistObject()
        data class StringObject(val value: String) : PlistObject()
        data class DataObject(val value: ByteArray) : PlistObject()
        data class ArrayObject(val refs: List<Int>) : PlistObject()
        data class DictObject(val keyRefs: List<Int>, val valueRefs: List<Int>) : PlistObject()
    }

    private object BinaryPlistWriter {
        fun write(root: PlistValue): ByteArray {
            val objects = mutableListOf<PlistObject>()

            fun add(value: PlistValue): Int {
                val index = objects.size
                objects += PlistObject.BoolObject(false)
                objects[index] = when (value) {
                    is PlistValue.BoolValue -> PlistObject.BoolObject(value.value)
                    is PlistValue.IntValue -> PlistObject.IntObject(value.value)
                    is PlistValue.StringValue -> PlistObject.StringObject(value.value)
                    is PlistValue.DataValue -> PlistObject.DataObject(value.value)
                    is PlistValue.ArrayValue -> PlistObject.ArrayObject(value.values.map { add(it) })
                    is PlistValue.DictValue -> {
                        val keyRefs = mutableListOf<Int>()
                        val valueRefs = mutableListOf<Int>()
                        value.values.forEach { (key, childValue) ->
                            keyRefs += add(PlistValue.StringValue(key))
                            valueRefs += add(childValue)
                        }
                        PlistObject.DictObject(keyRefs, valueRefs)
                    }
                }
                return index
            }

            val rootIndex = add(root)
            val objectRefSize = bytesNeeded(objects.size.toLong())
            val body = ByteArrayOutputStream()
            val offsets = mutableListOf<Int>()

            body.write("bplist00".toByteArray(Charsets.US_ASCII))
            objects.forEach { plistObject ->
                offsets += body.size()
                writeObject(body, plistObject, objectRefSize)
            }

            val offsetTableOffset = body.size()
            val offsetIntSize = bytesNeeded(offsets.maxOrNull()?.toLong() ?: 0L)
            offsets.forEach { offset ->
                body.writeSizedInt(offset.toLong(), offsetIntSize)
            }

            body.write(ByteArray(6))
            body.write(offsetIntSize)
            body.write(objectRefSize)
            body.writeSizedInt(objects.size.toLong(), 8)
            body.writeSizedInt(rootIndex.toLong(), 8)
            body.writeSizedInt(offsetTableOffset.toLong(), 8)
            return body.toByteArray()
        }

        private fun writeObject(
            output: ByteArrayOutputStream,
            plistObject: PlistObject,
            objectRefSize: Int
        ) {
            when (plistObject) {
                is PlistObject.BoolObject -> output.write(if (plistObject.value) 0x09 else 0x08)
                is PlistObject.IntObject -> {
                    val intSize = bytesNeeded(plistObject.value).coerceAtLeast(1)
                    val power = when {
                        intSize <= 1 -> 0
                        intSize <= 2 -> 1
                        intSize <= 4 -> 2
                        else -> 3
                    }
                    val byteCount = 1 shl power
                    output.write(0x10 or power)
                    output.writeSizedInt(plistObject.value, byteCount)
                }
                is PlistObject.StringObject -> writeString(output, plistObject.value)
                is PlistObject.DataObject -> {
                    writeCountMarker(output, 0x40, plistObject.value.size)
                    output.write(plistObject.value)
                }
                is PlistObject.ArrayObject -> {
                    writeCountMarker(output, 0xA0, plistObject.refs.size)
                    plistObject.refs.forEach { output.writeSizedInt(it.toLong(), objectRefSize) }
                }
                is PlistObject.DictObject -> {
                    writeCountMarker(output, 0xD0, plistObject.keyRefs.size)
                    plistObject.keyRefs.forEach { output.writeSizedInt(it.toLong(), objectRefSize) }
                    plistObject.valueRefs.forEach { output.writeSizedInt(it.toLong(), objectRefSize) }
                }
            }
        }

        private fun writeString(output: ByteArrayOutputStream, value: String) {
            if (value.all { it.code <= 0x7F }) {
                val bytes = value.toByteArray(Charsets.US_ASCII)
                writeCountMarker(output, 0x50, bytes.size)
                output.write(bytes)
            } else {
                writeCountMarker(output, 0x60, value.length)
                value.forEach { char ->
                    output.write((char.code ushr 8) and 0xFF)
                    output.write(char.code and 0xFF)
                }
            }
        }

        private fun writeCountMarker(output: ByteArrayOutputStream, typeMarker: Int, count: Int) {
            if (count < 15) {
                output.write(typeMarker or count)
            } else {
                output.write(typeMarker or 0x0F)
                output.write(0x10)
                output.write(count)
            }
        }

        private fun bytesNeeded(value: Long): Int {
            return when {
                value <= 0xFF -> 1
                value <= 0xFFFF -> 2
                value <= 0xFFFFFFFFL -> 4
                else -> 8
            }
        }

        private fun ByteArrayOutputStream.writeSizedInt(value: Long, byteCount: Int) {
            for (index in byteCount - 1 downTo 0) {
                write(((value ushr (index * 8)) and 0xFF).toInt())
            }
        }
    }

    private enum class ProbeMode {
        BASIC,
        AUDIO_SESSION
    }

    private data class DigestChallenge(
        val realm: String,
        val nonce: String,
        val algorithm: String = "MD5",
        val qop: String? = null
    ) {
        companion object {
            fun parse(header: String): DigestChallenge? {
                if (!header.trimStart().startsWith("Digest", ignoreCase = true)) {
                    return null
                }

                val values = AUTH_VALUE_REGEX.findAll(header)
                    .associate { match ->
                        match.groupValues[1].lowercase(Locale.US) to
                            (match.groupValues[3].ifBlank { match.groupValues[4] })
                    }

                val realm = values["realm"].orEmpty()
                val nonce = values["nonce"].orEmpty()
                if (realm.isBlank() || nonce.isBlank()) {
                    return null
                }

                return DigestChallenge(
                    realm = realm,
                    nonce = nonce,
                    algorithm = values["algorithm"]?.ifBlank { "MD5" } ?: "MD5",
                    qop = values["qop"]
                )
            }
        }
    }

    private sealed class AirPlayProbeResult {
        data class Answered(
            val host: String,
            val firstLine: String,
            val headers: Map<String, String>
        ) : AirPlayProbeResult()
        data class Authenticated(
            val host: String,
            val username: String,
            val firstLine: String
        ) : AirPlayProbeResult()
        data class AuthRejected(
            val host: String,
            val realm: String,
            val hadPassword: Boolean
        ) : AirPlayProbeResult()
        data class AuthFailed(
            val host: String,
            val username: String,
            val firstLine: String
        ) : AirPlayProbeResult()
        data class AudioSessionProbe(
            val host: String,
            val step: String,
            val firstLine: String,
            val detail: String
        ) : AirPlayProbeResult()
        data class NoAnswer(val host: String?) : AirPlayProbeResult()

        fun message(receiverInfo: AirPlayReceiverInfo, port: Int): String {
            return when (this) {
                is Answered -> {
                    if (requiresAuthentication()) {
                        val authChallenge = authenticationChallenge()
                            ?: "No WWW-Authenticate header returned"
                        "Paired Mac AirPlay is visible at ${host.formatEndpointHost()}:$port and requires auth before streaming: $authChallenge. ${receiverInfo.txtSummary}"
                    } else {
                        "Paired Mac AirPlay answered from ${host.formatEndpointHost()}:$port: $firstLine. ${receiverInfo.txtSummary}"
                    }
                }
                is Authenticated -> {
                    "Paired Mac AirPlay accepted Digest auth at ${host.formatEndpointHost()}:$port using $username: $firstLine. Audio streaming can be tested next."
                }
                is AuthFailed -> {
                    "Paired Mac AirPlay answered Digest auth at ${host.formatEndpointHost()}:$port using $username, but RTSP failed: $firstLine. This is not ready for audio streaming yet."
                }
                is AuthRejected -> {
                    val passwordHint = if (hadPassword) {
                        "The password was sent but macOS still refused it."
                    } else {
                        "If the Mac has Require Password enabled, enter that AirPlay password and test again."
                    }
                    "Paired Mac AirPlay is visible at ${host.formatEndpointHost()}:$port, but Digest auth for realm \"$realm\" was refused. $passwordHint"
                }
                is AudioSessionProbe -> {
                    "Audio session probe reached $step at ${host.formatEndpointHost()}:$port: $firstLine. $detail"
                }
                is NoAnswer -> {
                    val endpoint = host?.let { " at ${it.formatEndpointHost()}:$port" }.orEmpty()
                    "Found ${receiverInfo.serviceName}$endpoint, but macOS did not answer the AirPlay probe."
                }
            }
        }

        fun requiresAuthentication(): Boolean {
            return this is Answered && firstLine.contains("401")
        }

        fun authenticationChallenge(): String? {
            return if (this is Answered) {
                headers.entries
                    .firstOrNull { it.key.equals("WWW-Authenticate", ignoreCase = true) }
                    ?.value
            } else {
                null
            }
        }

        fun isSuccess(): Boolean {
            return when (this) {
                is Answered -> firstLine.contains(" 200 ")
                is Authenticated -> firstLine.contains(" 200 ")
                is AuthFailed -> false
                is AudioSessionProbe -> firstLine.contains(" 200 ")
                else -> false
            }
        }

        fun statusLine(): String {
            return when (this) {
                is Answered -> firstLine
                is Authenticated -> firstLine
                is AuthFailed -> firstLine
                is AuthRejected -> "RTSP/1.0 401 Unauthorized"
                is AudioSessionProbe -> firstLine
                is NoAnswer -> "No response"
            }
        }

        protected fun String.formatEndpointHost(): String {
            return if (contains(':')) "[$this]" else this
        }
    }

    private companion object {
        const val TAG = "ZevClipAirPlay"
        const val RAOP_SERVICE_TYPE = "_raop._tcp."
        const val AIRPLAY_SERVICE_TYPE = "_airplay._tcp."
        val SERVICE_TYPES = listOf(RAOP_SERVICE_TYPE, AIRPLAY_SERVICE_TYPE)
        val SERVICE_TYPES_NORMALIZED = SERVICE_TYPES.map { it.trimEnd('.') }.toSet()
        const val SELECTION_DELAY_MS = 1_500L
        const val DISCOVERY_TIMEOUT_MS = 8_000L
        const val CONNECT_TIMEOUT_MS = 1_800
        const val READ_TIMEOUT_MS = 3_000
        const val SOCKET_RETRY_DELAY_MS = 350L
        const val SESSION_STEP_DELAY_MS = 250L
        const val AIRPLAY_APPROVAL_WAIT_MS = 5_000L
        const val AIRPLAY_APPROVAL_RETRY_WAIT_MS = 3_000L
        const val SETUP_RETRY_ROUNDS = 3
        const val DIGEST_NONCE_COUNT = "00000001"
        const val DIGEST_CLIENT_NONCE = "zevclip"
        const val NATIVE_RAOP_PORT = 7000
        val THIRD_PARTY_RECEIVER_NAME_MARKERS = listOf(
            "airdroid",
            "airscreen",
            "apowermirror",
            "letsview",
            "lonelyscreen",
            "reflector"
        )
        val AUTH_VALUE_REGEX = Regex("""([A-Za-z0-9_-]+)=("(.*?)"|([^,\s]+))""")

        fun formatEndpointHost(host: String): String {
            return if (host.contains(':')) "[$host]" else host
        }
    }
}
