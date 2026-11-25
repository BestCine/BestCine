package com.nuvio.app.bestcine.cast

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.window.Dialog
import com.nuvio.app.bestcine.BestCineAndroidContext
import com.nuvio.app.bestcine.dns.BestCineDohDns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

@Composable
internal actual fun BestCineCastModalContainer(
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismissRequest) {
        content()
    }
}

internal actual object BestCineCastPlatform {
    actual val supportsSystemScreenCastShortcut: Boolean = true

    actual val isBatteryOptimizationRestricting: Boolean
        get() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
            val ctx = BestCineAndroidContext.appContext ?: return false
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
            return !pm.isIgnoringBatteryOptimizations(ctx.packageName)
        }

    actual fun openBatteryOptimizationSettings(): Boolean {
        val context = BestCineAndroidContext.appContext ?: return false
        val pkgUri = Uri.parse("package:${context.packageName}")
        val intents = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                add(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri))
                add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
            add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri))
        }
        for (intent in intents) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val opened = runCatching {
                context.startActivity(intent)
                true
            }.getOrDefault(false)
            if (opened) return true
        }
        return false
    }

    actual fun openSystemScreenCastSettings(): Boolean {
        val context = BestCineAndroidContext.appContext ?: return false
        val intents = listOf(
            Intent(Settings.ACTION_CAST_SETTINGS),
            Intent("android.settings.WIFI_DISPLAY_SETTINGS"),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in intents) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val opened = runCatching {
                context.startActivity(intent)
                true
            }.getOrDefault(false)
            if (opened) return true
        }
        return false
    }

    actual suspend fun discoverSsdpLocations(timeoutMs: Long): List<String> = withContext(Dispatchers.IO) {
        val found = linkedSetOf<String>()
        val multicastLock = runCatching {
            val wifi = BestCineAndroidContext.appContext
                ?.applicationContext
                ?.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifi?.createMulticastLock("bestcine_dlna_ssdp")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()

        val searchTargets = listOf(
            "urn:schemas-upnp-org:service:AVTransport:1",
            "urn:schemas-upnp-org:device:MediaRenderer:1",
        )
        val group = InetAddress.getByName("239.255.255.250")
        val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(1000L, 6000L)

        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket().apply {
                soTimeout = 300
                broadcast = true
            }
            searchTargets.forEach { st ->
                val payload = buildString {
                    append("M-SEARCH * HTTP/1.1\r\n")
                    append("HOST: 239.255.255.250:1900\r\n")
                    append("MAN: \"ssdp:discover\"\r\n")
                    append("MX: 2\r\n")
                    append("ST: $st\r\n")
                    append("\r\n")
                }.toByteArray(StandardCharsets.UTF_8)
                val packet = DatagramPacket(payload, payload.size, group, 1900)
                runCatching { socket.send(packet) }
            }

            val buf = ByteArray(4096)
            while (System.currentTimeMillis() < deadline) {
                val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(50L)
                socket.soTimeout = remaining.coerceAtMost(250L).toInt()
                val pkt = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(pkt)
                    val text = String(pkt.data, 0, pkt.length, StandardCharsets.UTF_8)
                    parseAndroidSsdpLocation(text)?.let { found.add(it) }
                } catch (_: SocketTimeoutException) {
                    // continua aguardando respostas até o deadline
                } catch (_: Throwable) {
                    break
                }
            }
        } finally {
            runCatching { socket?.close() }
            runCatching {
                if (multicastLock?.isHeld == true) multicastLock.release()
            }
        }

        found.toList()
    }

    @Volatile
    private var proxyServer: AndroidDlnaHttpProxy? = null

    actual fun startLocalStreamProxy(
        sourceUrl: String,
        headers: Map<String, String>,
        contentType: String,
        isLive: Boolean,
        targetHostIp: String?,
    ): String? {
        stopLocalStreamProxy()
        val lanIp = pickBestAndroidLanIp(targetHostIp) ?: return null
        val server = runCatching {
            AndroidDlnaHttpProxy(
                defaultUrl = sourceUrl,
                defaultHeaders = headers,
                defaultContentType = contentType,
                isLiveStream = isLive,
            )
        }.getOrNull() ?: return null
        proxyServer = server
        server.start()
        BestCineAndroidContext.appContext?.let { ctx ->
            val currentState = BestCineCastController.state.value
            val mediaTitle = currentState.activeMedia?.title?.takeIf { it.isNotBlank() }
                ?: BestCineCastController.pendingCastTitle?.takeIf { it.isNotBlank() }
                ?: "Transmitindo para TV"
            val devName = currentState.activeDevice?.friendlyName?.takeIf { it.isNotBlank() }
                ?: BestCineCastController.pendingCastDeviceName?.takeIf { it.isNotBlank() }
            val subtitle = devName ?: "Smart TV (DLNA)"
            BestCineCastForegroundService.start(ctx, title = mediaTitle, subtitle = subtitle, isPlaying = true)
        }
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
        BestCineAndroidContext.appContext?.let { ctx ->
            BestCineCastForegroundService.stop(ctx)
        }
    }
}

