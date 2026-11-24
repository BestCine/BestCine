package com.nuvio.app.bestcine

import com.nuvio.app.core.network.createApiHttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import platform.Foundation.NSDate
import platform.Foundation.NSURL
import platform.Foundation.NSUserDefaults
import platform.Foundation.timeIntervalSince1970
import platform.SafariServices.SFSafariViewController
import platform.SafariServices.SFSafariViewControllerDelegateProtocol
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.posix.AF_INET
import platform.posix.POLLIN
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_REUSEADDR
import platform.posix.accept
import platform.posix.bind
import platform.posix.close
import platform.posix.getsockname
import platform.posix.listen
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.recv
import platform.posix.send
import platform.posix.setsockopt
import platform.posix.sockaddr_in
import platform.posix.socklen_tVar
import platform.posix.socket
import kotlin.concurrent.Volatile
import kotlin.random.Random

private const val IOS_KEY_SESSION_JSON = "bestcine_account_session_json"
private const val IOS_KEY_TV_FAVORITES = "bestcine_tv_favorite_channel_ids"

internal actual object BestCinePlatformStorage {
    private val defaults: NSUserDefaults
        get() = NSUserDefaults.standardUserDefaults

    actual fun loadSessionJson(): String? = defaults.stringForKey(IOS_KEY_SESSION_JSON)

    actual fun saveSessionJson(payload: String) {
        defaults.setObject(payload, forKey = IOS_KEY_SESSION_JSON)
    }

    actual fun clearSession() {
        defaults.removeObjectForKey(IOS_KEY_SESSION_JSON)
    }

    actual fun loadTvFavoriteChannelIds(): Set<String> {
        val raw = defaults.stringForKey(IOS_KEY_TV_FAVORITES).orEmpty()
        return raw.lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() }
            .toSet()
    }

    actual fun saveTvFavoriteChannelIds(ids: Set<String>) {
        val cleaned = ids.map(String::trim).filter { it.isNotEmpty() }.toSet()
        defaults.setObject(cleaned.joinToString("\n"), forKey = IOS_KEY_TV_FAVORITES)
    }
}

