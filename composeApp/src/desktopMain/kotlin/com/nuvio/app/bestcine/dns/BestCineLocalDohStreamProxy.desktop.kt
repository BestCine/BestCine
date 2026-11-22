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
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
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

        val proxiedUrl = buildProxyUrl(boundPort, trimmed, sessionId)
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

            val queryParams = parseQueryParams(exchange.requestURI.rawQuery.orEmpty())
            val encodedTarget = queryParams["u"]
            val sessionId = queryParams["s"].orEmpty()
            if (encodedTarget.isNullOrBlank()) {
                exchange.sendResponseHeaders(400, -1)
                return
            }

            val targetUrl = runCatching {
                String(Base64.getUrlDecoder().decode(encodedTarget), StandardCharsets.UTF_8)
            }.getOrNull()?.takeIf {
                it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
            } ?: run {
                exchange.sendResponseHeaders(400, -1)
                return
            }

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

                val isHlsPlaylist = isM3u8Playlist(finalUrl, contentType)
                if (isHlsPlaylist && method == "GET" && response.isSuccessful) {
                    val playlistText = response.body?.string().orEmpty()
                    val rewritten = rewriteM3u8Playlist(
                        playlist = playlistText,
                        baseUrl = finalUrl,
                        boundPort = port,
                        sessionId = sessionId,
                    )
                    val bytes = rewritten.toByteArray(StandardCharsets.UTF_8)
                    exchange.responseHeaders.set(
                        "Content-Type",
                        if (contentType.isNotBlank()) contentType else "application/vnd.apple.mpegurl",
                    )
                    exchange.responseHeaders.set("Cache-Control", "no-store")
                    exchange.sendResponseHeaders(statusCode, bytes.size.toLong())
                    exchange.responseBody.use { out ->
                        out.write(bytes)
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

    private fun isM3u8Playlist(url: String, contentType: String): Boolean {
        val ct = contentType.lowercase()
        if (ct.contains("mpegurl") || ct.contains("x-mpegurl")) return true
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return path.endsWith(".m3u8") || path.endsWith(".m3u")
    }

    private fun rewriteM3u8Playlist(
        playlist: String,
        baseUrl: String,
        boundPort: Int,
        sessionId: String,
    ): String {
        val baseUri = runCatching { URI(baseUrl) }.getOrNull() ?: return playlist
        val uriAttrRegex = Regex("""URI="([^"]+)"""")

        return playlist.lineSequence().joinToString("\n") { line ->
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> line
                trimmed.startsWith("#") -> {
                    uriAttrRegex.replace(line) { match ->
                        val rawUri = match.groupValues[1]
                        val resolved = resolveAgainstBase(baseUri, rawUri)
                        if (resolved != null) {
                            """URI="${buildProxyUrl(boundPort, resolved, sessionId)}""""
                        } else {
                            match.value
                        }
                    }
                }
                else -> {
                    val resolved = resolveAgainstBase(baseUri, trimmed)
                    if (resolved != null) buildProxyUrl(boundPort, resolved, sessionId) else line
                }
            }
        }
    }

    private fun resolveAgainstBase(baseUri: URI, relativeOrAbsolute: String): String? =
        runCatching {
            val resolved = baseUri.resolve(relativeOrAbsolute).toString()
            resolved.takeIf {
                it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
            }
        }.getOrNull()

    private fun buildProxyUrl(boundPort: Int, targetUrl: String, sessionId: String): String {
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(targetUrl.toByteArray(StandardCharsets.UTF_8))
        val safeSession = URLEncoder.encode(sessionId, "UTF-8")
        return "http://127.0.0.1:$boundPort/p?u=$encoded&s=$safeSession"
    }

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