private fun parseAndroidSsdpLocation(raw: String): String? =
    raw.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
        ?.substringAfter(':')
        ?.trim()
        ?.takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }

private fun pickBestAndroidLanIp(targetHostIp: String?): String? {
    val candidates = runCatching {
        val list = mutableListOf<String>()
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching emptyList()
        while (interfaces.hasMoreElements()) {
            val ni = interfaces.nextElement()
            if (!ni.isUp || ni.isLoopback || ni.isVirtual) continue
            val addrs = ni.inetAddresses
            while (addrs.hasMoreElements()) {
                val addr = addrs.nextElement()
                if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                    addr.hostAddress?.let { list.add(it) }
                }
            }
        }
        list
    }.getOrDefault(emptyList())

    if (candidates.isEmpty()) return null
    val targetPrefix3 = targetHostIp?.split('.')?.take(3)?.joinToString(".")
    if (!targetPrefix3.isNullOrBlank()) {
        candidates.firstOrNull { it.startsWith("$targetPrefix3.") }?.let { return it }
    }
    return candidates.firstOrNull { it.startsWith("192.168.") }
        ?: candidates.firstOrNull { it.startsWith("10.") }
        ?: candidates.first()
}

private class AndroidDlnaHttpProxy(
    private val defaultUrl: String,
    private val defaultHeaders: Map<String, String>,
    private val defaultContentType: String,
    private val isLiveStream: Boolean,
) {
    private val httpClient = OkHttpClient.Builder()
        .dns(BestCineDohDns)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    private val serverSocket = ServerSocket(0, 16, InetAddress.getByName("0.0.0.0"))
    val port: Int get() = serverSocket.localPort
    private val executor = Executors.newCachedThreadPool()
    private val receivedAnyRequest = AtomicBoolean(false)
    val hasReceivedRequest: Boolean get() = receivedAnyRequest.get()

    @Volatile
    var lastDiagnostic: String? = null
        private set

    @Volatile
    private var running = true

    fun start() {
        executor.execute {
            try {
                while (running && !serverSocket.isClosed) {
                    try {
                        val client = serverSocket.accept()
                        executor.execute {
                            try {
                                handleClient(client)
                            } catch (_: Throwable) {
                                // Nunca deixar exceção não tratada derrubar o processo Android
                                // (ex.: InterruptedException ao parar ou Broken pipe quando a TV desconecta)
                            }
                        }
                    } catch (_: Throwable) {
                        if (!running) break
                    }
                }
            } catch (_: Throwable) {
                // Encerramento limpo do loop accept
            }
        }
    }

    fun stop() {
        running = false
        runCatching { serverSocket.close() }
        runCatching { executor.shutdownNow() }
    }

    private fun handleClient(client: Socket) {
        receivedAnyRequest.set(true)
        client.use { socket ->
            runCatching {
                socket.tcpNoDelay = true
                socket.keepAlive = true
            }
            socket.soTimeout = 45_000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) return
            val method = parts[0].uppercase()

            val reqHeaders = linkedMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) {
                    reqHeaders[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                }
            }

            val out = BufferedOutputStream(socket.getOutputStream(), 32 * 1024)
            val dlnaOp = if (isLiveStream) "00" else "01"
            val dlnaFeatures = "DLNA.ORG_OP=$dlnaOp;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"

            if (isLiveStream && method == "HEAD") {
                lastDiagnostic = "HEAD live -> 200 video/mp2t"
                val headerBlock = buildString {
                    append("HTTP/1.1 200 OK\r\n")
                    append("Content-Type: video/mp2t\r\n")
                    append("Accept-Ranges: none\r\n")
                    append("Access-Control-Allow-Origin: *\r\n")
                    append("transferMode.dlna.org: Streaming\r\n")
                    append("contentFeatures.dlna.org: $dlnaFeatures\r\n")
                    append("Connection: close\r\n\r\n")
                }
                out.write(headerBlock.toByteArray(StandardCharsets.ISO_8859_1))
                out.flush()
                return
            }

            if (isLiveStream) {
                handleLiveOrHlsStream(out, dlnaFeatures)
                return
            }

            if (method == "HEAD") {
                handleVodHead(out, dlnaFeatures)
                return
            }

            handleVodGet(out, reqHeaders["range"], dlnaFeatures)
        }
    }

    private fun handleVodHead(out: OutputStream, dlnaFeatures: String) {
        val probe = runCatching {
            openUpstream(defaultUrl, rangeHeader = "bytes=0-0")
        }.getOrElse { ex ->
            lastDiagnostic = "HEAD falha na origem: ${ex.message}"
            val fallback = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: $defaultContentType\r\n")
                append("Accept-Ranges: bytes\r\n")
                append("Access-Control-Allow-Origin: *\r\n")
                append("transferMode.dlna.org: Streaming\r\n")
                append("contentFeatures.dlna.org: $dlnaFeatures\r\n")
                append("Connection: close\r\n\r\n")
            }
            out.write(fallback.toByteArray(StandardCharsets.ISO_8859_1))
            out.flush()
            return
        }

        probe.use { resp ->
            val code = resp.code
            val totalLength = parseTotalSizeFromRange(resp.header("Content-Range"))
                ?: resp.body?.contentLength()?.takeIf { it > 0L && code == 200 }
            val safeMime = normalizeVideoMime(resp.header("Content-Type"), defaultContentType)
            lastDiagnostic = "HEAD probe -> origem HTTP $code ($safeMime)"

            val headerBlock = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: $safeMime\r\n")
                if (totalLength != null && totalLength > 0L) {
                    append("Content-Length: $totalLength\r\n")
                }
                append("Accept-Ranges: bytes\r\n")
                append("Access-Control-Allow-Origin: *\r\n")
                append("transferMode.dlna.org: Streaming\r\n")
                append("contentFeatures.dlna.org: $dlnaFeatures\r\n")
                append("Connection: close\r\n\r\n")
            }
            out.write(headerBlock.toByteArray(StandardCharsets.ISO_8859_1))
            out.flush()
        }
    }

    private fun handleVodGet(out: OutputStream, rangeHeader: String?, dlnaFeatures: String) {
        val resp = openUpstreamWithRetry(defaultUrl, rangeHeader = rangeHeader, maxAttempts = 2)
        if (resp == null) {
            val err = "HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n"
            out.write(err.toByteArray(StandardCharsets.ISO_8859_1))
            out.flush()
            return
        }

        resp.use { r ->
            val statusCode = r.code
            val rawMime = r.header("Content-Type")
            val finalUrl = r.request.url.toString()
            val body = r.body
            if (body == null || statusCode !in 200..299) {
                lastDiagnostic = "GET origem retornou HTTP $statusCode"
                val err = "HTTP/1.1 $statusCode Error\r\nConnection: close\r\n\r\n"
                out.write(err.toByteArray(StandardCharsets.ISO_8859_1))
                out.flush()
                return
            }

            val input = BufferedInputStream(body.byteStream(), 64 * 1024)
            input.mark(8192)
            val probeBuf = ByteArray(256)
            val probeRead = input.read(probeBuf).coerceAtLeast(0)
            input.reset()
            val probeText = if (probeRead > 0) String(probeBuf, 0, probeRead, StandardCharsets.UTF_8).trimStart() else ""
            val isHls = probeText.startsWith("#EXTM3U") ||
                rawMime.orEmpty().contains("mpegurl", ignoreCase = true) ||
                finalUrl.substringBefore('?').lowercase().endsWith(".m3u8")

            if (isHls) {
                lastDiagnostic = "GET VOD HLS -> convertendo para video/mp2t"
                val initialPlaylist = input.bufferedReader(StandardCharsets.UTF_8).readText()
                streamHlsAsMpegTs(
                    initialText = initialPlaylist,
                    initialUrl = finalUrl,
                    out = out,
                    dlnaFeatures = "DLNA.ORG_OP=00;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000",
                )
                return
            }

            val safeMime = normalizeVideoMime(rawMime, defaultContentType)
            val contentLength = body.contentLength()
            val contentRange = r.header("Content-Range")
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
            out.write(headerBlock.toByteArray(StandardCharsets.ISO_8859_1))
            out.flush()

            val buffer = ByteArray(32 * 1024)
            while (running) {
                val read = input.read(buffer)
                if (read <= 0) break
                out.write(buffer, 0, read)
            }
            out.flush()
        }
    }

    private fun handleLiveOrHlsStream(out: OutputStream, dlnaFeatures: String) {
        val resp = openUpstreamWithRetry(defaultUrl, rangeHeader = null, maxAttempts = 3)
        if (resp == null) {
            val errHeader = "HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n"
            out.write(errHeader.toByteArray(StandardCharsets.ISO_8859_1))
            out.flush()
            return
        }

        resp.use { r ->
            val statusCode = r.code
            val body = r.body
            if (body == null || statusCode !in 200..299) {
                lastDiagnostic = "Live origem retornou HTTP $statusCode"
                val errHeader = "HTTP/1.1 $statusCode Bad Gateway\r\nConnection: close\r\n\r\n"
                out.write(errHeader.toByteArray(StandardCharsets.ISO_8859_1))
                out.flush()
                return
            }

            val finalUrl = r.request.url.toString()
            val upstreamMime = r.header("Content-Type")?.lowercase().orEmpty()
            val input = BufferedInputStream(body.byteStream(), 64 * 1024)
            input.mark(8192)
            val probeBuf = ByteArray(256)
            val probeRead = input.read(probeBuf).coerceAtLeast(0)
            input.reset()

            val probeText = if (probeRead > 0) String(probeBuf, 0, probeRead, StandardCharsets.UTF_8).trimStart() else ""
            val isHlsPlaylist = probeText.startsWith("#EXTM3U") ||
                upstreamMime.contains("mpegurl") ||
                finalUrl.substringBefore('?').lowercase().endsWith(".m3u8")

            lastDiagnostic = if (isHlsPlaylist) "Live HLS -> video/mp2t" else "Live TS -> video/mp2t"

            if (!isHlsPlaylist) {
                writeLiveTsHeaders(out, dlnaFeatures)
                val buffer = ByteArray(16 * 1024)
                while (running) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                    out.flush()
                }
                return
            }

            // Só envia 200 OK após resolver a playlist (e transmite os segmentos .ts em chunks de 16KB
            // em tempo real, sem esperar baixar o segmento inteiro na RAM — evita timeout na Samsung!).
            val initialPlaylist = input.bufferedReader(StandardCharsets.UTF_8).readText()
            streamHlsAsMpegTs(initialPlaylist, finalUrl, out, dlnaFeatures)
        }
    }

    private fun writeLiveTsHeaders(out: OutputStream, dlnaFeatures: String) {
        val headerBlock = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: video/mp2t\r\n")
            append("Accept-Ranges: none\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("transferMode.dlna.org: Streaming\r\n")
            append("contentFeatures.dlna.org: $dlnaFeatures\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(headerBlock.toByteArray(StandardCharsets.ISO_8859_1))
        out.flush()
    }

    private fun streamHlsAsMpegTs(
        initialText: String,
        initialUrl: String,
        out: OutputStream,
        dlnaFeatures: String,
    ) {
        var mediaUrl = initialUrl
        var playlistText = initialText

        if (playlistText.contains("#EXT-X-STREAM-INF")) {
            val bestVariant = pickBestHlsVariant(playlistText, mediaUrl) ?: run {
                out.write("HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
                out.flush()
                return
            }
            mediaUrl = bestVariant
            playlistText = fetchTextWithRetry(mediaUrl, attempts = 3) ?: run {
                out.write("HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
                out.flush()
                return
            }
        }

        val sentSegments = LinkedHashSet<String>()
        val keyCache = HashMap<String, ByteArray>()
        var isFirstPoll = true
        var headersSent = false
        var consecutiveErrors = 0

        while (running) {
            val parsed = parseHlsMediaPlaylist(playlistText, mediaUrl)
            val segments = parsed.segments
            if (segments.isEmpty()) {
                consecutiveErrors += 1
                if (consecutiveErrors >= 8) break
                if (!safeSleep(1000L)) return
                val refreshed = refreshMediaPlaylist(mediaUrl)
                if (refreshed != null) {
                    mediaUrl = refreshed.first
                    playlistText = refreshed.second
                }
                continue
            }
            consecutiveErrors = 0

            val toStream = if (isFirstPoll && !parsed.isVod && segments.size > 3) {
                segments.dropLast(2).forEach { sentSegments.add(it.url) }
                segments.takeLast(2)
            } else {
                segments.filter { it.url !in sentSegments }
            }
            isFirstPoll = false

            for (seg in toStream) {
                if (!running) return
                val streamed = streamSegmentChunked(
                    seg = seg,
                    keyCache = keyCache,
                    out = out,
                    sendHeadersFirst = !headersSent,
                    dlnaFeatures = dlnaFeatures,
                )
                if (streamed == SegmentStreamResult.CLIENT_DISCONNECTED) {
                    return
                }
                if (streamed == SegmentStreamResult.OK) {
                    headersSent = true
                    sentSegments.add(seg.url)
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
            if (!safeSleep(waitMs)) return

            // Retry resiliente: uma oscilação rápida de Wi-Fi ao minimizar NÃO encerra mais a transmissão!
            var nextPlaylist: Pair<String, String>? = null
            var pollTry = 0
            while (running && pollTry < 6 && nextPlaylist == null) {
                nextPlaylist = refreshMediaPlaylist(mediaUrl)
                if (nextPlaylist == null) {
                    pollTry += 1
                    if (!safeSleep(750L)) return
                }
            }
            if (nextPlaylist == null) break
            mediaUrl = nextPlaylist.first
            playlistText = nextPlaylist.second
        }
    }

    private fun safeSleep(ms: Long): Boolean {
        if (!running) return false
        return try {
            Thread.sleep(ms)
            running
        } catch (_: InterruptedException) {
            false
        }
    }

    private enum class SegmentStreamResult { OK, UPSTREAM_FAILED, CLIENT_DISCONNECTED }

    /**
     * Transmite cada segmento .ts em pedaços de 16 KB em tempo real conforme chegam da rede
     * (sem segurar 2-4 MB na RAM antes do 1º byte -> elimina timeout "Arquivo não suportado" na Samsung!).
     */
    private fun streamSegmentChunked(
        seg: HlsSegment,
        keyCache: HashMap<String, ByteArray>,
        out: OutputStream,
        sendHeadersFirst: Boolean,
        dlnaFeatures: String,
    ): SegmentStreamResult {
        val resp = openUpstreamWithRetry(seg.url, rangeHeader = null, maxAttempts = 2)
            ?: return SegmentStreamResult.UPSTREAM_FAILED

        return resp.use { r ->
            val body = r.body
            if (!r.isSuccessful || body == null) return@use SegmentStreamResult.UPSTREAM_FAILED

            val rawStream = if (seg.keyUri != null) {
                val keyBytes = keyCache.getOrPut(seg.keyUri) {
                    fetchBytes(seg.keyUri) ?: ByteArray(0)
                }
                if (keyBytes.size == 16) {
                    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(seg.iv))
                    CipherInputStream(body.byteStream(), cipher)
                } else {
                    body.byteStream()
                }
            } else {
                body.byteStream()
            }

            var headerWritten = !sendHeadersFirst
            val buf = ByteArray(16 * 1024)
            try {
                rawStream.use { inp ->
                    while (running) {
                        val read = inp.read(buf)
                        if (read <= 0) break
                        if (!headerWritten) {
                            writeLiveTsHeaders(out, dlnaFeatures)
                            headerWritten = true
                        }
                        try {
                            out.write(buf, 0, read)
                            out.flush()
                        } catch (_: Throwable) {
                            return@use SegmentStreamResult.CLIENT_DISCONNECTED
                        }
                    }
                }
                if (headerWritten) SegmentStreamResult.OK else SegmentStreamResult.UPSTREAM_FAILED
            } catch (_: Throwable) {
                if (headerWritten) SegmentStreamResult.OK else SegmentStreamResult.UPSTREAM_FAILED
            }
        }
    }

    private fun refreshMediaPlaylist(currentMediaUrl: String): Pair<String, String>? {
        fetchText(currentMediaUrl)?.let { text ->
            if (text.contains("#EXTM3U")) return currentMediaUrl to text
        }
        // Se o token/URL da variante expirou, re-resolve a partir da URL original do canal!
        val resp = runCatching { openUpstream(defaultUrl, rangeHeader = null) }.getOrNull() ?: return null
        return resp.use { r ->
            if (!r.isSuccessful) return@use null
            val resolvedUrl = r.request.url.toString()
            val text = r.body?.string() ?: return@use null
            if (!text.contains("#EXTM3U")) return@use null
            if (text.contains("#EXT-X-STREAM-INF")) {
                val variant = pickBestHlsVariant(text, resolvedUrl) ?: return@use null
                val variantText = fetchText(variant) ?: return@use null
                variant to variantText
            } else {
                resolvedUrl to text
            }
        }
    }

    private fun openUpstreamWithRetry(url: String, rangeHeader: String?, maxAttempts: Int): Response? {
        var attempt = 0
        while (running && attempt < maxAttempts) {
            attempt += 1
            val resp = runCatching { openUpstream(url, rangeHeader) }.getOrElse { ex ->
                lastDiagnostic = "Origem erro (tentativa $attempt): ${ex.message}"
                null
            }
            if (resp != null) {
                if (resp.isSuccessful) return resp
                val code = resp.code
                lastDiagnostic = "Origem HTTP $code (tentativa $attempt)"
                if (attempt >= maxAttempts) return resp
                resp.close()
            }
            if (attempt < maxAttempts) {
                runCatching { Thread.sleep(450L * attempt) }
            }
        }
        return null
    }

    private fun openUpstream(url: String, rangeHeader: String?): Response {
        val builder = Request.Builder().url(url).get()
        defaultHeaders.forEach { (k, v) ->
            if (!k.equals("host", ignoreCase = true) && !k.equals("range", ignoreCase = true)) {
                builder.header(k, v)
            }
        }
        if (!rangeHeader.isNullOrBlank()) {
            builder.header("Range", rangeHeader)
        }
        if (defaultHeaders.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            builder.header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
        }
        return httpClient.newCall(builder.build()).execute()
    }

    private fun fetchTextWithRetry(url: String, attempts: Int): String? {
        repeat(attempts) { idx ->
            fetchText(url)?.let { return it }
            if (idx + 1 < attempts) runCatching { Thread.sleep(400L) }
        }
        return null
    }

    private fun fetchText(url: String): String? = runCatching {
        openUpstream(url, null).use { resp ->
            if (!resp.isSuccessful) return@runCatching null
            resp.body?.string()
        }
    }.getOrNull()

    private fun fetchBytes(url: String): ByteArray? = runCatching {
        openUpstream(url, null).use { resp ->
            if (!resp.isSuccessful) return@runCatching null
            resp.body?.bytes()
        }
    }.getOrNull()

    private fun normalizeVideoMime(raw: String?, fallback: String): String {
        val clean = raw?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        return when {
            clean.startsWith("video/") -> clean
            else -> fallback
        }
    }

    private fun parseTotalSizeFromRange(contentRange: String?): Long? {
        if (contentRange.isNullOrBlank()) return null
        val slash = contentRange.substringAfter('/', "").trim()
        return slash.toLongOrNull()
    }

    private data class HlsSegment(val url: String, val keyUri: String?, val iv: ByteArray)
    private data class HlsPlaylist(val segments: List<HlsSegment>, val targetDurationSec: Int, val isVod: Boolean)

    private fun pickBestHlsVariant(masterText: String, baseUrl: String): String? {
        val base = URL(baseUrl)
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
                    bestUrl = runCatching { URL(base, nextUri).toString() }.getOrNull()
                }
            }
            i += 1
        }
        return bestUrl
    }

    private fun parseHlsMediaPlaylist(text: String, baseUrl: String): HlsPlaylist {
        val base = URL(baseUrl)
        val segments = mutableListOf<HlsSegment>()
        var targetDuration = 4
        var mediaSeq = 0L
        var isVod = false
        var currentKeyUri: String? = null
        var currentKeyIv: ByteArray? = null

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
                line.startsWith("#EXT-X-ENDLIST") -> {
                    isVod = true
                }
                line.startsWith("#EXT-X-KEY:") -> {
                    if (line.contains("METHOD=AES-128", ignoreCase = true)) {
                        val uriRel = Regex("""URI="([^"]+)"""").find(line)?.groupValues?.getOrNull(1)
                        currentKeyUri = uriRel?.let { runCatching { URL(base, it).toString() }.getOrNull() }
                        val ivHex = Regex("""IV=0x([0-9A-Fa-f]+)""").find(line)?.groupValues?.getOrNull(1)
                        currentKeyIv = ivHex?.let(::hexToIv16)
                    } else if (line.contains("METHOD=NONE", ignoreCase = true)) {
                        currentKeyUri = null
                        currentKeyIv = null
                    }
                }
                !line.startsWith("#") -> {
                    val segUrl = runCatching { URL(base, line).toString() }.getOrNull() ?: continue
                    val seq = mediaSeq + segments.size
                    val iv = currentKeyIv ?: sequenceToIv16(seq)
                    segments.add(HlsSegment(segUrl, currentKeyUri, iv))
                }
            }
        }
        return HlsPlaylist(segments, targetDuration, isVod)
    }

    private fun hexToIv16(hex: String): ByteArray {
        val padded = hex.takeLast(32).padStart(32, '0')
        return ByteArray(16) { idx ->
            padded.substring(idx * 2, idx * 2 + 2).toInt(16).toByte()
        }
    }

    private fun sequenceToIv16(seq: Long): ByteArray {
        val out = ByteArray(16)
        for (i in 0 until 8) {
            out[15 - i] = ((seq ushr (i * 8)) and 0xFF).toByte()
        }
        return out
    }
}
