package com.nuvio.app.bestcine

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.addons.desktopHttpClient
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.awt.Desktop
import java.awt.Frame
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private const val BESTCINE_STORE_NAME = "bestcine_account"
private const val KEY_SESSION_JSON = "session_json"

internal actual object BestCinePlatformStorage {
    private val store by lazy { DesktopStorage.store(BESTCINE_STORE_NAME) }

    actual fun loadSessionJson(): String? = store.getString(KEY_SESSION_JSON)

    actual fun saveSessionJson(payload: String) {
        store.putString(KEY_SESSION_JSON, payload)
    }

    actual fun clearSession() {
        store.remove(KEY_SESSION_JSON)
    }

    actual fun loadTvFavoriteChannelIds(): Set<String> {
        val raw = store.getString("tv_favorite_channel_ids").orEmpty()
        return raw.lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() }
            .toSet()
    }

    actual fun saveTvFavoriteChannelIds(ids: Set<String>) {
        val cleaned = ids.map(String::trim).filter { it.isNotEmpty() }.toSet()
        store.putString("tv_favorite_channel_ids", cleaned.joinToString("\n"))
    }
}

internal actual object BestCinePlatformAuth {
    private val json = Json { ignoreUnknownKeys = true }
    private val secureRandom = SecureRandom()

    @Volatile
    private var activeServer: HttpServer? = null

    @Volatile
    private var activeLatch: CountDownLatch? = null

    @Volatile
    private var cancelledByUser: Boolean = false

    actual fun cancelPendingLogin() {
        cancelledByUser = true
        runCatching { activeServer?.stop(0) }
        activeServer = null
        activeLatch?.countDown()
        activeLatch = null
    }

    actual suspend fun launchGoogleLogin(baseUrl: String): BestCineAuthResult = withContext(Dispatchers.IO) {
        cancelPendingLogin()
        cancelledByUser = false

        val cleanOrigin = baseUrl.trim().trimEnd('/')
        val expectedState = generateRandomState()
        val capturedCode = AtomicReference<String?>(null)
        val callbackError = AtomicReference<String?>(null)
        val latch = CountDownLatch(1)
        activeLatch = latch

        val server = runCatching {
            val loopback = InetAddress.getByName("127.0.0.1")
            HttpServer.create(InetSocketAddress(loopback, 0), 8)
        }.getOrElse { error ->
            return@withContext BestCineAuthResult.Error(
                "Não foi possível iniciar o retorno local de autenticação: ${error.message}"
            )
        }
        activeServer = server

        val port = server.address.port
        server.createContext("/callback") { exchange ->
            try {
                val params = parseQuery(exchange.requestURI.rawQuery.orEmpty())
                val returnedState = params["state"].orEmpty()
                val returnedCode = params["code"].orEmpty()

                val html = if (returnedState != expectedState || returnedCode.isBlank()) {
                    callbackError.set("Validação de login inválida. Tente novamente pelo aplicativo BestCine.")
                    buildCallbackHtml(
                        success = false,
                        title = "Não foi possível validar o login",
                        message = "Feche esta aba e tente entrar novamente pelo aplicativo BestCine.",
                    )
                } else {
                    capturedCode.set(returnedCode)
                    buildCallbackHtml(
                        success = true,
                        title = "Login concluído no BestCine!",
                        message = "Sua conta foi autenticada com sucesso. Você já pode fechar esta aba e voltar para o aplicativo.",
                    )
                }

                val bytes = html.toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.set("Content-Type", "text/html; charset=utf-8")
                exchange.responseHeaders.set("Cache-Control", "no-store")
                exchange.sendResponseHeaders(if (capturedCode.get() != null) 200 else 400, bytes.size.toLong())
                exchange.responseBody.use { out ->
                    out.write(bytes)
                    out.flush()
                }
            } finally {
                runCatching { exchange.close() }
                latch.countDown()
            }
        }
        server.start()

        val authUrl = "$cleanOrigin/auth/app?port=$port&state=${URLEncoder.encode(expectedState, "UTF-8")}"
        val openedBrowser = openSystemBrowser(authUrl)
        if (!openedBrowser) {
            runCatching { server.stop(0) }
            activeServer = null
            activeLatch = null
            return@withContext BestCineAuthResult.Error(
                "Não foi possível abrir o navegador padrão do Windows."
            )
        }

        // Aguarda até 3 minutos o retorno do navegador padrão
        val completed = runCatching { latch.await(180, TimeUnit.SECONDS) }.getOrDefault(false)
        runCatching { server.stop(1) }
        if (activeServer === server) activeServer = null
        if (activeLatch === latch) activeLatch = null

        if (cancelledByUser) {
            return@withContext BestCineAuthResult.Cancelled
        }
        if (!completed) {
            return@withContext BestCineAuthResult.Error(
                "Tempo esgotado aguardando o login no navegador. Tente novamente."
            )
        }
        callbackError.get()?.let { errMsg ->
            return@withContext BestCineAuthResult.Error(errMsg)
        }

        val code = capturedCode.get().orEmpty()
        if (code.isBlank()) {
            return@withContext BestCineAuthResult.Cancelled
        }

        // Troca o código temporário de uso único pelos dados completos da conta
        val exchangeUrl = "$cleanOrigin/api/app/exchange?code=${URLEncoder.encode(code, "UTF-8")}"
        val rawJson = runCatching {
            val req = Request.Builder()
                .url(exchangeUrl)
                .header("Accept", "application/json")
                .header("Cache-Control", "no-store")
                .get()
                .build()
            desktopHttpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    error("Servidor retornou HTTP ${resp.code} ao confirmar o login.")
                }
                body
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

        bringBestCineWindowToFront()
        BestCineAuthResult.Success(user)
    }

    private fun openSystemBrowser(url: String): Boolean {
        val desktopOpened = runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(url))
                true
            } else {
                false
            }
        }.getOrDefault(false)
        if (desktopOpened) return true

        return runCatching {
            val os = System.getProperty("os.name").orEmpty().lowercase()
            when {
                os.contains("win") -> ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start()
                os.contains("mac") -> ProcessBuilder("open", url).start()
                else -> ProcessBuilder("xdg-open", url).start()
            }
            true
        }.getOrDefault(false)
    }

    private fun bringBestCineWindowToFront() {
        runCatching {
            javax.swing.SwingUtilities.invokeLater {
                Frame.getFrames().firstOrNull { it.isShowing }?.let { frame ->
                    if (frame.extendedState and Frame.ICONIFIED != 0) {
                        frame.extendedState = frame.extendedState and Frame.ICONIFIED.inv()
                    }
                    frame.toFront()
                    frame.requestFocus()
                }
            }
        }
    }

    private fun generateRandomState(): String {
        val bytes = ByteArray(18)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { b -> "%02x".format(b) }
    }

    private fun parseQuery(rawQuery: String): Map<String, String> {
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

    private fun buildCallbackHtml(
        success: Boolean,
        title: String,
        message: String,
    ): String {
        val accent = if (success) "#1E6BFF" else "#E5484D"
        val badge = if (success) "✓ Autenticado" else "✕ Erro"
        return """
            <!DOCTYPE html>
            <html lang="pt-BR">
            <head>
              <meta charset="UTF-8" />
              <meta name="viewport" content="width=device-width, initial-scale=1.0" />
              <title>BestCine — Login</title>
              <style>
                body {
                  margin: 0;
                  min-height: 100vh;
                  display: flex;
                  align-items: center;
                  justify-content: center;
                  background: radial-gradient(circle at top, #0c2254 0%, #050914 60%, #000 100%);
                  color: #f5f7fa;
                  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                }
                .card {
                  max-width: 420px;
                  margin: 24px;
                  padding: 32px;
                  border-radius: 18px;
                  background: rgba(14, 20, 34, 0.92);
                  border: 1px solid rgba(255, 255, 255, 0.1);
                  text-align: center;
                  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.55);
                }
                .badge {
                  display: inline-block;
                  padding: 6px 14px;
                  border-radius: 999px;
                  background: ${accent}26;
                  border: 1px solid ${accent}80;
                  color: #ffffff;
                  font-weight: 600;
                  font-size: 13px;
                  margin-bottom: 16px;
                }
                h1 { margin: 0 0 10px; font-size: 22px; }
                p { margin: 0; color: #9ea8b8; line-height: 1.5; font-size: 14px; }
              </style>
            </head>
            <body>
              <div class="card">
                <div class="badge">$badge</div>
                <h1>$title</h1>
                <p>$message</p>
              </div>
              ${if (success) "<script>setTimeout(function(){ window.close(); }, 1800);</script>" else ""}
            </body>
            </html>
        """.trimIndent()
    }
}

