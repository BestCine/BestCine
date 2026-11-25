package com.nuvio.app.bestcine.cast

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Base64
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
                lanIp = lanIp,
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

        // Sonda rapidamente se o link ao vivo/HLS é uma playlist #EXTM3U real (ex.: Pluto TV com áudio separado
        // e cortes de comercial #EXT-X-DISCONTINUITY). Se for HLS, entrega `/live.m3u8` reescrito nativamente
        // (áudio 100% + buffer nativo na TV + zero quedas em intervalos!). O servidor também atende `/live.ts`
        // automaticamente como plano B caso uma TV muito antiga recuse `.m3u8`.
        val isHlsDetected = if (isLive || sourceUrl.substringBefore('?').lowercase().endsWith(".m3u8")) {
            server.probeIfHlsPlaylist()
        } else {
            false
        }

        val path = when {
            isHlsDetected -> "live.m3u8"
            isLive || contentType.contains("mp2t", ignoreCase = true) -> "live.ts"
            contentType.contains("mkv", ignoreCase = true) || contentType.contains("matroska", ignoreCase = true) -> "stream.mkv"
            else -> "stream.mp4"
        }
        return "http://$lanIp:${server.port}/$path"
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
    private val lanIp: String,
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

    /**
     * Sonda rapidamente se `defaultUrl` retorna uma playlist HLS `#EXTM3U`.
     * (Não mantém a conexão aberta se for TS binário — fecha logo após ler os primeiros 64 bytes.)
     */
    fun probeIfHlsPlaylist(): Boolean {
        val urlLower = defaultUrl.substringBefore('?').lowercase()
        if (urlLower.endsWith(".m3u8")) return true
        val resp = runCatching { openUpstream(defaultUrl, rangeHeader = null) }.getOrNull() ?: return false
        return resp.use { r ->
            if (!r.isSuccessful) return@use false
            val finalUrl = r.request.url.toString().substringBefore('?').lowercase()
            val mime = r.header("Content-Type")?.lowercase().orEmpty()
            if (mime.contains("mpegurl") || finalUrl.endsWith(".m3u8")) return@use true
            val body = r.body ?: return@use false
            val buf = ByteArray(128)
            val n = runCatching { body.byteStream().read(buf) }.getOrDefault(0)
            if (n <= 0) return@use false
            val head = String(buf, 0, n, StandardCharsets.UTF_8).trimStart()
            head.startsWith("#EXTM3U")
        }
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
            val rawPath = parts[1].substringBefore('?')

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

            // 1. Sub-recursos reescritos de HLS: `/hls/<base64>.m3u8`, `.ts`, `.aac`, `.m4s`, `.mp4`, `.key`
            if (rawPath.startsWith("/hls/")) {
                handleProxiedHlsResource(rawPath, method, reqHeaders["range"], out)
                return
            }

            // 2. Playlist HLS principal reescrita (`/live.m3u8`) — preserva áudio separado (Pluto TV) e
            //    permite buffer nativo + reconexão automática pela própria Smart TV!
            if (rawPath.endsWith(".m3u8", ignoreCase = true)) {
                handleHlsPlaylistRequest(defaultUrl, method, out)
                return
            }

            // 3. Fluxo contínuo MPEG-TS (`/live.ts`) — para canais `.ts` puros ou TVs antigas que não aceitam `.m3u8`
            if (rawPath.endsWith(".ts", ignoreCase = true) || isLiveStream) {
                if (method == "HEAD") {
                    lastDiagnostic = "HEAD live.ts -> 200 video/mp2t"
                    writeLiveTsHeaders(out, dlnaFeatures)
                    return
                }
                handleLiveOrHlsAsTsStream(out, dlnaFeatures)
                return
            }

            // 4. Filmes e Séries (VOD .mp4 / .mkv) com Range / Seek
            if (method == "HEAD") {
                handleVodHead(out, dlnaFeatures)
                return
            }
            handleVodGet(out, reqHeaders["range"], dlnaFeatures)
        }
    }

    /**
     * Entrega qualquer playlist `.m3u8` (master, vídeo ou áudio separado da Pluto TV) reescrevendo
     * TODAS as URIs (inclusive `#EXT-X-MEDIA`, `#EXT-X-KEY`, `#EXT-X-MAP` e segmentos) para o proxy local
     * com Base64 URL-safe e extensão real preservada.
     */
    private fun handleHlsPlaylistRequest(targetUrl: String, method: String, out: OutputStream) {
        if (method == "HEAD") {
            lastDiagnostic = "HEAD m3u8 -> 200 application/vnd.apple.mpegurl"
            val headerBlock = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: application/vnd.apple.mpegurl\r\n")
                append("Cache-Control: no-cache, no-store\r\n")
                append("Access-Control-Allow-Origin: *\r\n")
                append("transferMode.dlna.org: Streaming\r\n")
                append("contentFeatures.dlna.org: DLNA.ORG_OP=00;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000\r\n")
                append("Connection: close\r\n\r\n")
            }
            out.write(headerBlock.toByteArray(StandardCharsets.ISO_8859_1))
            out.flush()
            return
        }

        val resp = openUpstreamWithRetry(targetUrl, rangeHeader = null, maxAttempts = 3)
        if (resp == null) {
            val err = "HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n"
            out.write(err.toByteArray(StandardCharsets.ISO_8859_1))
            out.flush()
            return
        }

        resp.use { r ->
            val code = r.code
            val body = r.body
            if (!r.isSuccessful || body == null) {
                lastDiagnostic = "HLS m3u8 origem retornou HTTP $code"
                val err = "HTTP/1.1 $code Bad Gateway\r\nConnection: close\r\n\r\n"
                out.write(err.toByteArray(StandardCharsets.ISO_8859_1))
                out.flush()
                return
            }

            val finalUrl = r.request.url.toString()
            val rawText = body.string()
            val rewritten = rewriteHlsPlaylist(rawText, finalUrl)
            val bytes = rewritten.toByteArray(StandardCharsets.UTF_8)
            lastDiagnostic = "GET m3u8 -> 200 (${bytes.size} bytes)"

            val headerBlock = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: application/vnd.apple.mpegurl\r\n")
                append("Content-Length: ${bytes.size}\r\n")
                append("Cache-Control: no-cache, no-store\r\n")
                append("Access-Control-Allow-Origin: *\r\n")
                append("transferMode.dlna.org: Streaming\r\n")
                append("contentFeatures.dlna.org: DLNA.ORG_OP=00;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000\r\n")
                append("Connection: close\r\n\r\n")
            }
            out.write(headerBlock.toByteArray(StandardCharsets.ISO_8859_1))
            out.write(bytes)
            out.flush()
        }
    }

    private fun handleProxiedHlsResource(
        rawPath: String,
        method: String,
        rangeHeader: String?,
        out: OutputStream,
    ) {
        val tokenWithExt = rawPath.removePrefix("/hls/")
        val b64 = tokenWithExt.substringBeforeLast('.')
        val ext = tokenWithExt.substringAfterLast('.', "").lowercase()
        val upstreamUrl = decodeUrlBase64(b64)
        if (upstreamUrl.isNullOrBlank()) {
            out.write("HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            out.flush()
            return
        }

        if (ext == "m3u8") {
            handleHlsPlaylistRequest(upstreamUrl, method, out)
            return
        }

        val resp = openUpstreamWithRetry(upstreamUrl, rangeHeader = rangeHeader, maxAttempts = 3)
        if (resp == null) {
            out.write("HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            out.flush()
            return
        }

        resp.use { r ->
            val statusCode = r.code
            val body = r.body
            if (!r.isSuccessful || body == null) {
                out.write("HTTP/1.1 $statusCode Bad Gateway\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
                out.flush()
                return
            }

            val rawMime = r.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase().orEmpty()
            val finalUrl = r.request.url.toString()

            // Se o recurso sem extensão `.m3u8` na verdade for uma sub-playlist HLS, reescreve também!
            val input = BufferedInputStream(body.byteStream(), 32 * 1024)
            input.mark(256)
            val probe = ByteArray(64)
            val probeN = input.read(probe).coerceAtLeast(0)
            input.reset()
            val probeStr = if (probeN > 0) String(probe, 0, probeN, StandardCharsets.UTF_8).trimStart() else ""
            if (probeStr.startsWith("#EXTM3U") || rawMime.contains("mpegurl")) {
                val rawText = input.bufferedReader(StandardCharsets.UTF_8).readText()
                val rewritten = rewriteHlsPlaylist(rawText, finalUrl).toByteArray(StandardCharsets.UTF_8)
                val headerBlock = buildString {
                    append("HTTP/1.1 200 OK\r\n")
                    append("Content-Type: application/vnd.apple.mpegurl\r\n")
                    append("Content-Length: ${rewritten.size}\r\n")
                    append("Cache-Control: no-cache, no-store\r\n")
                    append("Access-Control-Allow-Origin: *\r\n")
                    append("transferMode.dlna.org: Streaming\r\n")
                    append("Connection: close\r\n\r\n")
                }
                out.write(headerBlock.toByteArray(StandardCharsets.ISO_8859_1))
                if (method != "HEAD") out.write(rewritten)
                out.flush()
                return
            }

            val contentType = when {
                ext == "ts" -> "video/mp2t"
                ext == "aac" -> "audio/aac"
                ext == "m4a" -> "audio/mp4"
                ext == "m4s" || ext == "mp4" -> "video/mp4"
                ext == "vtt" -> "text/vtt"
                ext == "key" -> "application/octet-stream"
                rawMime.isNotBlank() -> rawMime
                else -> "video/mp2t"
            }
            val contentLength = body.contentLength()
            val contentRange = r.header("Content-Range")
            val statusMsg = if (statusCode == 206) "Partial Content" else "OK"

            val headerBlock = buildString {
                append("HTTP/1.1 $statusCode $statusMsg\r\n")
                append("Content-Type: $contentType\r\n")
                if (contentLength >= 0L) append("Content-Length: $contentLength\r\n")
                if (!contentRange.isNullOrBlank()) append("Content-Range: $contentRange\r\n")
                append("Accept-Ranges: bytes\r\n")
                append("Access-Control-Allow-Origin: *\r\n")
                append("transferMode.dlna.org: Streaming\r\n")
                append("Connection: close\r\n\r\n")
            }
            out.write(headerBlock.toByteArray(StandardCharsets.ISO_8859_1))
            out.flush()

            if (method != "HEAD") {
                val buf = ByteArray(16 * 1024)
                while (running) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    out.write(buf, 0, read)
                    out.flush()
                }
            }
        }
    }

    private fun rewriteHlsPlaylist(content: String, baseUrl: String): String {
        val base = URL(baseUrl)
        val uriAttrRegex = Regex("""URI="([^"]+)"""")
        return content.lineSequence().joinToString("\n") { line ->
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> line
                trimmed.startsWith("#") -> {
                    uriAttrRegex.replace(line) { m ->
                        val rawRef = m.groupValues[1]
                        val resolved = runCatching { URL(base, rawRef).toString() }.getOrDefault(rawRef)
                        val isAudioOrSubPlaylist = line.startsWith("#EXT-X-MEDIA", ignoreCase = true)
                        val isKey = line.startsWith("#EXT-X-KEY", ignoreCase = true)
                        val forcedExt = when {
                            isAudioOrSubPlaylist -> "m3u8"
                            isKey -> "key"
                            else -> guessExtension(resolved, fallback = "mp4")
                        }
                        """URI="${proxyHlsUrlFor(resolved, forcedExt)}""""
                    }
                }
                else -> {
                    val resolved = runCatching { URL(base, trimmed).toString() }.getOrDefault(trimmed)
                    val isMaster = content.contains("#EXT-X-STREAM-INF")
                    val ext = if (isMaster) "m3u8" else guessExtension(resolved, fallback = "ts")
                    proxyHlsUrlFor(resolved, ext)
                }
            }
        }
    }

    private fun proxyHlsUrlFor(targetUrl: String, ext: String): String {
        val encoded = Base64.encodeToString(
            targetUrl.toByteArray(StandardCharsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        return "http://$lanIp:$port/hls/$encoded.$ext"
    }

    private fun decodeUrlBase64(token: String): String? = runCatching {
        val bytes = Base64.decode(token, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        String(bytes, StandardCharsets.UTF_8)
    }.getOrNull()

    private fun guessExtension(url: String, fallback: String): String {
        val clean = url.substringBefore('?').substringBefore('#').lowercase()
        return when {
            clean.endsWith(".m3u8") -> "m3u8"
            clean.endsWith(".ts") -> "ts"
            clean.endsWith(".aac") -> "aac"
            clean.endsWith(".m4s") -> "m4s"
            clean.endsWith(".m4a") -> "m4a"
            clean.endsWith(".mp4") -> "mp4"
            clean.endsWith(".vtt") -> "vtt"
            clean.endsWith(".key") -> "key"
            else -> fallback
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

    /**
     * Modo MPEG-TS contínuo (`/live.ts`):
     * - Para canais TS puros: reconecta automaticamente até 8x se o servidor IPTV derrubar a conexão!
     * - Para HLS (fallback em TVs antigas): prioriza variantes MUXED (com áudio embutido) + 16 KB realtime.
     */
    private fun handleLiveOrHlsAsTsStream(out: OutputStream, dlnaFeatures: String) {
        var headersSent = false
        var reconnects = 0
        while (running && reconnects < 8) {
            val resp = openUpstreamWithRetry(defaultUrl, rangeHeader = null, maxAttempts = 3)
            if (resp == null) {
                if (!headersSent) {
                    out.write("HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
                    out.flush()
                    return
                }
                reconnects += 1
                if (!safeSleep(600L)) return
                continue
            }

            var clientDisconnected = false
            resp.use { r ->
                val statusCode = r.code
                val body = r.body
                if (body == null || statusCode !in 200..299) {
                    lastDiagnostic = "Live origem retornou HTTP $statusCode"
                    if (!headersSent) {
                        out.write("HTTP/1.1 $statusCode Bad Gateway\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
                        out.flush()
                        return
                    }
                    return@use
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

                if (isHlsPlaylist) {
                    lastDiagnostic = "Live HLS -> video/mp2t (fallback)"
                    val initialPlaylist = input.bufferedReader(StandardCharsets.UTF_8).readText()
                    streamHlsAsMpegTs(initialPlaylist, finalUrl, out, dlnaFeatures)
                    return
                }

                lastDiagnostic = "Live TS -> video/mp2t"
                if (!headersSent) {
                    writeLiveTsHeaders(out, dlnaFeatures)
                    headersSent = true
                }

                val buffer = ByteArray(16 * 1024)
                var streamedBytes = 0L
                while (running) {
                    val read = try {
                        input.read(buffer)
                    } catch (_: Throwable) {
                        -1
                    }
                    if (read <= 0) break
                    try {
                        out.write(buffer, 0, read)
                        out.flush()
                        streamedBytes += read
                    } catch (_: Throwable) {
                        clientDisconnected = true
                        break
                    }
                }
                if (streamedBytes > 256 * 1024L) {
                    // Fluxo rodou bem antes da queda da origem -> zera contador de reconexão!
                    reconnects = 0
                }
            }

            if (clientDisconnected) return
            reconnects += 1
            if (!safeSleep(500L)) return
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
            val bestVariant = pickBestMuxedHlsVariant(playlistText, mediaUrl) ?: run {
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
                if (streamed == SegmentStreamResult.CLIENT_DISCONNECTED) return
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
        val resp = runCatching { openUpstream(defaultUrl, rangeHeader = null) }.getOrNull() ?: return null
        return resp.use { r ->
            if (!r.isSuccessful) return@use null
            val resolvedUrl = r.request.url.toString()
            val text = r.body?.string() ?: return@use null
            if (!text.contains("#EXTM3U")) return@use null
            if (text.contains("#EXT-X-STREAM-INF")) {
                val variant = pickBestMuxedHlsVariant(text, resolvedUrl) ?: return@use null
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
            if (attempt < maxAttempts && !safeSleep(450L * attempt)) {
                return null
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
            if (idx + 1 < attempts && !safeSleep(400L)) return null
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

    /**
     * Prioriza variantes MUXED (vídeo+áudio juntos) sobre variantes video-only com áudio separado (`AUDIO="..."`).
     */
    private fun pickBestMuxedHlsVariant(masterText: String, baseUrl: String): String? {
        val base = URL(baseUrl)
        val lines = masterText.lines().map { it.trim() }.filter { it.isNotEmpty() }

        // Identifica grupos de áudio que possuem URI externa separada
        val externalAudioGroups = mutableSetOf<String>()
        for (line in lines) {
            if (line.startsWith("#EXT-X-MEDIA:") &&
                line.contains("TYPE=AUDIO", ignoreCase = true) &&
                line.contains("URI=\"", ignoreCase = true)
            ) {
                Regex("""GROUP-ID="([^"]+)"""").find(line)?.groupValues?.getOrNull(1)?.let {
                    externalAudioGroups.add(it)
                }
            }
        }

        var bestMuxedUrl: String? = null
        var bestMuxedBw = -1L
        var bestAnyUrl: String? = null
        var bestAnyBw = -1L

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val bw = Regex("""BANDWIDTH=(\d+)""").find(line)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
                val audioGroup = Regex("""AUDIO="([^"]+)"""").find(line)?.groupValues?.getOrNull(1)
                val usesExternalAudio = audioGroup != null && audioGroup in externalAudioGroups
                val nextUri = lines.drop(i + 1).firstOrNull { !it.startsWith("#") }
                val resolved = nextUri?.let { runCatching { URL(base, it).toString() }.getOrNull() }
                if (resolved != null) {
                    if (bw >= bestAnyBw) {
                        bestAnyBw = bw
                        bestAnyUrl = resolved
                    }
                    if (!usesExternalAudio && bw >= bestMuxedBw) {
                        bestMuxedBw = bw
                        bestMuxedUrl = resolved
                    }
                }
            }
            i += 1
        }
        return bestMuxedUrl ?: bestAnyUrl
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
