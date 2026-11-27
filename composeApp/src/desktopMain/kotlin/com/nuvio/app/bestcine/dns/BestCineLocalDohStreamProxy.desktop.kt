package com.nuvio.app.bestcine.dns

import co.touchlab.kermit.Logger
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Micro-proxy HTTP local (127.0.0.1) usando BestCineDohDns (Cloudflare/Google DoH IPv4+IPv6).
 * Permite que o libmpv reproduza streams (HLS .m3u8, TS, MP4, MKV) mesmo quando o DNS da operadora
 * no Windows bloqueia o domínio de origem (IPv4 ou IPv6).
 */
internal object BestCineLocalDohStreamProxy {
    private val log = Logger.withTag("BestCineDohProxy")
    private val lock = Any()
    private val tokenLock = Any()

    private const val MAX_URL_TOKENS = 4096

    private val ALLOWED_PROXY_EXTENSIONS = setOf(
        "m3u8", "m3u", "ts", "m4s", "mp4", "m4v", "m4a", "aac", "mp3",
        "vtt", "webvtt", "mkv", "webm", "key", "ogg", "oga", "ogv",
        "mpeg", "mpg", "mov", "flac", "wav", "3gp",
    )

    private val NON_PLAYLIST_EXTENSIONS = setOf(
        "key", "ts", "m4s", "mp4", "m4v", "m4a", "aac", "mp3",
        "vtt", "webvtt", "mkv", "webm", "ogg", "oga", "ogv",
        "mpeg", "mpg", "mov", "flac", "wav", "3gp",
    )

    private data class ProxyTarget(
        val targetUrl: String,
        val sessionId: String,
    )