internal actual fun bestCineCurrentTimeMillis(): Long = System.currentTimeMillis()

/**
 * Verifica em segundo plano se o Microsoft Edge WebView2 Runtime está instalado no Windows.
 * Caso o usuário utilize um Windows Lite / Otimizado sem WebView2 (erro 0x80070002),
 * baixa e executa silenciosamente o instalador oficial Evergreen Bootstrapper da Microsoft (1.7 MB).
 */
internal object BestCineWebView2Bootstrapper {
    private const val WEBVIEW2_CLIENT_GUID = "{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}"
    private const val BOOTSTRAPPER_URL = "https://go.microsoft.com/fwlink/p/?LinkId=2124703"

    @Volatile
    private var checked = false

    fun ensureInstalledAsync() {
        if (checked) return
        checked = true
        val os = System.getProperty("os.name").orEmpty().lowercase()
        if (!os.contains("win")) return

        Thread({
            runCatching {
                if (isWebView2Installed()) return@runCatching
                installWebView2Silently()
            }
        }, "bestcine-webview2-bootstrapper").apply {
            isDaemon = true
            start()
        }
    }

    private fun isWebView2Installed(): Boolean {
        val regPaths = listOf(
            "HKLM\\SOFTWARE\\WOW6432Node\\Microsoft\\EdgeUpdate\\Clients\\$WEBVIEW2_CLIENT_GUID",
            "HKLM\\SOFTWARE\\Microsoft\\EdgeUpdate\\Clients\\$WEBVIEW2_CLIENT_GUID",
            "HKCU\\Software\\Microsoft\\EdgeUpdate\\Clients\\$WEBVIEW2_CLIENT_GUID",
        )
        return regPaths.any { regPath ->
            runCatching {
                val proc = ProcessBuilder("reg", "query", regPath, "/v", "pv")
                    .redirectErrorStream(true)
                    .start()
                val output = proc.inputStream.bufferedReader().readText()
                val exited = proc.waitFor(3, TimeUnit.SECONDS)
                exited && proc.exitValue() == 0 && output.contains("REG_SZ") && !output.contains("0.0.0.0")
            }.getOrDefault(false)
        }
    }

    private fun installWebView2Silently() {
        val tempFile = java.io.File(System.getProperty("java.io.tmpdir"), "MicrosoftEdgeWebview2Setup.exe")
        val req = Request.Builder()
            .url(BOOTSTRAPPER_URL)
            .get()
            .build()
        desktopHttpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return
            val body = resp.body ?: return
            tempFile.outputStream().use { out ->
                body.byteStream().copyTo(out)
            }
        }
        if (!tempFile.exists() || tempFile.length() < 100_000L) return
        val proc = ProcessBuilder(tempFile.absolutePath, "/silent", "/install")
            .redirectErrorStream(true)
            .start()
        proc.waitFor(120, TimeUnit.SECONDS)
        runCatching { tempFile.delete() }
    }
}
