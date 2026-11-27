package com.nuvio.app.bestcine.cast

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.Foundation.NSDate
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.Foundation.timeIntervalSince1970
import platform.posix.AF_INET
import platform.posix.EAGAIN
import platform.posix.EINTR
import platform.posix.F_GETFL
import platform.posix.F_SETFL
import platform.posix.IPPROTO_TCP
import platform.posix.O_NONBLOCK
import platform.posix.POLLERR
import platform.posix.POLLHUP
import platform.posix.POLLIN
import platform.posix.POLLOUT
import platform.posix.SOCK_DGRAM
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_ERROR
import platform.posix.SO_KEEPALIVE
import platform.posix.SO_NOSIGPIPE
import platform.posix.SO_REUSEADDR
import platform.posix.TCP_NODELAY
import platform.posix.accept
import platform.posix.bind
import platform.posix.close
import platform.posix.connect
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.getsockname
import platform.posix.getsockopt
import platform.posix.listen
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.recv
import platform.posix.send
import platform.posix.sendto
import platform.posix.setsockopt
import platform.posix.sockaddr_in
import platform.posix.socklen_tVar
import platform.posix.socket
import kotlin.concurrent.Volatile

@Composable
internal actual fun BestCineCastModalContainer(
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 460.dp)
                .padding(horizontal = 16.dp, vertical = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
internal actual object BestCineCastPlatform {
    actual val supportsSystemScreenCastShortcut: Boolean = false

    actual val isBatteryOptimizationRestricting: Boolean = false

    actual fun openBatteryOptimizationSettings(): Boolean = false

    actual fun openSystemScreenCastSettings(): Boolean = false

    actual suspend fun discoverSsdpLocations(timeoutMs: Long): List<String> = withContext(Dispatchers.IO) {
        // No iOS 14+, multicast UDP (239.255.255.250:1900) exige o entitlement restrito
        // com.apple.developer.networking.multicast (indisponivel em contas pessoais SideStore/AltStore).
        // Por isso combinamos em paralelo:
        //  1) SSDP multicast padrao (funciona se autorizado pelo sistema);
        //  2) SSDP M-SEARCH unicast na sub-rede /24 local (nao exige entitlement multicast);
        //  3) Varredura TCP nao-bloqueante nas portas DLNA conhecidas de Smart TVs (Samsung, Sony, LG, Android TV, TCL/Hisense).
        val lanIp = resolveLocalLanIpv4("192.168.1.1")
            ?: resolveLocalLanIpv4("10.0.0.1")
            ?: resolveLocalLanIpv4("1.1.1.1")
        val lanParts = lanIp?.split('.')?.takeIf { it.size == 4 }
        val subnetPrefix = lanParts?.let { "${it[0]}.${it[1]}.${it[2]}" }
        val selfOctet = lanParts?.getOrNull(3)?.toIntOrNull() ?: -1

        val found = linkedSetOf<String>()
        coroutineScope {
            val udpJob = async(Dispatchers.IO) {
                discoverViaUdpSsdp(
                    timeoutMs = timeoutMs,
                    subnetPrefix = subnetPrefix,
                    selfOctet = selfOctet,
                )
            }
            val tcpJob = async(Dispatchers.IO) {
                if (subnetPrefix != null) {
                    discoverViaLanTcpProbe(
                        subnetPrefix = subnetPrefix,
                        selfOctet = selfOctet,
                    )
                } else {
                    emptyList()
                }
            }
            found.addAll(udpJob.await())
            found.addAll(tcpJob.await())
        }
        found.toList()
    }

    private fun discoverViaUdpSsdp(
        timeoutMs: Long,
        subnetPrefix: String?,
        selfOctet: Int,
    ): List<String> {
        val found = linkedSetOf<String>()
        val fd = socket(AF_INET, SOCK_DGRAM, 0)
        if (fd < 0) return emptyList()

        try {
            val searchTargets = listOf(
                "urn:schemas-upnp-org:service:AVTransport:1",
                "urn:schemas-upnp-org:device:MediaRenderer:1",
            )
            // 239.255.255.250 em network byte order (little-endian arm64/x86_64): 0xFAFFFFEFu
            val multicastAddrBe = 0xFAFFFFEFu
            // Porta 1900 (0x076C) em network byte order: 0x6C07
            val portBe: UShort = 0x6C07u

            memScoped {
                val dest = alloc<sockaddr_in>()
                dest.sin_len = sizeOf<sockaddr_in>().toUByte()
                dest.sin_family = AF_INET.toUByte()
                dest.sin_port = portBe

                val payloads = searchTargets.map { st ->
                    buildString {
                        append("M-SEARCH * HTTP/1.1\r\n")
                        append("HOST: 239.255.255.250:1900\r\n")
                        append("MAN: \"ssdp:discover\"\r\n")
                        append("MX: 2\r\n")
                        append("ST: $st\r\n")
                        append("\r\n")
                    }.encodeToByteArray()
                }

                // 1) Multicast padrao
                dest.sin_addr.s_addr = multicastAddrBe
                payloads.forEach { msg ->
                    msg.usePinned { pinned ->
                        sendto(
                            fd,
                            pinned.addressOf(0),
                            msg.size.convert(),
                            0,
                            dest.ptr.reinterpret(),
                            sizeOf<sockaddr_in>().convert(),
                        )
                    }
                }

                // 2) Unicast direto na sub-rede /24 local (funciona no iOS 14+ sem entitlement multicast)
                if (subnetPrefix != null) {
                    val primaryPayload = payloads.first()
                    primaryPayload.usePinned { pinned ->
                        for (host in 1..254) {
                            if (host == selfOctet) continue
                            val addrBe = parseIpv4ToNetworkOrder("$subnetPrefix.$host") ?: continue
                            dest.sin_addr.s_addr = addrBe
                            sendto(
                                fd,
                                pinned.addressOf(0),
                                primaryPayload.size.convert(),
                                0,
                                dest.ptr.reinterpret(),
                                sizeOf<sockaddr_in>().convert(),
                            )
                        }
                    }
                }

                val pfd = alloc<pollfd>()
                pfd.fd = fd
                pfd.events = POLLIN.convert()
                val buf = ByteArray(4096)
                val deadlineMs = currentTimeMillisIos() + timeoutMs.coerceIn(1000L, 5000L)

                while (true) {
                    val remaining = (deadlineMs - currentTimeMillisIos()).coerceAtMost(250L)
                    if (remaining <= 0L) break
                    val ready = poll(pfd.ptr, 1u, remaining.toInt())
                    if (ready > 0) {
                        val read = buf.usePinned { pinned ->
                            recv(fd, pinned.addressOf(0), buf.size.convert(), 0).toInt()
                        }
                        if (read > 0) {
                            val text = buf.decodeToString(0, read)
                            text.lineSequence()
                                .map { it.trim() }
                                .firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
                                ?.substringAfter(':')
                                ?.trim()
                                ?.takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
                                ?.let { found.add(it) }
                        }
                    }
                }
            }
        } finally {
            close(fd)
        }

        return found.toList()
    }

    private suspend fun discoverViaLanTcpProbe(
        subnetPrefix: String,
        selfOctet: Int,
    ): List<String> = coroutineScope {
        val portSpecs = listOf(
            9197 to listOf("/dmr"),
            7676 to listOf("/smp_15_", "/smp_2_", "/smp_7_", "/smp_10_", "/smp_22_"),
            52235 to listOf("/dmr/SamsungMRDesc.xml"),
            52323 to listOf("/dmr.xml"),
            49152 to listOf("/description.xml", "/rootDesc.xml"),
            38400 to listOf("/description.xml"),
        )
        val hosts = (1..254).filter { it != selfOctet }.map { "$subnetPrefix.$it" }

        portSpecs.map { (port, paths) ->
            async(Dispatchers.IO) {
                val openIps = probeTcpPortOnHosts(hosts = hosts, port = port, batchSize = 16, timeoutMs = 180)
                buildList {
                    for (ip in openIps) {
                        for (path in paths) {
                            add("http://$ip:$port$path")
                        }
                    }
                }
            }
        }.awaitAll().flatten()
    }

    private fun probeTcpPortOnHosts(
        hosts: List<String>,
        port: Int,
        batchSize: Int,
        timeoutMs: Int,
    ): List<String> {
        val openIps = mutableListOf<String>()
        val portBe = toNetworkPortOrder(port)

        for (batch in hosts.chunked(batchSize)) {
            memScoped {
                val fds = IntArray(batch.size) { -1 }
                val pfds = allocArray<pollfd>(batch.size)
                var pendingCount = 0

                try {
                    for (idx in batch.indices) {
                        val ip = batch[idx]
                        val ipBe = parseIpv4ToNetworkOrder(ip)
                        if (ipBe == null) {
                            pfds[idx].fd = -1
                            pfds[idx].events = 0.toShort()
                            pfds[idx].revents = 0.toShort()
                            continue
                        }

                        val fd = socket(AF_INET, SOCK_STREAM, 0)
                        if (fd < 0) {
                            pfds[idx].fd = -1
                            pfds[idx].events = 0.toShort()
                            pfds[idx].revents = 0.toShort()
                            continue
                        }

                        val flags = fcntl(fd, F_GETFL, 0)
                        if (flags >= 0) {
                            fcntl(fd, F_SETFL, flags or O_NONBLOCK)
                        }

                        val remote = alloc<sockaddr_in>()
                        remote.sin_len = sizeOf<sockaddr_in>().toUByte()
                        remote.sin_family = AF_INET.toUByte()
                        remote.sin_port = portBe
                        remote.sin_addr.s_addr = ipBe

                        val rc = connect(fd, remote.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
                        if (rc == 0) {
                            openIps.add(ip)
                            close(fd)
                            pfds[idx].fd = -1
                            pfds[idx].events = 0.toShort()
                            pfds[idx].revents = 0.toShort()
                        } else {
                            fds[idx] = fd
                            pfds[idx].fd = fd
                            pfds[idx].events = POLLOUT.convert()
                            pfds[idx].revents = 0.toShort()
                            pendingCount += 1
                        }
                    }

                    if (pendingCount > 0) {
                        val ready = poll(pfds, batch.size.toUInt(), timeoutMs)
                        if (ready > 0) {
                            val soErr = alloc<IntVar>()
                            val soLen = alloc<socklen_tVar>()
                            for (idx in batch.indices) {
                                val fd = fds[idx]
                                if (fd < 0) continue
                                val revents = pfds[idx].revents.toInt()
                                if ((revents and POLLOUT) != 0 && (revents and (POLLERR or POLLHUP)) == 0) {
                                    soErr.value = -1
                                    soLen.value = sizeOf<IntVar>().convert()
                                    if (getsockopt(fd, SOL_SOCKET, SO_ERROR, soErr.ptr, soLen.ptr) == 0 && soErr.value == 0) {
                                        openIps.add(batch[idx])
                                    }
                                }
                            }
                        }
                    }
                } finally {
                    for (fd in fds) {
                        if (fd >= 0) close(fd)
                    }
                }
            }
        }

        return openIps
    }

    @Volatile
    private var proxyServer: IosDlnaHttpProxy? = null

    actual fun startLocalStreamProxy(
        sourceUrl: String,
        headers: Map<String, String>,
        contentType: String,
        isLive: Boolean,
        targetHostIp: String?,
    ): String? {
        stopLocalStreamProxy()
        val lanIp = resolveLocalLanIpv4(targetHostIp) ?: return null
        val server = IosDlnaHttpProxy.create(
            defaultUrl = sourceUrl,
            defaultHeaders = headers,
            defaultContentType = contentType,
            isLiveStream = isLive,
        ) ?: return null
        proxyServer = server
        server.start()
        val ext = when {
            isLive || contentType.contains("mp2t", ignoreCase = true) -> "live.ts"
            contentType.contains("mkv", ignoreCase = true) || contentType.contains("matroska", ignoreCase = true) -> "stream.mkv"
            else -> "stream.mp4"
        }
        return "http://$lanIp:${server.port}/$ext"
    }

    actual fun hasLocalProxyReceivedRequest(): Boolean =
        proxyServer?.hasReceivedRequest == true

    actual fun lastLocalProxyDiagnostic(): String? =
        proxyServer?.lastDiagnostic

    actual fun stopLocalStreamProxy() {
        proxyServer?.stop()
        proxyServer = null
    }
}

private fun currentTimeMillisIos(): Long =
    (NSDate().timeIntervalSince1970 * 1000.0).toLong()

@OptIn(ExperimentalForeignApi::class)
private fun resolveLocalLanIpv4(targetHostIp: String?): String? = memScoped {
    val targetBe = parseIpv4ToNetworkOrder(targetHostIp) ?: parseIpv4ToNetworkOrder("192.168.1.1") ?: return null
    val fd = socket(AF_INET, SOCK_DGRAM, 0)
    if (fd < 0) return null
    try {
        val remote = alloc<sockaddr_in>()
        remote.sin_len = sizeOf<sockaddr_in>().toUByte()
        remote.sin_family = AF_INET.toUByte()
        remote.sin_port = 0x5000u // Porta 80 em network byte order
        remote.sin_addr.s_addr = targetBe

        if (connect(fd, remote.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) != 0) {
            return null
        }

        val local = alloc<sockaddr_in>()
        val len = alloc<socklen_tVar>()
        len.value = sizeOf<sockaddr_in>().convert()
        if (getsockname(fd, local.ptr.reinterpret(), len.ptr) != 0) {
            return null
        }

        val raw = local.sin_addr.s_addr
        val b0 = (raw and 0xFFu).toInt()
        val b1 = ((raw shr 8) and 0xFFu).toInt()
        val b2 = ((raw shr 16) and 0xFFu).toInt()
        val b3 = ((raw shr 24) and 0xFFu).toInt()
        if (b0 == 0 || b0 == 127) return null
        "$b0.$b1.$b2.$b3"
    } finally {
        close(fd)
    }
}

private fun toNetworkPortOrder(port: Int): UShort =
    (((port and 0xFF) shl 8) or ((port ushr 8) and 0xFF)).toUShort()

private fun parseIpv4ToNetworkOrder(ip: String?): UInt? {
    val parts = ip?.trim()?.split('.') ?: return null
    if (parts.size != 4) return null
    val octets = parts.map { it.toIntOrNull()?.takeIf { v -> v in 0..255 } ?: return null }
    return (octets[0].toUInt()) or
        (octets[1].toUInt() shl 8) or
        (octets[2].toUInt() shl 16) or
        (octets[3].toUInt() shl 24)
}

@OptIn(ExperimentalForeignApi::class)
private class IosDlnaHttpProxy private constructor(
    private val serverFd: Int,
    val port: Int,
    private val defaultUrl: String,
    private val defaultHeaders: Map<String, String>,
    private val defaultContentType: String,
    private val isLiveStream: Boolean,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Clientes dedicados de streaming DLNA no iOS com followRedirects = false:
    // No Ktor Darwin (NSURLSession), redirects nativos NÃO atualizam resp.call.request.url
    // (quebrando 100% dos caminhos relativos em playlists HLS de TV Ao Vivo/Pluto TV e VOD HLS)
    // e removem headers customizados entre domínios. Seguimos redirects manualmente (até 10 saltos)
    // e desativamos NSURLCache para nunca servir playlists .m3u8 antigas em canais Ao Vivo.
    private val streamHttpClient by lazy {
        HttpClient(Darwin) {
            followRedirects = false
            expectSuccess = false
            engine {
                configureRequest {
                    setCachePolicy(NSURLRequestReloadIgnoringLocalCacheData)
                    setAllowsCellularAccess(true)
                }
                configureSession {
                    requestCachePolicy = NSURLRequestReloadIgnoringLocalCacheData
                    URLCache = null
                    timeoutIntervalForRequest = if (isLiveStream) 25.0 else 60.0
                    timeoutIntervalForResource = 60.0 * 60.0 * 12.0
                    allowsCellularAccess = true
                }
            }
            install(HttpTimeout) {
                requestTimeoutMillis = Long.MAX_VALUE
                connectTimeoutMillis = 15_000L
                socketTimeoutMillis = if (isLiveStream) 25_000L else 60_000L
            }
        }
    }
    private val metaHttpClient by lazy {
        HttpClient(Darwin) {
            followRedirects = false
            expectSuccess = false
            engine {
                configureRequest {
                    setCachePolicy(NSURLRequestReloadIgnoringLocalCacheData)
                    setAllowsCellularAccess(true)
                }
                configureSession {
                    requestCachePolicy = NSURLRequestReloadIgnoringLocalCacheData
                    URLCache = null
                    timeoutIntervalForRequest = 15.0
                    timeoutIntervalForResource = 30.0
                    allowsCellularAccess = true
                }
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 20_000L
                connectTimeoutMillis = 12_000L
                socketTimeoutMillis = 15_000L
            }
        }
    }
    private var acceptJob: Job? = null

    @Volatile
    private var running = true

    @Volatile
    var hasReceivedRequest: Boolean = false
        private set

    @Volatile
    var lastDiagnostic: String? = null
        private set

    fun start() {
        acceptJob = scope.launch {
            runCatching {
                memScoped {
                    val pfd = alloc<pollfd>()
                    pfd.fd = serverFd
                    pfd.events = POLLIN.convert()
                    while (running) {
                        val ready = poll(pfd.ptr, 1u, 250)
                        if (ready <= 0) continue
                        val clientFd = accept(serverFd, null, null)
                        if (clientFd < 0) continue
                        // Evita SIGPIPE fatal, desativa Nagle (TCP_NODELAY) e ativa keepalive na TV
                        configureClientSocket(clientFd)
                        scope.launch {
                            runCatching { handleClient(clientFd) }
                        }
                    }
                }
            }
        }
    }

    fun stop() {
        running = false
        acceptJob?.cancel()
        acceptJob = null
        scope.coroutineContext.cancelChildren()
        if (serverFd >= 0) {
            close(serverFd)
        }
        runCatching { streamHttpClient.close() }
        runCatching { metaHttpClient.close() }
    }

    private suspend fun handleClient(clientFd: Int) {
        hasReceivedRequest = true
        try {
            val rawRequest = readHttpRequest(clientFd)
            if (rawRequest.isBlank()) return
            val lines = rawRequest.split("\r\n")
            val requestLine = lines.firstOrNull().orEmpty()
            val parts = requestLine.split(' ')
            if (parts.size < 2) return
            val method = parts[0].uppercase()

            val reqHeaders = linkedMapOf<String, String>()
            for (line in lines.drop(1)) {
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) {
                    reqHeaders[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                }
            }

            val dlnaOp = if (isLiveStream) "00" else "01"
            val dlnaFeatures = "DLNA.ORG_OP=$dlnaOp;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"

            if (isLiveStream && method == "HEAD") {
                lastDiagnostic = "HEAD live -> 200 video/mp2t"
                writeLiveTsHeaders(clientFd, dlnaFeatures)
                return
            }

            if (isLiveStream) {
                handleLiveOrHlsAsTsStream(clientFd, dlnaFeatures)
                return
            }

            if (method == "HEAD") {
                handleVodHead(clientFd, dlnaFeatures)
                return
            }

            handleVodGet(clientFd, reqHeaders["range"], dlnaFeatures)
        } finally {
            close(clientFd)
        }
    }

    private fun writeLiveTsHeaders(clientFd: Int, dlnaFeatures: String): Boolean {
        val headerBlock = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: video/mp2t\r\n")
            append("Accept-Ranges: none\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("transferMode.dlna.org: Streaming\r\n")
            append("contentFeatures.dlna.org: $dlnaFeatures\r\n")
            append("Connection: close\r\n\r\n")
        }
        return writeAll(clientFd, headerBlock.encodeToByteArray())
    }

    private suspend fun handleVodHead(clientFd: Int, dlnaFeatures: String) {
        var safeMime = defaultContentType
        var totalLength: Long? = null
        var detectedHls = false
        var probedOk = false

        runCatching {
            executeStreamWithRedirects(
                client = metaHttpClient,
                initialUrl = defaultUrl,
                rangeHeader = "bytes=0-0",
                forceIdentityEncoding = true,
            ) { resp, finalUrl ->
                val code = resp.status.value
                val rawMime = resp.headers["Content-Type"]
                if (rawMime.orEmpty().contains("mpegurl", ignoreCase = true) ||
                    finalUrl.substringBefore('?').lowercase().endsWith(".m3u8")
                ) {
                    detectedHls = true
                    safeMime = "video/mp2t"
                    probedOk = true
                    lastDiagnostic = "HEAD probe -> HLS ($finalUrl)"
                    return@executeStreamWithRedirects
                }
                if (code in 200..299) {
                    totalLength = parseTotalSizeFromRange(resp.headers["Content-Range"])
                        ?: resp.contentLength()?.takeIf { it > 0L && code == 200 }
                    safeMime = normalizeVideoMime(rawMime, defaultContentType)
                    probedOk = true
                    lastDiagnostic = "HEAD probe -> origem HTTP $code ($safeMime)"
                } else {
                    lastDiagnostic = "HEAD probe range -> HTTP $code"
                }
            }
        }.onFailure { ex ->
            lastDiagnostic = "HEAD falha na origem: ${ex.message}"
        }

        // Fallback: algumas CDNs recusam Range: bytes=0-0 (416/405/403); tenta HEAD direto
        if (!probedOk && running) {
            runCatching {
                val headRes = executeMetaHeadWithRedirects(defaultUrl)
                if (headRes != null) {
                    val (resp, finalUrl) = headRes
                    val rawMime = resp.headers["Content-Type"]
                    if (rawMime.orEmpty().contains("mpegurl", ignoreCase = true) ||
                        finalUrl.substringBefore('?').lowercase().endsWith(".m3u8")
                    ) {
                        detectedHls = true
                        safeMime = "video/mp2t"
                    } else if (resp.status.value in 200..299) {
                        totalLength = resp.contentLength()?.takeIf { it > 0L }
                        safeMime = normalizeVideoMime(rawMime, defaultContentType)
                    }
                    lastDiagnostic = "HEAD fallback -> HTTP ${resp.status.value} ($safeMime)"
                }
            }
        }

        val effectiveFeatures = if (detectedHls) {
            "DLNA.ORG_OP=00;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
        } else {
            dlnaFeatures
        }
        val headerBlock = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: $safeMime\r\n")
            if (!detectedHls && totalLength != null && totalLength!! > 0L) {
                append("Content-Length: $totalLength\r\n")
            }
            append(if (detectedHls) "Accept-Ranges: none\r\n" else "Accept-Ranges: bytes\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("transferMode.dlna.org: Streaming\r\n")
            append("contentFeatures.dlna.org: $effectiveFeatures\r\n")
            append("Connection: close\r\n\r\n")
        }
        writeAll(clientFd, headerBlock.encodeToByteArray())
    }

    private suspend fun handleVodGet(clientFd: Int, rangeHeader: String?, dlnaFeatures: String) {
        var resumeUrl = defaultUrl
        var startOffset = 0L
        var endOffset = -1L
        var expectedLength = -1L
        var bytesSent = 0L
        var clientDisconnected = false
        var handledHls = false
        var headersSentToClient = false
        var lastErrorStatus = 502

        // Até 3 tentativas na conexão inicial de VOD (alinhado ao Android/Desktop),
        // evitando falhas aleatórias quando a CDN oscila ou redireciona.
        var initialAttempt = 0
        while (running && initialAttempt < 3 && !headersSentToClient && !handledHls && !clientDisconnected) {
            initialAttempt += 1
            var retryableFailure = false

            runCatching {
                executeStreamWithRedirects(
                    client = streamHttpClient,
                    initialUrl = defaultUrl,
                    rangeHeader = rangeHeader,
                    forceIdentityEncoding = true,
                ) { resp, finalUrl ->
                    val statusCode = resp.status.value
                    if (statusCode !in 200..299) {
                        lastErrorStatus = statusCode
                        lastDiagnostic = "GET origem retornou HTTP $statusCode (tentativa $initialAttempt)"
                        if (statusCode >= 500 || statusCode == 429 || statusCode == 408) {
                            retryableFailure = true
                            return@executeStreamWithRedirects
                        }
                        writeAll(clientFd, "HTTP/1.1 $statusCode Error\r\nConnection: close\r\n\r\n".encodeToByteArray())
                        clientDisconnected = true
                        return@executeStreamWithRedirects
                    }

                    val rawMime = resp.headers["Content-Type"]
                    resumeUrl = finalUrl
                    val channel = resp.bodyAsChannel()
                    val probeBuf = ByteArray(4096)
                    val probeRead = channel.readAvailable(probeBuf, 0, probeBuf.size).coerceAtLeast(0)
                    val maybeTextHls = rawMime.orEmpty().contains("mpegurl", ignoreCase = true) ||
                        finalUrl.substringBefore('?').lowercase().endsWith(".m3u8") ||
                        looksLikeM3u8Prefix(probeBuf, probeRead)
                    val probeText = if (maybeTextHls && probeRead > 0) {
                        probeBuf.decodeToString(0, probeRead).trimStart('\uFEFF', ' ', '\t', '\r', '\n')
                    } else {
                        ""
                    }
                    val isHls = probeText.startsWith("#EXTM3U") ||
                        rawMime.orEmpty().contains("mpegurl", ignoreCase = true) ||
                        finalUrl.substringBefore('?').lowercase().endsWith(".m3u8")

                    if (isHls) {
                        handledHls = true
                        lastDiagnostic = "GET VOD HLS -> video/mp2t"
                        val rest = readRemainingText(probeBuf, probeRead, channel)
                        streamHlsAsMpegTs(
                            initialText = rest,
                            initialUrl = finalUrl,
                            clientFd = clientFd,
                            dlnaFeatures = "DLNA.ORG_OP=00;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000",
                            normalizer = BestCineMpegTsTools.MpegTsNormalizer(),
                            alreadySentHeaders = false,
                        )
                        return@executeStreamWithRedirects
                    }

                    val safeMime = normalizeVideoMime(rawMime, defaultContentType)
                    val contentLength = resp.contentLength() ?: -1L
                    val contentRange = resp.headers["Content-Range"]
                    val parsedRange = parseContentRangeBounds(contentRange)
                    if (parsedRange != null) {
                        startOffset = parsedRange.first
                        endOffset = parsedRange.second
                        expectedLength = (endOffset - startOffset + 1L).coerceAtLeast(0L)
                    } else {
                        val reqBounds = parseRequestRangeBounds(rangeHeader)
                        startOffset = reqBounds?.first ?: 0L
                        endOffset = reqBounds?.second ?: if (contentLength > 0L) startOffset + contentLength - 1L else -1L
                        expectedLength = contentLength
                    }

                    val statusMsg = if (statusCode == 206) "Partial Content" else "OK"
                    lastDiagnostic = "GET -> HTTP $statusCode ($safeMime)"

                    val headerBlock = buildString {
                        append("HTTP/1.1 $statusCode $statusMsg\r\n")
                        append("Content-Type: $safeMime\r\n")
                        if (contentLength >= 0L) append("Content-Length: $contentLength\r\n")
                        if (!contentRange.isNullOrBlank()) append("Content-Range: $contentRange\r\n")
                        append("Accept-Ranges: bytes\r\n")
                        append("Access-Control-Allow-Origin: *\r\n")
                        append("transferMode.dlna.org: Streaming\r\n")
                        append("contentFeatures.dlna.org: $dlnaFeatures\r\n")
                        append("Connection: close\r\n\r\n")
                    }
                    if (!writeAll(clientFd, headerBlock.encodeToByteArray())) {
                        clientDisconnected = true
                        return@executeStreamWithRedirects
                    }
                    headersSentToClient = true
                    if (probeRead > 0) {
                        if (!writeAll(clientFd, probeBuf, probeRead)) {
                            clientDisconnected = true
                            return@executeStreamWithRedirects
                        }
                        bytesSent += probeRead
                    }

                    val buffer = ByteArray(32 * 1024)
                    while (running) {
                        val read = channel.readAvailable(buffer, 0, buffer.size)
                        if (read <= 0) break
                        if (!writeAll(clientFd, buffer, read)) {
                            clientDisconnected = true
                            break
                        }
                        bytesSent += read
                    }
                }
            }.onFailure { ex ->
                lastDiagnostic = "GET falha na origem (tentativa $initialAttempt): ${ex.message}"
                retryableFailure = true
            }

            if (retryableFailure && !headersSentToClient && !clientDisconnected && initialAttempt < 3) {
                delay(450L * initialAttempt)
            }
        }

        if (handledHls || clientDisconnected) return
        if (!headersSentToClient) {
            writeAll(clientFd, "HTTP/1.1 $lastErrorStatus Bad Gateway\r\nConnection: close\r\n\r\n".encodeToByteArray())
            return
        }

        // Reconexão transparente por Range no iOS/iPadOS caso a CDN encerre a conexão no meio do vídeo
        var resumeRetries = 0
        while (running && !clientDisconnected && expectedLength > 0L && bytesSent < expectedLength && resumeRetries < 16) {
            val nextOffset = startOffset + bytesSent
            val resumeRange = if (endOffset >= nextOffset) {
                "bytes=$nextOffset-$endOffset"
            } else {
                "bytes=$nextOffset-"
            }
            val targetUrl = if (resumeRetries % 2 == 0) resumeUrl else defaultUrl
            var chunkBytes = 0L

            runCatching {
                executeStreamWithRedirects(
                    client = streamHttpClient,
                    initialUrl = targetUrl,
                    rangeHeader = resumeRange,
                    forceIdentityEncoding = true,
                ) { resp, finalUrl ->
                    val validResume = (resp.status.value == 206) || (resp.status.value == 200 && nextOffset == 0L)
                    if (!validResume) return@executeStreamWithRedirects
                    resumeUrl = finalUrl
                    val channel = resp.bodyAsChannel()
                    val buffer = ByteArray(32 * 1024)
                    while (running && bytesSent < expectedLength) {
                        val remaining = expectedLength - bytesSent
                        val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                        val read = channel.readAvailable(buffer, 0, toRead)
                        if (read <= 0) break
                        if (!writeAll(clientFd, buffer, read)) {
                            clientDisconnected = true
                            break
                        }
                        bytesSent += read
                        chunkBytes += read
                    }
                }
            }

            if (clientDisconnected) return
            if (chunkBytes >= 64 * 1024L) {
                resumeRetries = 0
            } else {
                resumeRetries += 1
                delay(650L * minOf(resumeRetries, 5))
            }
        }
    }

    /**
     * Mesma engine validada no Android e Desktop (para iPhone e iPad):
     * - Redirects resolvidos manualmente para preservar a URL real final (corrige caminhos relativos HLS);
     * - TS puro: reconexão contínua + keepalive NULL + normalizador PTS/PCR/CC compartilhado;
     * - HLS/Pluto TV: loop externo resiliente + AES-128 + muxer vídeo+áudio separado + renovação de tokens + keepalive NULL!
     */
    private suspend fun handleLiveOrHlsAsTsStream(clientFd: Int, dlnaFeatures: String) {
        val normalizer = BestCineMpegTsTools.MpegTsNormalizer()
        var headersSent = false
        var reconnects = 0
        var lastErrorStatus = 502

        while (running && reconnects < 30) {
            var clientDisconnected = false
            var wasHls = false
            var streamedBytes = 0L

            runCatching {
                executeStreamWithRedirects(
                    client = streamHttpClient,
                    initialUrl = defaultUrl,
                    rangeHeader = null,
                    forceIdentityEncoding = false,
                ) { resp, finalUrl ->
                    val statusCode = resp.status.value
                    if (statusCode !in 200..299) {
                        lastErrorStatus = statusCode
                        lastDiagnostic = "Live origem retornou HTTP $statusCode"
                        if (!headersSent && reconnects >= 4) {
                            writeAll(clientFd, "HTTP/1.1 $statusCode Bad Gateway\r\nConnection: close\r\n\r\n".encodeToByteArray())
                            clientDisconnected = true
                        }
                        return@executeStreamWithRedirects
                    }

                    val upstreamMime = resp.headers["Content-Type"]?.lowercase().orEmpty()
                    val channel = resp.bodyAsChannel()
                    val probeBuf = ByteArray(4096)
                    val probeRead = channel.readAvailable(probeBuf, 0, probeBuf.size).coerceAtLeast(0)
                    val maybeHls = upstreamMime.contains("mpegurl") ||
                        finalUrl.substringBefore('?').lowercase().endsWith(".m3u8") ||
                        looksLikeM3u8Prefix(probeBuf, probeRead)
                    val probeText = if (maybeHls && probeRead > 0) {
                        probeBuf.decodeToString(0, probeRead).trimStart('\uFEFF', ' ', '\t', '\r', '\n')
                    } else {
                        ""
                    }
                    val isHls = probeText.startsWith("#EXTM3U") ||
                        upstreamMime.contains("mpegurl") ||
                        finalUrl.substringBefore('?').lowercase().endsWith(".m3u8")

                    if (isHls) {
                        wasHls = true
                        lastDiagnostic = "Live HLS -> video/mp2t (muxed+normalizado)"
                        val initialPlaylist = readRemainingText(probeBuf, probeRead, channel)
                        val outcome = streamHlsAsMpegTs(
                            initialText = initialPlaylist,
                            initialUrl = finalUrl,
                            clientFd = clientFd,
                            dlnaFeatures = dlnaFeatures,
                            normalizer = normalizer,
                            alreadySentHeaders = headersSent,
                        )
                        headersSent = headersSent || outcome.headersSent
                        clientDisconnected = outcome.clientDisconnected
                        if (outcome.streamedAnySegment) {
                            streamedBytes = 512 * 1024L
                        }
                        return@executeStreamWithRedirects
                    }

                    lastDiagnostic = "Live TS -> video/mp2t"
                    if (!headersSent) {
                        if (!writeLiveTsHeaders(clientFd, dlnaFeatures)) {
                            clientDisconnected = true
                            return@executeStreamWithRedirects
                        }
                        headersSent = true
                    } else {
                        normalizer.markDiscontinuity()
                    }

                    if (probeRead > 0) {
                        val normalized = normalizer.normalizeBytes(probeBuf, probeRead)
                        if (normalized.isNotEmpty() && !writeAll(clientFd, normalized)) {
                            clientDisconnected = true
                            return@executeStreamWithRedirects
                        }
                        streamedBytes += probeRead
                    }

                    val buffer = ByteArray(16 * 1024)
                    while (running) {
                        val read = channel.readAvailable(buffer, 0, buffer.size)
                        if (read <= 0) break
                        val normalized = normalizer.normalizeBytes(buffer, read)
                        if (normalized.isNotEmpty() && !writeAll(clientFd, normalized)) {
                            clientDisconnected = true
                            return@executeStreamWithRedirects
                        }
                        streamedBytes += read
                    }
                }
            }.onFailure { ex ->
                lastDiagnostic = "Live falha na origem: ${ex.message}"
            }

            if (clientDisconnected) return
            if (!headersSent && reconnects >= 4) {
                writeAll(clientFd, "HTTP/1.1 $lastErrorStatus Bad Gateway\r\nConnection: close\r\n\r\n".encodeToByteArray())
                return
            }
            if (headersSent && !writeAll(clientFd, BestCineMpegTsTools.NULL_PACKET)) return
            if (streamedBytes > 128 * 1024L) {
                reconnects = 0
                if (wasHls) normalizer.markDiscontinuity()
            } else {
                reconnects += 1
            }
            delay(if (wasHls) 850L else 550L)
        }
    }

    private data class IosSelectedVariant(
        val videoPlaylistUrl: String,
        val externalAudioPlaylistUrl: String?,
    )

    private data class IosHlsOutcome(
        val clientDisconnected: Boolean,
        val headersSent: Boolean,
        val streamedAnySegment: Boolean,
    )

    private suspend fun streamHlsAsMpegTs(
        initialText: String,
        initialUrl: String,
        clientFd: Int,
        dlnaFeatures: String,
        normalizer: BestCineMpegTsTools.MpegTsNormalizer,
        alreadySentHeaders: Boolean,
    ): IosHlsOutcome {
        var videoMediaUrl = initialUrl
        var audioMediaUrl: String? = null
        var videoPlaylistText = initialText
        var headersSent = alreadySentHeaders
        var streamedAny = false

        if (videoPlaylistText.contains("#EXT-X-STREAM-INF")) {
            val selected = pickBestVariantWithAudio(videoPlaylistText, videoMediaUrl) ?: run {
                if (!headersSent) {
                    writeAll(clientFd, "HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n".encodeToByteArray())
                }
                return IosHlsOutcome(false, headersSent, false)
            }
            val fetchedVariant = fetchTextWithRedirectRetry(selected.videoPlaylistUrl, attempts = 3) ?: run {
                if (!headersSent) {
                    writeAll(clientFd, "HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n".encodeToByteArray())
                }
                return IosHlsOutcome(false, headersSent, false)
            }
            videoMediaUrl = fetchedVariant.first
            videoPlaylistText = fetchedVariant.second
            audioMediaUrl = selected.externalAudioPlaylistUrl
        }

        if (headersSent) {
            normalizer.markDiscontinuity()
        }

        val sentKeys = LinkedHashSet<String>()
        val keyCache = HashMap<String, ByteArray>()
        var lastStreamedSeq = -1L
        var isFirstPoll = true
        var consecutiveEmptyPolls = 0
        var staleNoNewSegs = 0

        while (running) {
            val pollStartMs = currentTimeMillisIos()
            val parsedVideo = parseHlsMediaPlaylist(videoPlaylistText, videoMediaUrl)
            val segments = parsedVideo.segments

            if (segments.isEmpty()) {
                consecutiveEmptyPolls += 1
                if (headersSent && !writeAll(clientFd, BestCineMpegTsTools.NULL_PACKET)) {
                    return IosHlsOutcome(true, headersSent, streamedAny)
                }
                if (consecutiveEmptyPolls >= 16) break
                delay(850L)
                val refreshed = refreshVideoAndAudioUrls(videoMediaUrl, audioMediaUrl, forceMaster = consecutiveEmptyPolls >= 3)
                if (refreshed != null) {
                    videoMediaUrl = refreshed.first
                    audioMediaUrl = refreshed.second
                    videoPlaylistText = refreshed.third
                }
                continue
            }
            consecutiveEmptyPolls = 0

            // Envia HTTP 200 assim que confirmamos segmentos válidos: evita timeout em Smart TVs
            // durante o download/mux do primeiro segmento (especialmente canais de áudio separado como Pluto TV).
            if (!headersSent) {
                if (!writeLiveTsHeaders(clientFd, dlnaFeatures)) {
                    return IosHlsOutcome(true, false, streamedAny)
                }
                headersSent = true
            }

            if (lastStreamedSeq >= 0L && !parsedVideo.isVod) {
                val firstSeq = segments.first().sequence
                val lastSeq = segments.last().sequence
                if (lastSeq < lastStreamedSeq - 30L || firstSeq > lastStreamedSeq + 60L) {
                    sentKeys.clear()
                    normalizer.markDiscontinuity()
                    isFirstPoll = true
                }
            }

            var audioBySeq: Map<Long, IosHlsSegment> = emptyMap()
            val curAudioUrl = audioMediaUrl
            if (curAudioUrl != null) {
                var activeAudioUrl = curAudioUrl
                var audioFetched = fetchTextWithRedirectRetry(activeAudioUrl, attempts = 2)
                if (audioFetched == null) {
                    val refreshed = refreshVideoAndAudioUrls(videoMediaUrl, activeAudioUrl, forceMaster = true)
                    val newAudioUrl = refreshed?.second
                    if (refreshed != null && newAudioUrl != null) {
                        videoMediaUrl = refreshed.first
                        audioMediaUrl = newAudioUrl
                        activeAudioUrl = newAudioUrl
                        videoPlaylistText = refreshed.third
                        audioFetched = fetchTextWithRedirectRetry(newAudioUrl, attempts = 2)
                    }
                }
                if (audioFetched != null) {
                    activeAudioUrl = audioFetched.first
                    audioMediaUrl = activeAudioUrl
                    audioBySeq = parseHlsMediaPlaylist(audioFetched.second, activeAudioUrl).segments.associateBy { it.sequence }
                }
            }

            val toStream = if (isFirstPoll && !parsedVideo.isVod && segments.size > 3) {
                segments.dropLast(2).forEach { sentKeys.add("${it.sequence}|${it.url.substringBefore('?')}") }
                segments.takeLast(2)
            } else {
                segments.filter { "${it.sequence}|${it.url.substringBefore('?')}" !in sentKeys }
            }
            isFirstPoll = false

            if (toStream.isEmpty()) {
                staleNoNewSegs += 1
                if (headersSent && !writeAll(clientFd, BestCineMpegTsTools.NULL_PACKET)) {
                    return IosHlsOutcome(true, headersSent, streamedAny)
                }
            } else {
                staleNoNewSegs = 0
            }

            var consecutiveSegFailures = 0
            for (seg in toStream) {
                if (!running) return IosHlsOutcome(true, headersSent, streamedAny)
                if (seg.precededByDiscontinuity) {
                    normalizer.markDiscontinuity()
                }

                val matchingAudioSeg = if (audioMediaUrl != null) {
                    audioBySeq[seg.sequence] ?: audioBySeq.values.firstOrNull()
                } else {
                    null
                }

                val streamed = if (matchingAudioSeg != null) {
                    streamMuxedDualSegment(
                        videoSeg = seg,
                        audioSeg = matchingAudioSeg,
                        keyCache = keyCache,
                        normalizer = normalizer,
                        clientFd = clientFd,
                        sendHeadersFirst = !headersSent,
                        dlnaFeatures = dlnaFeatures,
                    )
                } else {
                    streamSingleSegmentChunked(
                        seg = seg,
                        keyCache = keyCache,
                        normalizer = normalizer,
                        clientFd = clientFd,
                        sendHeadersFirst = !headersSent,
                        dlnaFeatures = dlnaFeatures,
                    )
                }

                if (streamed == IosSegResult.CLIENT_DISCONNECTED) {
                    return IosHlsOutcome(true, headersSent, streamedAny)
                }
                if (streamed == IosSegResult.OK) {
                    headersSent = true
                    streamedAny = true
                    consecutiveSegFailures = 0
                    lastStreamedSeq = seg.sequence
                    sentKeys.add("${seg.sequence}|${seg.url.substringBefore('?')}")
                    if (sentKeys.size > 2048) {
                        val it = sentKeys.iterator()
                        if (it.hasNext()) {
                            it.next()
                            it.remove()
                        }
                    }
                } else {
                    consecutiveSegFailures += 1
                    if (headersSent && !writeAll(clientFd, BestCineMpegTsTools.NULL_PACKET)) {
                        return IosHlsOutcome(true, headersSent, streamedAny)
                    }
                    if (consecutiveSegFailures >= 2) break
                }
            }

            if (parsedVideo.isVod && consecutiveSegFailures == 0) {
                return IosHlsOutcome(true, headersSent, streamedAny)
            }

            if (consecutiveSegFailures == 0) {
                val targetPollMs = (parsedVideo.targetDurationSec.coerceIn(2, 6)) * 500L
                val elapsedMs = currentTimeMillisIos() - pollStartMs
                val waitMs = (targetPollMs - elapsedMs).coerceAtLeast(300L)
                delay(waitMs)
            }

            var refreshed: Triple<String, String?, String>? = null
            var pollTry = 0
            val forceMasterNow = staleNoNewSegs >= 5 || consecutiveSegFailures >= 2
            while (running && pollTry < 20 && refreshed == null) {
                refreshed = refreshVideoAndAudioUrls(
                    currentVideoUrl = videoMediaUrl,
                    currentAudioUrl = audioMediaUrl,
                    forceMaster = forceMasterNow || pollTry >= 2,
                )
                if (refreshed == null) {
                    pollTry += 1
                    if (headersSent && !writeAll(clientFd, BestCineMpegTsTools.NULL_PACKET)) {
                        return IosHlsOutcome(true, headersSent, streamedAny)
                    }
                    delay(900L)
                }
            }
            if (refreshed == null) break
            videoMediaUrl = refreshed.first
            audioMediaUrl = refreshed.second
            videoPlaylistText = refreshed.third
            if (forceMasterNow) {
                staleNoNewSegs = 0
            }
        }
        return IosHlsOutcome(false, headersSent, streamedAny)
    }

    private enum class IosSegResult { OK, UPSTREAM_FAILED, CLIENT_DISCONNECTED }

    private suspend fun fetchSegmentDecryptedBytes(
        seg: IosHlsSegment,
        keyCache: HashMap<String, ByteArray>,
    ): ByteArray? {
        val raw = fetchBytesWithRetry(seg.url, attempts = 2) ?: return null
        val keyUri = seg.keyUri ?: return raw
        val keyBytes = keyCache[keyUri] ?: (fetchBytesWithRetry(keyUri, attempts = 2) ?: ByteArray(0)).also {
            if (it.size == 16) keyCache[keyUri] = it
        }
        return if (keyBytes.size == 16) {
            BestCineMpegTsTools.decryptAes128CbcPkcs7(raw, keyBytes, seg.iv)
        } else {
            raw
        }
    }

    private suspend fun streamMuxedDualSegment(
        videoSeg: IosHlsSegment,
        audioSeg: IosHlsSegment,
        keyCache: HashMap<String, ByteArray>,
        normalizer: BestCineMpegTsTools.MpegTsNormalizer,
        clientFd: Int,
        sendHeadersFirst: Boolean,
        dlnaFeatures: String,
    ): IosSegResult = coroutineScope {
        val videoDeferred = async { fetchSegmentDecryptedBytes(videoSeg, keyCache) }
        val audioDeferred = async { fetchSegmentDecryptedBytes(audioSeg, keyCache) }
        val videoBytes = videoDeferred.await() ?: return@coroutineScope IosSegResult.UPSTREAM_FAILED
        val audioBytes = audioDeferred.await()

        val combined = if (audioBytes != null && audioBytes.isNotEmpty()) {
            BestCineMpegTsTools.muxVideoAndAudioSegments(videoBytes, audioBytes)
        } else {
            videoBytes
        }

        if (sendHeadersFirst && !writeLiveTsHeaders(clientFd, dlnaFeatures)) {
            return@coroutineScope IosSegResult.CLIENT_DISCONNECTED
        }

        var off = 0
        val step = 16 * 1024
        while (running && off < combined.size) {
            val len = minOf(step, combined.size - off)
            val slice = if (off == 0 && len == combined.size) combined else combined.copyOfRange(off, off + len)
            val normalized = normalizer.normalizeBytes(slice, len)
            if (normalized.isNotEmpty() && !writeAll(clientFd, normalized)) {
                return@coroutineScope IosSegResult.CLIENT_DISCONNECTED
            }
            off += len
        }
        IosSegResult.OK
    }

    private suspend fun streamSingleSegmentChunked(
        seg: IosHlsSegment,
        keyCache: HashMap<String, ByteArray>,
        normalizer: BestCineMpegTsTools.MpegTsNormalizer,
        clientFd: Int,
        sendHeadersFirst: Boolean,
        dlnaFeatures: String,
    ): IosSegResult {
        // Segmentos AES-128 precisam ser decifrados antes de normalizar os pacotes TS de 188 bytes.
        if (seg.keyUri != null) {
            val decrypted = fetchSegmentDecryptedBytes(seg, keyCache) ?: return IosSegResult.UPSTREAM_FAILED
            if (sendHeadersFirst && !writeLiveTsHeaders(clientFd, dlnaFeatures)) {
                return IosSegResult.CLIENT_DISCONNECTED
            }
            var off = 0
            val step = 16 * 1024
            while (running && off < decrypted.size) {
                val len = minOf(step, decrypted.size - off)
                val slice = if (off == 0 && len == decrypted.size) decrypted else decrypted.copyOfRange(off, off + len)
                val normalized = normalizer.normalizeBytes(slice, len)
                if (normalized.isNotEmpty() && !writeAll(clientFd, normalized)) {
                    return IosSegResult.CLIENT_DISCONNECTED
                }
                off += len
            }
            return IosSegResult.OK
        }

        var result = IosSegResult.UPSTREAM_FAILED
        repeat(2) { attempt ->
            if (!running) return IosSegResult.CLIENT_DISCONNECTED
            runCatching {
                executeStreamWithRedirects(
                    client = streamHttpClient,
                    initialUrl = seg.url,
                    rangeHeader = null,
                    forceIdentityEncoding = false,
                ) { resp, _ ->
                    if (!resp.status.isSuccess()) return@executeStreamWithRedirects
                    val channel = resp.bodyAsChannel()
                    var headerWritten = !sendHeadersFirst
                    var anyBytesWritten = false
                    val buf = ByteArray(16 * 1024)
                    while (running) {
                        val read = channel.readAvailable(buf, 0, buf.size)
                        if (read <= 0) break
                        if (!headerWritten) {
                            if (!writeLiveTsHeaders(clientFd, dlnaFeatures)) {
                                result = IosSegResult.CLIENT_DISCONNECTED
                                return@executeStreamWithRedirects
                            }
                            headerWritten = true
                        }
                        val normalized = normalizer.normalizeBytes(buf, read)
                        if (normalized.isNotEmpty() && !writeAll(clientFd, normalized)) {
                            result = IosSegResult.CLIENT_DISCONNECTED
                            return@executeStreamWithRedirects
                        }
                        anyBytesWritten = true
                    }
                    if (anyBytesWritten) result = IosSegResult.OK
                }
            }
            if (result != IosSegResult.UPSTREAM_FAILED) return result
            if (attempt == 0 && running) delay(350L)
        }
        return result
    }

    private suspend fun refreshVideoAndAudioUrls(
        currentVideoUrl: String,
        currentAudioUrl: String?,
        forceMaster: Boolean = false,
    ): Triple<String, String?, String>? {
        if (!forceMaster) {
            fetchTextWithRedirect(currentVideoUrl)?.let { (resolvedVideoUrl, text) ->
                if (text.contains("#EXTM3U")) return Triple(resolvedVideoUrl, currentAudioUrl, text)
            }
        }
        return runCatching {
            val master = fetchTextWithRedirect(defaultUrl) ?: return@runCatching null
            val (resolvedUrl, text) = master
            if (!text.contains("#EXTM3U")) return@runCatching null
            if (text.contains("#EXT-X-STREAM-INF")) {
                val selected = pickBestVariantWithAudio(text, resolvedUrl) ?: return@runCatching null
                val variant = fetchTextWithRedirect(selected.videoPlaylistUrl) ?: return@runCatching null
                Triple(variant.first, selected.externalAudioPlaylistUrl, variant.second)
            } else {
                Triple(resolvedUrl, null, text)
            }
        }.getOrNull()
    }

    private fun parseContentRangeBounds(contentRange: String?): Pair<Long, Long>? {
        if (contentRange.isNullOrBlank()) return null
        val match = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""", RegexOption.IGNORE_CASE).find(contentRange) ?: return null
        val s = match.groupValues[1].toLongOrNull() ?: return null
        val e = match.groupValues[2].toLongOrNull() ?: return null
        return s to e
    }

    private fun parseRequestRangeBounds(rangeHeader: String?): Pair<Long, Long>? {
        if (rangeHeader.isNullOrBlank()) return null
        val match = Regex("""bytes=(\d+)-(\d*)""", RegexOption.IGNORE_CASE).find(rangeHeader) ?: return null
        val s = match.groupValues[1].toLongOrNull() ?: return null
        val e = match.groupValues[2].toLongOrNull() ?: -1L
        return s to e
    }

    private fun isRedirectStatus(code: Int): Boolean =
        code == 301 || code == 302 || code == 303 || code == 307 || code == 308

    private fun extractUrlHost(url: String): String {
        val afterScheme = url.substringAfter("://", "")
        return afterScheme.substringBefore('/').substringBefore('?').lowercase()
    }

    private fun io.ktor.client.request.HttpRequestBuilder.applyUpstreamHeaders(
        targetUrl: String,
        rangeHeader: String?,
        forceIdentityEncoding: Boolean = false,
    ) {
        val sameOriginAsDefault = extractUrlHost(targetUrl) == extractUrlHost(defaultUrl)
        defaultHeaders.forEach { (k, v) ->
            if (k.equals("host", ignoreCase = true) || k.equals("range", ignoreCase = true)) return@forEach
            if (!sameOriginAsDefault &&
                (k.equals("authorization", ignoreCase = true) || k.startsWith("x-bestcine-", ignoreCase = true))
            ) {
                return@forEach
            }
            header(k, v)
        }
        if (!rangeHeader.isNullOrBlank()) {
            header("Range", rangeHeader)
        }
        if (forceIdentityEncoding && defaultHeaders.keys.none { it.equals("Accept-Encoding", ignoreCase = true) }) {
            header("Accept-Encoding", "identity")
        }
        if (defaultHeaders.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            header("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15")
        }
    }

    private suspend fun executeStreamWithRedirects(
        client: HttpClient,
        initialUrl: String,
        rangeHeader: String?,
        forceIdentityEncoding: Boolean,
        maxRedirects: Int = 10,
        onFinalResponse: suspend (HttpResponse, String) -> Unit,
    ) {
        var currentUrl = initialUrl
        for (hop in 0..maxRedirects) {
            var nextUrl: String? = null
            client.prepareGet(currentUrl) {
                applyUpstreamHeaders(
                    targetUrl = currentUrl,
                    rangeHeader = rangeHeader,
                    forceIdentityEncoding = forceIdentityEncoding,
                )
            }.execute { resp ->
                val code = resp.status.value
                val loc = resp.headers["Location"]?.trim()
                if (isRedirectStatus(code) && !loc.isNullOrBlank() && hop < maxRedirects) {
                    nextUrl = resolveRelativeHttpUrl(currentUrl, loc)
                } else {
                    onFinalResponse(resp, currentUrl)
                }
            }
            val target = nextUrl ?: return
            currentUrl = target
        }
    }

    private suspend fun executeMetaHeadWithRedirects(
        initialUrl: String,
        maxRedirects: Int = 10,
    ): Pair<HttpResponse, String>? {
        var currentUrl = initialUrl
        for (hop in 0..maxRedirects) {
            val resp = metaHttpClient.head(currentUrl) {
                applyUpstreamHeaders(
                    targetUrl = currentUrl,
                    rangeHeader = null,
                    forceIdentityEncoding = true,
                )
            }
            val code = resp.status.value
            val loc = resp.headers["Location"]?.trim()
            if (isRedirectStatus(code) && !loc.isNullOrBlank() && hop < maxRedirects) {
                currentUrl = resolveRelativeHttpUrl(currentUrl, loc) ?: return resp to currentUrl
                continue
            }
            return resp to currentUrl
        }
        return null
    }

    private suspend fun fetchTextWithRedirect(url: String, maxRedirects: Int = 10): Pair<String, String>? = runCatching {
        var currentUrl = url
        for (hop in 0..maxRedirects) {
            val resp = metaHttpClient.get(currentUrl) {
                applyUpstreamHeaders(targetUrl = currentUrl, rangeHeader = null)
            }
            val code = resp.status.value
            val loc = resp.headers["Location"]?.trim()
            if (isRedirectStatus(code) && !loc.isNullOrBlank() && hop < maxRedirects) {
                currentUrl = resolveRelativeHttpUrl(currentUrl, loc) ?: return@runCatching null
                continue
            }
            if (!resp.status.isSuccess()) return@runCatching null
            return@runCatching currentUrl to resp.bodyAsText()
        }
        null
    }.getOrNull()

    private suspend fun fetchTextWithRedirectRetry(url: String, attempts: Int): Pair<String, String>? {
        repeat(attempts) { idx ->
            fetchTextWithRedirect(url)?.let { return it }
            if (idx + 1 < attempts && running) delay(350L)
        }
        return null
    }

    private suspend fun fetchBytesWithRetry(url: String, attempts: Int): ByteArray? {
        repeat(attempts) { idx ->
            runCatching {
                var currentUrl = url
                for (hop in 0..8) {
                    val resp = metaHttpClient.get(currentUrl) {
                        applyUpstreamHeaders(targetUrl = currentUrl, rangeHeader = null)
                    }
                    val code = resp.status.value
                    val loc = resp.headers["Location"]?.trim()
                    if (isRedirectStatus(code) && !loc.isNullOrBlank() && hop < 8) {
                        currentUrl = resolveRelativeHttpUrl(currentUrl, loc) ?: return@runCatching null
                        continue
                    }
                    return@runCatching if (resp.status.isSuccess()) resp.bodyAsBytes() else null
                }
                null
            }.getOrNull()?.let { return it }
            if (idx + 1 < attempts && running) delay(350L)
        }
        return null
    }

    private fun looksLikeM3u8Prefix(buf: ByteArray, len: Int): Boolean {
        if (len <= 0) return false
        var i = 0
        if (len >= 3 && buf[0] == 0xEF.toByte() && buf[1] == 0xBB.toByte() && buf[2] == 0xBF.toByte()) {
            i = 3
        }
        while (i < len && (buf[i] == 0x20.toByte() || buf[i] == 0x09.toByte() || buf[i] == 0x0D.toByte() || buf[i] == 0x0A.toByte())) {
            i += 1
        }
        return i < len && buf[i] == '#'.code.toByte()
    }

    private suspend fun readRemainingText(
        firstChunk: ByteArray,
        firstLen: Int,
        channel: io.ktor.utils.io.ByteReadChannel,
    ): String {
        val maxBytes = 512 * 1024
        var total = firstLen.coerceAtMost(maxBytes)
        var acc = ByteArray((total + 16 * 1024).coerceAtLeast(16 * 1024))
        if (total > 0) {
            firstChunk.copyInto(acc, destinationOffset = 0, startIndex = 0, endIndex = total)
        }
        val buf = ByteArray(16 * 1024)
        while (running && total < maxBytes) {
            val n = channel.readAvailable(buf, 0, minOf(buf.size, maxBytes - total))
            if (n <= 0) break
            if (total + n > acc.size) {
                acc = acc.copyOf(maxOf(acc.size * 2, total + n))
            }
            buf.copyInto(acc, destinationOffset = total, startIndex = 0, endIndex = n)
            total += n
        }
        return acc.decodeToString(0, total)
    }

    private fun normalizeVideoMime(raw: String?, fallback: String): String {
        val clean = raw?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        return if (clean.startsWith("video/")) clean else fallback
    }

    private fun parseTotalSizeFromRange(contentRange: String?): Long? {
        if (contentRange.isNullOrBlank()) return null
        return contentRange.substringAfter('/', "").trim().toLongOrNull()
    }

    private data class IosHlsSegment(
        val url: String,
        val sequence: Long,
        val keyUri: String?,
        val iv: ByteArray,
        val precededByDiscontinuity: Boolean,
    )

    private data class IosHlsPlaylist(
        val segments: List<IosHlsSegment>,
        val targetDurationSec: Int,
        val isVod: Boolean,
    )

    private fun pickBestVariantWithAudio(masterText: String, baseUrl: String): IosSelectedVariant? {
        val lines = masterText.lines().map { it.trim() }.filter { it.isNotEmpty() }

        val audioGroupBest = HashMap<String, Pair<Int, String>>()
        for (line in lines) {
            if (line.startsWith("#EXT-X-MEDIA:") &&
                line.contains("TYPE=AUDIO", ignoreCase = true) &&
                line.contains("URI=\"", ignoreCase = true)
            ) {
                val groupId = Regex("""GROUP-ID="([^"]+)"""").find(line)?.groupValues?.getOrNull(1) ?: continue
                val uriRel = Regex("""URI="([^"]+)"""").find(line)?.groupValues?.getOrNull(1) ?: continue
                val resolved = resolveRelativeHttpUrl(baseUrl, uriRel) ?: continue
                var score = 0
                if (line.contains("LANGUAGE=\"pt", ignoreCase = true) || line.contains("LANGUAGE=\"por", ignoreCase = true)) score += 20
                if (line.contains("DEFAULT=YES", ignoreCase = true)) score += 10
                if (line.contains("AUTOSELECT=YES", ignoreCase = true)) score += 5
                val prev = audioGroupBest[groupId]
                if (prev == null || score > prev.first) {
                    audioGroupBest[groupId] = score to resolved
                }
            }
        }

        var bestMuxed: Pair<Long, String>? = null
        var bestSplitUnderCap: Pair<Long, IosSelectedVariant>? = null
        var bestSplitAny: Pair<Long, IosSelectedVariant>? = null

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val bw = Regex("""BANDWIDTH=(\d+)""").find(line)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
                val audioGroup = Regex("""AUDIO="([^"]+)"""").find(line)?.groupValues?.getOrNull(1)
                val externalAudioUrl = audioGroup?.let { audioGroupBest[it]?.second }
                val nextUri = lines.drop(i + 1).firstOrNull { !it.startsWith("#") }
                val resolved = nextUri?.let { resolveRelativeHttpUrl(baseUrl, it) }
                if (resolved != null) {
                    if (externalAudioUrl == null) {
                        if (bestMuxed == null || bw >= bestMuxed.first) {
                            bestMuxed = bw to resolved
                        }
                    } else {
                        val candidate = IosSelectedVariant(resolved, externalAudioUrl)
                        if (bestSplitAny == null || bw >= bestSplitAny.first) {
                            bestSplitAny = bw to candidate
                        }
                        if (bw in 500_000L..3_800_000L && (bestSplitUnderCap == null || bw >= bestSplitUnderCap.first)) {
                            bestSplitUnderCap = bw to candidate
                        }
                    }
                }
            }
            i += 1
        }

        return bestMuxed?.let { IosSelectedVariant(it.second, null) }
            ?: bestSplitUnderCap?.second
            ?: bestSplitAny?.second
    }

    private fun parseHlsMediaPlaylist(text: String, baseUrl: String): IosHlsPlaylist {
        val segments = mutableListOf<IosHlsSegment>()
        var targetDuration = 4
        var mediaSeq = 0L
        var isVod = false
        var currentKeyUri: String? = null
        var currentKeyIv: ByteArray? = null
        var nextDiscontinuity = false

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXT-X-TARGETDURATION:") -> {
                    targetDuration = line.substringAfter(':').trim().toDoubleOrNull()?.toInt() ?: 4
                }
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> {
                    mediaSeq = line.substringAfter(':').trim().toLongOrNull() ?: 0L
                }
                line.startsWith("#EXT-X-DISCONTINUITY") && !line.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE") -> {
                    nextDiscontinuity = true
                }
                line.startsWith("#EXT-X-ENDLIST") -> isVod = true
                line.startsWith("#EXT-X-KEY:") -> {
                    if (line.contains("METHOD=AES-128", ignoreCase = true)) {
                        val uriRel = Regex("""URI="([^"]+)"""").find(line)?.groupValues?.getOrNull(1)
                        currentKeyUri = uriRel?.let { resolveRelativeHttpUrl(baseUrl, it) }
                        val ivHex = Regex("""IV=0x([0-9A-Fa-f]+)""").find(line)?.groupValues?.getOrNull(1)
                        currentKeyIv = ivHex?.let(BestCineMpegTsTools::hexToIv16)
                    } else if (line.contains("METHOD=NONE", ignoreCase = true)) {
                        currentKeyUri = null
                        currentKeyIv = null
                    }
                }
                !line.startsWith("#") -> {
                    val resolved = resolveRelativeHttpUrl(baseUrl, line) ?: continue
                    val seq = mediaSeq + segments.size
                    val iv = currentKeyIv ?: BestCineMpegTsTools.sequenceToIv16(seq)
                    segments.add(
                        IosHlsSegment(
                            url = resolved,
                            sequence = seq,
                            keyUri = currentKeyUri,
                            iv = iv,
                            precededByDiscontinuity = nextDiscontinuity,
                        ),
                    )
                    nextDiscontinuity = false
                }
            }
        }
        return IosHlsPlaylist(segments, targetDuration, isVod)
    }

    private fun resolveRelativeHttpUrl(baseUrl: String, relative: String): String? {
        val clean = relative.trim()
        if (clean.isEmpty()) return null
        if (clean.startsWith("http://", ignoreCase = true) || clean.startsWith("https://", ignoreCase = true)) {
            return clean
        }
        val schemeIdx = baseUrl.indexOf("://")
        if (schemeIdx <= 0) return null
        val pathIdx = baseUrl.indexOf('/', schemeIdx + 3)
        val origin = if (pathIdx > 0) baseUrl.substring(0, pathIdx) else baseUrl
        return if (clean.startsWith("/")) {
            "$origin$clean"
        } else {
            val baseNoQuery = baseUrl.substringBefore('?')
            val dir = if (baseNoQuery.indexOf('/', schemeIdx + 3) > 0) baseNoQuery.substringBeforeLast('/') else origin
            "$dir/$clean"
        }
    }

    private fun readHttpRequest(clientFd: Int): String = memScoped {
        val pfd = alloc<pollfd>()
        pfd.fd = clientFd
        pfd.events = POLLIN.convert()
        val chunk = ByteArray(2048)
        val sb = StringBuilder()
        val deadlineMs = currentTimeMillisIos() + 8_000L

        while (running && sb.length < 8192) {
            val remaining = (deadlineMs - currentTimeMillisIos()).coerceAtMost(500L)
            if (remaining <= 0L) break
            val ready = poll(pfd.ptr, 1u, remaining.toInt())
            if (ready < 0) {
                if (errno == EINTR) continue
                break
            }
            if (ready == 0) continue
            val n = chunk.usePinned { pinned ->
                recv(clientFd, pinned.addressOf(0), chunk.size.convert(), 0).toInt()
            }
            if (n < 0) {
                if (errno == EINTR || errno == EAGAIN) continue
                break
            }
            if (n == 0) break
            sb.append(chunk.decodeToString(0, n))
            if (sb.indexOf("\r\n\r\n") >= 0) break
        }
        sb.toString()
    }

    private fun writeAll(clientFd: Int, data: ByteArray, length: Int = data.size): Boolean {
        if (length <= 0) return true
        return data.usePinned { pinned ->
            var offset = 0
            while (offset < length) {
                val sent = send(
                    clientFd,
                    pinned.addressOf(offset),
                    (length - offset).convert(),
                    0,
                ).toInt()
                if (sent < 0) {
                    if (errno == EINTR) continue
                    return@usePinned false
                }
                if (sent == 0) return@usePinned false
                offset += sent
            }
            true
        }
    }

    companion object {
        private fun disableSigPipe(fd: Int) = memScoped {
            if (fd < 0) return@memScoped
            val one = alloc<IntVar>()
            one.value = 1
            setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, one.ptr, sizeOf<IntVar>().convert())
        }

        private fun configureClientSocket(fd: Int) = memScoped {
            if (fd < 0) return@memScoped
            val one = alloc<IntVar>()
            one.value = 1
            setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, one.ptr, sizeOf<IntVar>().convert())
            setsockopt(fd, SOL_SOCKET, SO_KEEPALIVE, one.ptr, sizeOf<IntVar>().convert())
            setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, one.ptr, sizeOf<IntVar>().convert())
        }

        fun create(
            defaultUrl: String,
            defaultHeaders: Map<String, String>,
            defaultContentType: String,
            isLiveStream: Boolean,
        ): IosDlnaHttpProxy? = memScoped {
            val fd = socket(AF_INET, SOCK_STREAM, 0)
            if (fd < 0) return null

            val opt = alloc<IntVar>()
            opt.value = 1
            setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, opt.ptr, sizeOf<IntVar>().convert())
            disableSigPipe(fd)

            val addr = alloc<sockaddr_in>()
            addr.sin_len = sizeOf<sockaddr_in>().toUByte()
            addr.sin_family = AF_INET.toUByte()
            addr.sin_port = 0u
            addr.sin_addr.s_addr = 0u // 0.0.0.0

            if (bind(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) != 0) {
                close(fd)
                return null
            }

            val bound = alloc<sockaddr_in>()
            val len = alloc<socklen_tVar>()
            len.value = sizeOf<sockaddr_in>().convert()
            if (getsockname(fd, bound.ptr.reinterpret(), len.ptr) != 0) {
                close(fd)
                return null
            }

            if (listen(fd, 16) != 0) {
                close(fd)
                return null
            }

            val netPort = bound.sin_port.toInt()
            val hostPort = ((netPort and 0xFF) shl 8) or ((netPort ushr 8) and 0xFF)
            if (hostPort <= 0) {
                close(fd)
                return null
            }

            IosDlnaHttpProxy(
                serverFd = fd,
                port = hostPort,
                defaultUrl = defaultUrl,
                defaultHeaders = defaultHeaders,
                defaultContentType = defaultContentType,
                isLiveStream = isLiveStream,
            )
        }
    }
}