    // Cache LRU ordenado por acesso: playlists master/variantes consultadas a cada poucos segundos
    // permanecem quentes enquanto segmentos antigos expiram naturalmente sem quebrar o canal ao vivo.
    private val urlTokens = object : LinkedHashMap<String, ProxyTarget>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ProxyTarget>?): Boolean =
            size > MAX_URL_TOKENS
    }

    @Volatile
    private var server: HttpServer? = null

    @Volatile
    private var port: Int = 0

    private val sessionHeaders = ConcurrentHashMap<String, Map<String, String>>()

    private val proxyClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(BestCineDohDns)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    data class PlaybackTarget(
        val url: String,
        val headerLines: List<String>,
    )

    fun resolvePlaybackTarget(
        sourceUrl: String,
        headerLines: List<String>,
    ): PlaybackTarget {
        if (!BestCineDnsSettingsRepository.shouldProxyVideoViaDoh()) {
            return PlaybackTarget(sourceUrl, headerLines)
        }
        val trimmed = sourceUrl.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return PlaybackTarget(sourceUrl, headerLines)
        }
        val parsed = trimmed.toHttpUrlOrNull() ?: return PlaybackTarget(sourceUrl, headerLines)
        val host = parsed.host.lowercase()
        if (host == "localhost" || host == "127.0.0.1" || host == "::1" ||
            host.startsWith("192.168.") || host.startsWith("10.")
        ) {
            return PlaybackTarget(sourceUrl, headerLines)
        }

        val boundPort = ensureStarted() ?: return PlaybackTarget(sourceUrl, headerLines)
        val headersMap = parseHeaderLines(headerLines)
        val sessionId = UUID.randomUUID().toString().replace("-", "").take(16)
        if (sessionHeaders.size > 200) {
            sessionHeaders.clear()
        }
        sessionHeaders[sessionId] = headersMap

        val proxiedUrl = buildProxyUrl(
            boundPort = boundPort,
            targetUrl = trimmed,
            sessionId = sessionId,
            defaultExtension = inferInitialExtension(trimmed),
        )
        return PlaybackTarget(url = proxiedUrl, headerLines = emptyList())
    }

    private fun ensureStarted(): Int? {
        if (server != null && port > 0) return port
        synchronized(lock) {
            if (server != null && port > 0) return port
            return runCatching {
                val loopback = InetAddress.getByName("127.0.0.1")
                val httpServer = HttpServer.create(InetSocketAddress(loopback, 0), 64)
                httpServer.createContext("/p", ::handleProxyRequest)
                httpServer.executor = Executors.newCachedThreadPool { r ->
                    Thread(r, "bestcine-doh-proxy").apply { isDaemon = true }
                }
                httpServer.start()
                server = httpServer
                port = httpServer.address.port
                log.i { "BestCine DoH Stream Proxy iniciado em 127.0.0.1:$port" }
                port
            }.onFailure { err ->
                log.e(err) { "Falha ao iniciar proxy DoH local" }
            }.getOrNull()
        }
    }

    private fun handleProxyRequest(exchange: HttpExchange) {
        try {
            val method = exchange.requestMethod.uppercase()
            if (method != "GET" && method != "HEAD") {
                exchange.sendResponseHeaders(405, -1)
                return
            }

            val resolvedTarget = resolveRequestTarget(exchange.requestURI)
            if (resolvedTarget == null) {
                exchange.sendResponseHeaders(404, -1)
                return
            }
            val targetUrl = resolvedTarget.targetUrl
            val sessionId = resolvedTarget.sessionId

            val reqBuilder = Request.Builder().url(targetUrl)
            if (method == "HEAD") reqBuilder.head() else reqBuilder.get()

            // Aplica headers customizados da stream
            val customHeaders = sessionHeaders[sessionId].orEmpty()
            customHeaders.forEach { (k, v) ->
                if (!k.equals("Host", ignoreCase = true) && !k.equals("Connection", ignoreCase = true)) {
                    reqBuilder.header(k, v)
                }
            }

            // Repassa Range para seek em VOD / MP4 / MKV
            exchange.requestHeaders.getFirst("Range")?.let { range ->
                reqBuilder.header("Range", range)
            }
            if (customHeaders.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                exchange.requestHeaders.getFirst("User-Agent")?.let { ua ->
                    reqBuilder.header("User-Agent", ua)
                }
            }

            proxyClient.newCall(reqBuilder.build()).execute().use { response ->
                val statusCode = response.code
                val contentType = response.header("Content-Type").orEmpty()
                val finalUrl = response.request.url.toString()

                // CDNs como Pluto TV servem chaves binárias AES-128 (.key de 16 bytes) com
                // Content-Type: application/vnd.apple.mpegurl. Só reescrevemos como playlist HLS
                // quando a extensão NÃO é binária E o corpo realmente começa com #EXTM3U!
                val candidatePlaylist = method == "GET" &&
                    response.isSuccessful &&
                    isCandidateHlsPlaylist(targetUrl, finalUrl, contentType)
                if (candidatePlaylist) {
                    val rawBytes = response.body?.bytes() ?: ByteArray(0)
                    if (startsWithExtM3u(rawBytes)) {
                        val playlistText = String(rawBytes, StandardCharsets.UTF_8)
                        val rewritten = rewriteM3u8Playlist(
                            playlist = playlistText,
                            baseUrl = finalUrl,
                            boundPort = port,
                            sessionId = sessionId,
                        )
                        val bytes = rewritten.toByteArray(StandardCharsets.UTF_8)
                        exchange.responseHeaders.set("Content-Type", "application/vnd.apple.mpegurl")
                        exchange.responseHeaders.set("Cache-Control", "no-store")
                        exchange.sendResponseHeaders(statusCode, bytes.size.toLong())
                        exchange.responseBody.use { out ->
                            out.write(bytes)
                            out.flush()
                        }
                        return
                    }
                    // Não era playlist HLS (ex.: chave AES-128 .key servida com mime mpegurl): devolve os bytes intactos!
                    val safeCt = if (contentType.isNotBlank() && !contentType.contains("mpegurl", ignoreCase = true)) {
                        contentType
                    } else {
                        "application/octet-stream"
                    }
                    exchange.responseHeaders.set("Content-Type", safeCt)
                    exchange.sendResponseHeaders(statusCode, rawBytes.size.toLong())
                    exchange.responseBody.use { out ->
                        out.write(rawBytes)
                        out.flush()
                    }
                    return
                }

                // Copia headers essenciais para streaming direto
                listOf("Content-Type", "Content-Range", "Accept-Ranges", "ETag", "Last-Modified").forEach { h ->
                    response.header(h)?.let { v -> exchange.responseHeaders.set(h, v) }
                }

                if (method == "HEAD") {
                    val cl = response.header("Content-Length")?.toLongOrNull() ?: -1L
                    if (cl >= 0) exchange.responseHeaders.set("Content-Length", cl.toString())
                    exchange.sendResponseHeaders(statusCode, -1)
                    return
                }

                val contentLength = response.body?.contentLength() ?: -1L
                val responseLenArg = if (contentLength > 0) contentLength else 0L
                exchange.sendResponseHeaders(statusCode, responseLenArg)

                val input = response.body?.byteStream() ?: return
                exchange.responseBody.use { out ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                    }
                    out.flush()
                }
            }
        } catch (_: IOException) {
            // Cliente (mpv) encerrou conexão ao trocar canal ou fechar — comportamento normal
        } catch (err: Throwable) {
            log.w { "Erro no proxy DoH: ${err.message}" }
            runCatching { exchange.sendResponseHeaders(502, -1) }
        } finally {
            runCatching { exchange.close() }
        }
    }

    private fun resolveRequestTarget(requestUri: URI): ProxyTarget? {
        val rawPath = requestUri.rawPath.orEmpty()
        if (rawPath.startsWith("/p/")) {
            val fileSegment = rawPath.substringAfter("/p/").substringBefore('/')
            val token = fileSegment.substringBefore('.').trim()
            if (token.isNotEmpty()) {
                synchronized(tokenLock) {
                    urlTokens[token]?.let { return it }
                }
            }
        }
        // Compatibilidade com formato legado /p?u=<base64>&s=<session>
        val queryParams = parseQueryParams(requestUri.rawQuery.orEmpty())
        val encodedTarget = queryParams["u"] ?: return null
        val sessionId = queryParams["s"].orEmpty()
        val decoded = runCatching {
            String(Base64.getUrlDecoder().decode(encodedTarget), StandardCharsets.UTF_8)
        }.getOrNull()?.takeIf {
            it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
        } ?: return null
        return ProxyTarget(targetUrl = decoded, sessionId = sessionId)
    }

    private fun isCandidateHlsPlaylist(targetUrl: String, finalUrl: String, contentType: String): Boolean {
        val extTarget = extractUrlExtension(targetUrl)
        val extFinal = extractUrlExtension(finalUrl)
        if (extTarget in NON_PLAYLIST_EXTENSIONS || extFinal in NON_PLAYLIST_EXTENSIONS) {
            return false
        }
        if (extTarget == "m3u8" || extTarget == "m3u" || extFinal == "m3u8" || extFinal == "m3u") {
            return true
        }
        val ct = contentType.lowercase()
        return ct.contains("mpegurl") || ct.contains("x-mpegurl")
    }

    private fun startsWithExtM3u(bytes: ByteArray): Boolean {
        if (bytes.size < 7) return false
        var i = 0
        if (bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            i = 3
        }
        while (i < bytes.size && (bytes[i] == 0x20.toByte() || bytes[i] == 0x09.toByte() || bytes[i] == 0x0D.toByte() || bytes[i] == 0x0A.toByte())) {
            i += 1
        }
        if (i + 7 > bytes.size) return false
        return bytes[i] == '#'.code.toByte() &&
            bytes[i + 1] == 'E'.code.toByte() &&
            bytes[i + 2] == 'X'.code.toByte() &&
            bytes[i + 3] == 'T'.code.toByte() &&
            bytes[i + 4] == 'M'.code.toByte() &&
            bytes[i + 5] == '3'.code.toByte() &&
            bytes[i + 6] == 'U'.code.toByte()
    }

    private fun rewriteM3u8Playlist(
        playlist: String,
        baseUrl: String,
        boundPort: Int,
        sessionId: String,
    ): String {
        val uriAttrRegex = Regex("""URI="([^"]+)"""")
        var previousLineWasStreamInf = false

        return playlist.lineSequence().joinToString("\n") { line ->
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> line
                trimmed.startsWith("#") -> {
                    previousLineWasStreamInf = trimmed.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)
                    val defaultExt = when {
                        trimmed.startsWith("#EXT-X-KEY", ignoreCase = true) -> "key"
                        trimmed.startsWith("#EXT-X-MAP", ignoreCase = true) -> "mp4"
                        else -> "m3u8"
                    }
                    uriAttrRegex.replace(line) { match ->
                        val rawUri = match.groupValues[1]
                        val resolved = resolveAgainstBase(baseUrl, rawUri)
                        if (resolved != null) {
                            """URI="${buildProxyUrl(boundPort, resolved, sessionId, defaultExt)}""""
                        } else {
                            match.value
                        }
                    }
                }
                else -> {
                    val defaultExt = if (previousLineWasStreamInf) "m3u8" else "ts"
                    previousLineWasStreamInf = false
                    val resolved = resolveAgainstBase(baseUrl, trimmed)
                    if (resolved != null) buildProxyUrl(boundPort, resolved, sessionId, defaultExt) else line
                }
            }
        }
    }

    private fun resolveAgainstBase(baseUrl: String, relativeOrAbsolute: String): String? {
        val viaUri = runCatching {
            URI(baseUrl).resolve(relativeOrAbsolute).toString()
        }.getOrNull()
        val resolved = viaUri ?: runCatching {
            URL(URL(baseUrl), relativeOrAbsolute).toString()
        }.getOrNull()
        return resolved?.takeIf {
            it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
        }
    }

    private fun extractUrlExtension(url: String): String? {
        val cleanPath = url.substringBefore('?').substringBefore('#')
        val lastSeg = cleanPath.substringAfterLast('/', "")
        val dotIdx = lastSeg.lastIndexOf('.')
        if (dotIdx <= 0 || dotIdx >= lastSeg.length - 1) return null
        val ext = lastSeg.substring(dotIdx + 1).lowercase()
        return ext.takeIf { it.length in 2..6 && it.all { ch -> ch in 'a'..'z' || ch in '0'..'9' } }
    }

    private fun inferInitialExtension(url: String): String {
        val ext = extractUrlExtension(url)
        if (ext != null && ext in ALLOWED_PROXY_EXTENSIONS) return ext
        return "m3u8"
    }

    /**
     * Gera URL curta e determinística (`/p/<hash16>.<ext>`) para satisfazer:
     * 1) `allowed_segment_extensions` do FFmpeg/libmpv (exige extensão `.ts`, `.m3u8`, `.vtt`, `.m4s`, `.mp4`, `.key` no caminho);
     * 2) Limite de URL (4096 bytes) do demuxer HLS (ex.: Pluto TV envia JWTs de ~2.800 chars que explodiam em Base64);
     * 3) Determinismo entre refreshes de playlist (o FFmpeg reconhece segmentos/chaves já baixados).
     */
    private fun buildProxyUrl(
        boundPort: Int,
        targetUrl: String,
        sessionId: String,
        defaultExtension: String,
    ): String {
        val ext = extractUrlExtension(targetUrl)?.takeIf { it in ALLOWED_PROXY_EXTENSIONS } ?: defaultExtension
        val token = shortStableToken("$sessionId|$targetUrl")
        synchronized(tokenLock) {
            urlTokens[token] = ProxyTarget(targetUrl = targetUrl, sessionId = sessionId)
        }
        return "http://127.0.0.1:$boundPort/p/$token.$ext"
    }

    private fun shortStableToken(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(StandardCharsets.UTF_8))
        val sb = StringBuilder(16)
        for (i in 0 until 8) {
            val b = digest[i].toInt() and 0xFF
            sb.append(HEX_CHARS[b ushr 4])
            sb.append(HEX_CHARS[b and 0x0F])
        }
        return sb.toString()
    }

    private const val HEX_CHARS = "0123456789abcdef"

    private fun parseQueryParams(rawQuery: String): Map<String, String> {
        if (rawQuery.isBlank()) return emptyMap()
        return rawQuery.split('&')
            .mapNotNull { part ->
                val idx = part.indexOf('=')
                if (idx <= 0) return@mapNotNull null
                val k = URLDecoder.decode(part.substring(0, idx), "UTF-8")
                val v = URLDecoder.decode(part.substring(idx + 1), "UTF-8")
                k to v
            }
            .toMap()
    }

    private fun parseHeaderLines(lines: List<String>): Map<String, String> {
        val map = linkedMapOf<String, String>()
        lines.forEach { line ->
            val idx = line.indexOf(':')
            if (idx > 0) {
                val name = line.substring(0, idx).trim()
                val value = line.substring(idx + 1).trim()
                if (name.isNotEmpty() && value.isNotEmpty()) {
                    map[name] = value
                }
            }
        }
        return map
    }
}
