package com.nuvio.app.bestcine.cast

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.Dialog
import com.nuvio.app.core.network.createApiHttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970
import platform.posix.AF_INET
import platform.posix.POLLIN
import platform.posix.SOCK_DGRAM
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_REUSEADDR
import platform.posix.accept
import platform.posix.bind
import platform.posix.close
import platform.posix.connect
import platform.posix.getsockname
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
    Dialog(onDismissRequest = onDismissRequest) {
        content()
    }
}

@OptIn(ExperimentalForeignApi::class)
internal actual object BestCineCastPlatform {
    actual val supportsSystemScreenCastShortcut: Boolean = false

    actual val isBatteryOptimizationRestricting: Boolean = false

    actual fun openBatteryOptimizationSettings(): Boolean = false

    actual fun openSystemScreenCastSettings(): Boolean = false

    actual suspend fun discoverSsdpLocations(timeoutMs: Long): List<String> = withContext(Dispatchers.IO) {
        val found = linkedSetOf<String>()
        val fd = socket(AF_INET, SOCK_DGRAM, 0)
        if (fd < 0) return@withContext emptyList()

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
                dest.sin_addr.s_addr = multicastAddrBe

                searchTargets.forEach { st ->
                    val msg = buildString {
                        append("M-SEARCH * HTTP/1.1\r\n")
                        append("HOST: 239.255.255.250:1900\r\n")
                        append("MAN: \"ssdp:discover\"\r\n")
                        append("MX: 2\r\n")
                        append("ST: $st\r\n")
                        append("\r\n")
                    }.encodeToByteArray()

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

                val pfd = alloc<pollfd>()
                pfd.fd = fd
                pfd.events = POLLIN.convert()
                val buf = ByteArray(4096)
                val deadlineMs = (NSDate().timeIntervalSince1970 * 1000.0).toLong() + timeoutMs.coerceIn(1000L, 5000L)

                while (true) {
                    val nowMs = (NSDate().timeIntervalSince1970 * 1000.0).toLong()
                    val remaining = (deadlineMs - nowMs).coerceAtMost(250L)
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

        found.toList()
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

@OptIn(ExperimentalForeignApi::class)
private fun resolveLocalLanIpv4(targetHostIp: String?): String? = memScoped {
    val targetBe = parseIpv4ToNetworkOrder(targetHostIp) ?: parseIpv4ToNetworkOrder("192.168.1.1") ?: return null
    val fd = socket(AF_INET, SOCK_DGRAM, 0)
    if (fd < 0) return null
    try {
        val remote = alloc<sockaddr_in>()
        remote.sin_len = sizeOf<sockaddr_in>().toUByte()
        remote.sin_family = AF_INET.toUByte()
        // Porta 80 em network byte order (0x5000)
        remote.sin_port = 0x5000u
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
    private val httpClient by lazy { createApiHttpClient() }
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
        if (serverFd >= 0) {
            close(serverFd)
        }
        runCatching { httpClient.close() }
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
                handleLiveOrHlsStream(clientFd, dlnaFeatures)
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
        runCatching {
            httpClient.prepareGet(defaultUrl) {
                applyUpstreamHeaders(rangeHeader = "bytes=0-0")
            }.execute { resp ->
                val code = resp.status.value
                totalLength = parseTotalSizeFromRange(resp.headers["Content-Range"])
                    ?: resp.contentLength()?.takeIf { it > 0L && code == 200 }
                safeMime = normalizeVideoMime(resp.headers["Content-Type"], defaultContentType)
                lastDiagnostic = "HEAD probe -> origem HTTP $code ($safeMime)"
            }
        }.onFailure { ex ->
            lastDiagnostic = "HEAD falha na origem: ${ex.message}"
        }

        val headerBlock = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: $safeMime\r\n")
            if (totalLength != null && totalLength!! > 0L) {
                append("Content-Length: $totalLength\r\n")
            }
            append("Accept-Ranges: bytes\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("transferMode.dlna.org: Streaming\r\n")
            append("contentFeatures.dlna.org: $dlnaFeatures\r\n")
            append("Connection: close\r\n\r\n")
        }
        writeAll(clientFd, headerBlock.encodeToByteArray())
    }

    private suspend fun handleVodGet(clientFd: Int, rangeHeader: String?, dlnaFeatures: String) {
        runCatching {
            httpClient.prepareGet(defaultUrl) {
                applyUpstreamHeaders(rangeHeader = rangeHeader)
            }.execute { resp ->
                val statusCode = resp.status.value
                if (statusCode !in 200..299) {
                    lastDiagnostic = "GET origem retornou HTTP $statusCode"
                    writeAll(clientFd, "HTTP/1.1 $statusCode Error\r\nConnection: close\r\n\r\n".encodeToByteArray())
                    return@execute
                }

                val rawMime = resp.headers["Content-Type"]
                val finalUrl = resp.call.request.url.toString()
                val channel = resp.bodyAsChannel()
                val probeBuf = ByteArray(4096)
                val probeRead = channel.readAvailable(probeBuf, 0, probeBuf.size).coerceAtLeast(0)
                val probeText = if (probeRead > 0) probeBuf.decodeToString(0, probeRead).trimStart() else ""
                val isHls = probeText.startsWith("#EXTM3U") ||
                    rawMime.orEmpty().contains("mpegurl", ignoreCase = true) ||
                    finalUrl.substringBefore('?').lowercase().endsWith(".m3u8")

                if (isHls) {
                    lastDiagnostic = "GET VOD HLS -> convertendo para video/mp2t"
                    val rest = readRemainingText(probeBuf, probeRead, channel)
                    streamHlsAsMpegTs(
                        initialText = rest,
                        initialUrl = finalUrl,
                        clientFd = clientFd,
                        dlnaFeatures = "DLNA.ORG_OP=00;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000",
                    )
                    return@execute
                }

                val safeMime = normalizeVideoMime(rawMime, defaultContentType)
                val contentLength = resp.contentLength()
                val contentRange = resp.headers["Content-Range"]
                val statusMsg = if (statusCode == 206) "Partial Content" else "OK"
                lastDiagnostic = "GET -> HTTP $statusCode ($safeMime)"

                val headerBlock = buildString {
                    append("HTTP/1.1 $statusCode $statusMsg\r\n")
                    append("Content-Type: $safeMime\r\n")
                    if (contentLength != null && contentLength >= 0L) append("Content-Length: $contentLength\r\n")
                    if (!contentRange.isNullOrBlank()) append("Content-Range: $contentRange\r\n")
                    append("Accept-Ranges: bytes\r\n")
                    append("Access-Control-Allow-Origin: *\r\n")
                    append("transferMode.dlna.org: Streaming\r\n")
                    append("contentFeatures.dlna.org: $dlnaFeatures\r\n")
                    append("Connection: close\r\n\r\n")
                }
                if (!writeAll(clientFd, headerBlock.encodeToByteArray())) return@execute
                if (probeRead > 0 && !writeAll(clientFd, probeBuf, probeRead)) return@execute

                val buffer = ByteArray(32 * 1024)
                while (running) {
                    val read = channel.readAvailable(buffer, 0, buffer.size)
                    if (read <= 0) break
                    if (!writeAll(clientFd, buffer, read)) break
                }
            }
        }.onFailure { ex ->
            lastDiagnostic = "GET falha na origem: ${ex.message}"
        }
    }

    private suspend fun handleLiveOrHlsStream(clientFd: Int, dlnaFeatures: String) {
        var succeeded = false
        var attempt = 0
        while (running && attempt < 3 && !succeeded) {
            attempt += 1
            runCatching {
                httpClient.prepareGet(defaultUrl) {
                    applyUpstreamHeaders(rangeHeader = null)
                }.execute { resp ->
                    val statusCode = resp.status.value
                    if (statusCode !in 200..299) {
                        lastDiagnostic = "Live origem retornou HTTP $statusCode (tentativa $attempt)"
                        if (attempt >= 3) {
                            writeAll(clientFd, "HTTP/1.1 $statusCode Bad Gateway\r\nConnection: close\r\n\r\n".encodeToByteArray())
                        }
                        return@execute
                    }
                    succeeded = true

                    val finalUrl = resp.call.request.url.toString()
                    val upstreamMime = resp.headers["Content-Type"]?.lowercase().orEmpty()
                    val channel = resp.bodyAsChannel()
                    val probeBuf = ByteArray(4096)
                    val probeRead = channel.readAvailable(probeBuf, 0, probeBuf.size).coerceAtLeast(0)
                    val probeText = if (probeRead > 0) probeBuf.decodeToString(0, probeRead).trimStart() else ""
                    val isHls = probeText.startsWith("#EXTM3U") ||
                        upstreamMime.contains("mpegurl") ||
                        finalUrl.substringBefore('?').lowercase().endsWith(".m3u8")

                    lastDiagnostic = if (isHls) "Live HLS -> video/mp2t" else "Live TS -> video/mp2t"

                    if (!isHls) {
                        if (!writeLiveTsHeaders(clientFd, dlnaFeatures)) return@execute
                        if (probeRead > 0 && !writeAll(clientFd, probeBuf, probeRead)) return@execute
                        val buffer = ByteArray(16 * 1024)
                        while (running) {
                            val read = channel.readAvailable(buffer, 0, buffer.size)
                            if (read <= 0) break
                            if (!writeAll(clientFd, buffer, read)) break
                        }
                        return@execute
                    }

                    val initialPlaylist = readRemainingText(probeBuf, probeRead, channel)
                    streamHlsAsMpegTs(initialPlaylist, finalUrl, clientFd, dlnaFeatures)
                }
            }.onFailure { ex ->
                lastDiagnostic = "Live falha na origem (tentativa $attempt): ${ex.message}"
            }
            if (!succeeded && running && attempt < 3) {
                delay(450L * attempt)
            }
        }
    }

    private suspend fun streamHlsAsMpegTs(
        initialText: String,
        initialUrl: String,
        clientFd: Int,
        dlnaFeatures: String,
    ) {
        var mediaUrl = initialUrl
        var playlistText = initialText

        if (playlistText.contains("#EXT-X-STREAM-INF")) {
            val bestVariant = pickBestHlsVariant(playlistText, mediaUrl) ?: run {
                writeAll(clientFd, "HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n".encodeToByteArray())
                return
            }
            mediaUrl = bestVariant
            playlistText = fetchTextWithRetry(mediaUrl, attempts = 3) ?: run {
                writeAll(clientFd, "HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n".encodeToByteArray())
                return
            }
        }

        val sentSegments = LinkedHashSet<String>()
        var isFirstPoll = true
        var headersSent = false
        var consecutiveEmpty = 0

        while (running) {
            val parsed = parseHlsMediaPlaylist(playlistText, mediaUrl)
            val segments = parsed.segments
            if (segments.isEmpty()) {
                consecutiveEmpty += 1
                if (consecutiveEmpty >= 8) break
                delay(1000L)
                val refreshed = refreshMediaPlaylist(mediaUrl)
                if (refreshed != null) {
                    mediaUrl = refreshed.first
                    playlistText = refreshed.second
                }
                continue
            }
            consecutiveEmpty = 0

            val toStream = if (isFirstPoll && !parsed.isVod && segments.size > 3) {
                segments.dropLast(2).forEach { sentSegments.add(it) }
                segments.takeLast(2)
            } else {
                segments.filter { it !in sentSegments }
            }
            isFirstPoll = false

            for (segUrl in toStream) {
                if (!running) return
                val streamed = streamSegmentChunked(
                    segUrl = segUrl,
                    clientFd = clientFd,
                    sendHeadersFirst = !headersSent,
                    dlnaFeatures = dlnaFeatures,
                )
                if (streamed == IosSegResult.CLIENT_DISCONNECTED) return
                if (streamed == IosSegResult.OK) {
                    headersSent = true
                    sentSegments.add(segUrl)
                    if (sentSegments.size > 96) {
                        val it = sentSegments.iterator()
                        if (it.hasNext()) {
                            it.next()
                            it.remove()
                        }
                    }
                }
            }

            if (parsed.isVod) break

            val waitMs = ((parsed.targetDurationSec.coerceIn(2, 6)) * 450L)
            delay(waitMs)

            var nextPlaylist: Pair<String, String>? = null
            var pollTry = 0
            while (running && pollTry < 6 && nextPlaylist == null) {
                nextPlaylist = refreshMediaPlaylist(mediaUrl)
                if (nextPlaylist == null) {
                    pollTry += 1
                    delay(750L)
                }
            }
            if (nextPlaylist == null) break
            mediaUrl = nextPlaylist.first
            playlistText = nextPlaylist.second
        }
    }

    private enum class IosSegResult { OK, UPSTREAM_FAILED, CLIENT_DISCONNECTED }

    private suspend fun streamSegmentChunked(
        segUrl: String,
        clientFd: Int,
        sendHeadersFirst: Boolean,
        dlnaFeatures: String,
    ): IosSegResult {
        var result = IosSegResult.UPSTREAM_FAILED
        repeat(2) { attempt ->
            if (!running) return IosSegResult.CLIENT_DISCONNECTED
            runCatching {
                httpClient.prepareGet(segUrl) {
                    applyUpstreamHeaders(null)
                }.execute { resp ->
                    if (!resp.status.isSuccess()) return@execute
                    val channel = resp.bodyAsChannel()
                    var headerWritten = !sendHeadersFirst
                    val buf = ByteArray(16 * 1024)
                    while (running) {
                        val read = channel.readAvailable(buf, 0, buf.size)
                        if (read <= 0) break
                        if (!headerWritten) {
                            if (!writeLiveTsHeaders(clientFd, dlnaFeatures)) {
                                result = IosSegResult.CLIENT_DISCONNECTED
                                return@execute
                            }
                            headerWritten = true
                        }
                        if (!writeAll(clientFd, buf, read)) {
                            result = IosSegResult.CLIENT_DISCONNECTED
                            return@execute
                        }
                    }
                    if (headerWritten) result = IosSegResult.OK
                }
            }
            if (result != IosSegResult.UPSTREAM_FAILED) return result
            if (attempt == 0 && running) delay(350L)
        }
        return result
    }

    private suspend fun refreshMediaPlaylist(currentMediaUrl: String): Pair<String, String>? {
        fetchText(currentMediaUrl)?.let { text ->
            if (text.contains("#EXTM3U")) return currentMediaUrl to text
        }
        return runCatching {
            val resp = httpClient.get(defaultUrl) { applyUpstreamHeaders(null) }
            if (!resp.status.isSuccess()) return@runCatching null
            val resolvedUrl = resp.call.request.url.toString()
            val text = resp.bodyAsText()
            if (!text.contains("#EXTM3U")) return@runCatching null
            if (text.contains("#EXT-X-STREAM-INF")) {
                val variant = pickBestHlsVariant(text, resolvedUrl) ?: return@runCatching null
                val variantText = fetchText(variant) ?: return@runCatching null
                variant to variantText
            } else {
                resolvedUrl to text
            }
        }.getOrNull()
    }

    private fun io.ktor.client.request.HttpRequestBuilder.applyUpstreamHeaders(rangeHeader: String?) {
        defaultHeaders.forEach { (k, v) ->
            if (!k.equals("host", ignoreCase = true) && !k.equals("range", ignoreCase = true)) {
                header(k, v)
            }
        }
        if (!rangeHeader.isNullOrBlank()) {
            header("Range", rangeHeader)
        }
        if (defaultHeaders.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            header("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15")
        }
    }

    private suspend fun fetchTextWithRetry(url: String, attempts: Int): String? {
        repeat(attempts) { idx ->
            fetchText(url)?.let { return it }
            if (idx + 1 < attempts && running) delay(400L)
        }
        return null
    }

    private suspend fun fetchText(url: String): String? = runCatching {
        val resp = httpClient.get(url) { applyUpstreamHeaders(null) }
        if (!resp.status.isSuccess()) return@runCatching null
        resp.bodyAsText()
    }.getOrNull()

    private suspend fun readRemainingText(
        firstChunk: ByteArray,
        firstLen: Int,
        channel: io.ktor.utils.io.ByteReadChannel,
    ): String {
        val sb = StringBuilder()
        if (firstLen > 0) sb.append(firstChunk.decodeToString(0, firstLen))
        val buf = ByteArray(16 * 1024)
        while (running) {
            val n = channel.readAvailable(buf, 0, buf.size)
            if (n <= 0) break
            sb.append(buf.decodeToString(0, n))
            if (sb.length > 512 * 1024) break
        }
        return sb.toString()
    }

    private fun normalizeVideoMime(raw: String?, fallback: String): String {
        val clean = raw?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        return if (clean.startsWith("video/")) clean else fallback
    }

    private fun parseTotalSizeFromRange(contentRange: String?): Long? {
        if (contentRange.isNullOrBlank()) return null
        return contentRange.substringAfter('/', "").trim().toLongOrNull()
    }

    private data class IosHlsPlaylist(val segments: List<String>, val targetDurationSec: Int, val isVod: Boolean)

    private fun pickBestHlsVariant(masterText: String, baseUrl: String): String? {
        val lines = masterText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        var bestUrl: String? = null
        var bestBandwidth = -1L
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val bw = Regex("""BANDWIDTH=(\d+)""").find(line)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
                val nextUri = lines.drop(i + 1).firstOrNull { !it.startsWith("#") }
                if (nextUri != null && bw >= bestBandwidth) {
                    bestBandwidth = bw
                    bestUrl = resolveRelativeHttpUrl(baseUrl, nextUri)
                }
            }
            i += 1
        }
        return bestUrl
    }

    private fun parseHlsMediaPlaylist(text: String, baseUrl: String): IosHlsPlaylist {
        val segments = mutableListOf<String>()
        var targetDuration = 4
        var isVod = false
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXT-X-TARGETDURATION:") -> {
                    targetDuration = line.substringAfter(':').trim().toDoubleOrNull()?.toInt() ?: 4
                }
                line.startsWith("#EXT-X-ENDLIST") -> isVod = true
                !line.startsWith("#") -> {
                    resolveRelativeHttpUrl(baseUrl, line)?.let { segments.add(it) }
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

    private fun readHttpRequest(clientFd: Int): String {
        val buf = ByteArray(4096)
        val n = buf.usePinned { pinned ->
            recv(clientFd, pinned.addressOf(0), buf.size.convert(), 0).toInt()
        }
        if (n <= 0) return ""
        return buf.decodeToString(0, n)
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
                if (sent <= 0) return@usePinned false
                offset += sent
            }
            true
        }
    }

    companion object {
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