@OptIn(ExperimentalForeignApi::class)
internal actual object BestCinePlatformAuth {
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var activeServerFd: Int = -1

    @Volatile
    private var cancelledByUser: Boolean = false

    @Volatile
    private var activeSafariVc: SFSafariViewController? = null

    @Volatile
    private var activeDelegate: NSObject? = null

    actual fun cancelPendingLogin() {
        cancelledByUser = true
        val fd = activeServerFd
        activeServerFd = -1
        if (fd >= 0) {
            close(fd)
        }
        dismissSafariController()
    }

    actual suspend fun launchGoogleLogin(baseUrl: String): BestCineAuthResult = withContext(Dispatchers.IO) {
        cancelPendingLogin()
        cancelledByUser = false

        val cleanOrigin = baseUrl.trim().trimEnd('/')
        val expectedState = generateRandomState()

        val bound = openLoopbackListener()
            ?: return@withContext BestCineAuthResult.Error(
                "Não foi possível iniciar o retorno local de login no iOS."
            )
        val serverFd = bound.first
        val port = bound.second
        activeServerFd = serverFd

        val authUrl = "$cleanOrigin/auth/app?port=$port&state=${percentEncode(expectedState)}"
        val nsUrl = NSURL.URLWithString(authUrl)
        if (nsUrl == null) {
            closeServerFd(serverFd)
            return@withContext BestCineAuthResult.Error("URL de autenticação inválida.")
        }

        presentSafariController(nsUrl)

        var capturedCode: String? = null
        var callbackError: String? = null

        try {
            val deadlineMs = bestCineCurrentTimeMillis() + 180_000L
            while (!cancelledByUser && capturedCode == null && callbackError == null && bestCineCurrentTimeMillis() < deadlineMs) {
                val ready = memScoped {
                    val pfd = alloc<pollfd>()
                    pfd.fd = serverFd
                    pfd.events = POLLIN.toShort()
                    pfd.revents = 0
                    poll(pfd.ptr, 1u, 350)
                }
                if (ready <= 0) continue

                val clientFd = accept(serverFd, null, null)
                if (clientFd < 0) {
                    if (cancelledByUser) break
                    continue
                }
                try {
                    val requestText = readHttpRequest(clientFd)
                    val firstLine = requestText.lineSequence().firstOrNull().orEmpty()
                    val pathAndQuery = firstLine.split(' ').getOrNull(1).orEmpty()
                    if (pathAndQuery.startsWith("/callback")) {
                        val rawQuery = pathAndQuery.substringAfter('?', "")
                        val params = parseQuery(rawQuery)
                        val returnedState = params["state"].orEmpty()
                        val returnedCode = params["code"].orEmpty()
                        val isSuccess = returnedState == expectedState && returnedCode.isNotBlank()
                        if (isSuccess) {
                            capturedCode = returnedCode
                        } else {
                            callbackError = "Validação de login inválida. Tente novamente pelo aplicativo BestCine."
                        }
                        val html = buildCallbackHtml(
                            success = isSuccess,
                            title = if (isSuccess) "Login concluído no BestCine!" else "Não foi possível validar o login",
                            message = if (isSuccess) {
                                "Sua conta foi autenticada com sucesso. Voltando automaticamente para o aplicativo..."
                            } else {
                                "Feche esta tela e tente entrar novamente pelo aplicativo BestCine."
                            },
                        )
                        val bodyBytes = html.encodeToByteArray()
                        val statusLine = if (isSuccess) "HTTP/1.1 200 OK" else "HTTP/1.1 400 Bad Request"
                        val responseHeader =
                            "$statusLine\r\n" +
                                "Content-Type: text/html; charset=utf-8\r\n" +
                                "Cache-Control: no-store\r\n" +
                                "Content-Length: ${bodyBytes.size}\r\n" +
                                "Connection: close\r\n\r\n"
                        writeAll(clientFd, responseHeader.encodeToByteArray() + bodyBytes)
                    } else {
                        val notFound = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        writeAll(clientFd, notFound.encodeToByteArray())
                    }
                } finally {
                    close(clientFd)
                }
            }
        } finally {
            closeServerFd(serverFd)
            dismissSafariController()
        }

        if (cancelledByUser) {
            return@withContext BestCineAuthResult.Cancelled
        }
        callbackError?.let { errMsg ->
            return@withContext BestCineAuthResult.Error(errMsg)
        }
        val code = capturedCode.orEmpty()
        if (code.isBlank()) {
            return@withContext BestCineAuthResult.Error(
                "Tempo esgotado aguardando o login no navegador. Tente novamente."
            )
        }

        val exchangeUrl = "$cleanOrigin/api/app/exchange?code=${percentEncode(code)}"
        val rawJson = runCatching {
            val client = createApiHttpClient()
            try {
                val resp = client.get(exchangeUrl) {
                    header("Accept", "application/json")
                    header("Cache-Control", "no-store")
                }
                val body = resp.bodyAsText()
                if (!resp.status.isSuccess()) {
                    error("Servidor retornou HTTP ${resp.status.value} ao confirmar o login.")
                }
                body
            } finally {
                client.close()
            }
        }.getOrElse { err ->
            return@withContext BestCineAuthResult.Error(
                "Falha ao finalizar login no BestCine: ${err.message ?: "erro de conexão"}"
            )
        }

        val parsed = runCatching {
            json.decodeFromString<BestCineMeResponse>(rawJson)
        }.getOrElse { error ->
            return@withContext BestCineAuthResult.Error(
                "Resposta inválida do servidor BestCine: ${error.message}"
            )
        }

        val user = parsed.usuario
        if (!parsed.logado || user == null || user.tokenInstalacao.isBlank()) {
            return@withContext BestCineAuthResult.Error(
                "Não foi possível obter os dados da sua conta BestCine."
            )
        }

        BestCineAuthResult.Success(user)
    }

    private fun closeServerFd(fd: Int) {
        if (activeServerFd == fd) activeServerFd = -1
        if (fd >= 0) close(fd)
    }

    private fun openLoopbackListener(): Pair<Int, Int>? = memScoped {
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        if (fd < 0) return null

        val opt = alloc<IntVar>()
        opt.value = 1
        setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, opt.ptr, sizeOf<IntVar>().convert())

        val addr = alloc<sockaddr_in>()
        addr.sin_len = sizeOf<sockaddr_in>().toUByte()
        addr.sin_family = AF_INET.toUByte()
        addr.sin_port = 0u
        // 127.0.0.1 em network byte order (iOS arm64/x86_64 é little-endian: 0x0100007F)
        addr.sin_addr.s_addr = 0x0100007Fu

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

        if (listen(fd, 8) != 0) {
            close(fd)
            return null
        }

        val netPort = bound.sin_port.toInt()
        val hostPort = ((netPort and 0xFF) shl 8) or ((netPort ushr 8) and 0xFF)
        if (hostPort <= 0) {
            close(fd)
            return null
        }
        fd to hostPort
    }

    private fun readHttpRequest(clientFd: Int): String {
        val buf = ByteArray(4096)
        val n = buf.usePinned { pinned ->
            recv(clientFd, pinned.addressOf(0), buf.size.convert(), 0).toInt()
        }
        if (n <= 0) return ""
        return buf.copyOf(n).decodeToString()
    }

    private fun writeAll(clientFd: Int, data: ByteArray) {
        if (data.isEmpty()) return
        data.usePinned { pinned ->
            var offset = 0
            while (offset < data.size) {
                val sent = send(
                    clientFd,
                    pinned.addressOf(offset),
                    (data.size - offset).convert(),
                    0,
                ).toInt()
                if (sent <= 0) break
                offset += sent
            }
        }
    }

    private fun presentSafariController(url: NSURL) {
        dispatch_async(dispatch_get_main_queue()) {
            val topVc = findTopViewController()
            if (topVc == null) {
                UIApplication.sharedApplication.openURL(url, emptyMap<Any?, Any>(), null)
                return@dispatch_async
            }
            val safari = SFSafariViewController(uRL = url)
            val delegate = object : NSObject(), SFSafariViewControllerDelegateProtocol {
                override fun safariViewControllerDidFinish(controller: SFSafariViewController) {
                    if (activeSafariVc === controller) {
                        activeSafariVc = null
                        activeDelegate = null
                        cancelledByUser = true
                        val fd = activeServerFd
                        activeServerFd = -1
                        if (fd >= 0) close(fd)
                    }
                }
            }
            safari.delegate = delegate
            activeSafariVc = safari
            activeDelegate = delegate
            topVc.presentViewController(safari, animated = true, completion = null)
        }
    }

    private fun dismissSafariController() {
        dispatch_async(dispatch_get_main_queue()) {
            val safari = activeSafariVc
            activeSafariVc = null
            activeDelegate = null
            safari?.dismissViewControllerAnimated(true, completion = null)
        }
    }

    private fun findTopViewController(): UIViewController? {
        val app = UIApplication.sharedApplication
        val window = app.connectedScenes
            .mapNotNull { it as? UIWindowScene }
            .flatMap { scene -> scene.windows.mapNotNull { it as? UIWindow } }
            .firstOrNull { it.isKeyWindow() }
            ?: app.keyWindow
        var top = window?.rootViewController
        while (top?.presentedViewController != null) {
            top = top.presentedViewController
        }
        return top
    }

    private fun generateRandomState(): String {
        val bytes = Random.nextBytes(16)
        return bytes.joinToString("") { b ->
            (b.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
    }

    private fun percentEncode(value: String): String {
        val hex = "0123456789ABCDEF"
        return buildString {
            value.encodeToByteArray().forEach { byte ->
                val v = byte.toInt() and 0xFF
                val c = v.toChar()
                if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' || c == '.' || c == '~') {
                    append(c)
                } else {
                    append('%')
                    append(hex[v ushr 4])
                    append(hex[v and 0x0F])
                }
            }
        }
    }

    private fun percentDecode(value: String): String {
        val out = mutableListOf<Byte>()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '+') {
                out.add(' '.code.toByte())
                i++
            } else if (c == '%' && i + 2 < value.length) {
                val hex = value.substring(i + 1, i + 3).toIntOrNull(16)
                if (hex != null) {
                    out.add(hex.toByte())
                    i += 3
                } else {
                    out.add(c.code.toByte())
                    i++
                }
            } else {
                out.add(c.code.toByte())
                i++
            }
        }
        return out.toByteArray().decodeToString()
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split('&').mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) return@mapNotNull null
            percentDecode(pair.substring(0, idx)) to percentDecode(pair.substring(idx + 1))
        }.toMap()
    }

    private fun buildCallbackHtml(
        success: Boolean,
        title: String,
        message: String,
    ): String {
        val accent = if (success) "#34D399" else "#F87171"
        val icon = if (success) "✓" else "✕"
        return """
            <!DOCTYPE html>
            <html lang="pt-BR">
            <head>
              <meta charset="UTF-8">
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <title>$title</title>
              <style>
                body {
                  margin: 0;
                  min-height: 100vh;
                  display: flex;
                  align-items: center;
                  justify-content: center;
                  background: radial-gradient(circle at top, #0f1b33 0%, #070b14 70%);
                  color: #f8fafc;
                  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                  padding: 16px;
                  box-sizing: border-box;
                }
                .card {
                  max-width: 420px;
                  width: 100%;
                  padding: 32px 24px;
                  border-radius: 20px;
                  background: rgba(15, 23, 42, 0.92);
                  border: 1px solid rgba(56, 189, 248, 0.25);
                  box-shadow: 0 20px 50px rgba(0, 0, 0, 0.6);
                  text-align: center;
                }
                .badge {
                  width: 56px;
                  height: 56px;
                  border-radius: 50%;
                  margin: 0 auto 18px;
                  display: flex;
                  align-items: center;
                  justify-content: center;
                  font-size: 28px;
                  font-weight: bold;
                  color: $accent;
                  background: rgba(255, 255, 255, 0.06);
                  border: 2px solid $accent;
                }
                h1 { font-size: 20px; margin: 0 0 10px; }
                p { font-size: 14px; color: #94a3b8; line-height: 1.5; margin: 0; }
              </style>
            </head>
            <body>
              <div class="card">
                <div class="badge">$icon</div>
                <h1>$title</h1>
                <p>$message</p>
              </div>
            </body>
            </html>
        """.trimIndent()
    }
}

internal actual fun bestCineCurrentTimeMillis(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()
