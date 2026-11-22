package com.nuvio.app.bestcine

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import com.nuvio.app.features.addons.AddonHttpClientProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicReference

private const val BESTCINE_PREFS_NAME = "bestcine_account"
private const val KEY_SESSION_JSON = "session_json"

internal object BestCineAndroidContext {
    @Volatile
    var appContext: Context? = null
        private set

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }
}

internal actual object BestCinePlatformStorage {
    @Volatile
    private var fallbackMemorySession: String? = null

    private fun prefs(): SharedPreferences? =
        BestCineAndroidContext.appContext?.getSharedPreferences(BESTCINE_PREFS_NAME, Context.MODE_PRIVATE)

    actual fun loadSessionJson(): String? =
        prefs()?.getString(KEY_SESSION_JSON, null) ?: fallbackMemorySession

    actual fun saveSessionJson(payload: String) {
        fallbackMemorySession = payload
        prefs()?.edit()?.putString(KEY_SESSION_JSON, payload)?.apply()
    }

    actual fun clearSession() {
        fallbackMemorySession = null
        prefs()?.edit()?.remove(KEY_SESSION_JSON)?.apply()
    }
}

internal actual object BestCinePlatformAuth {
    private val json = Json { ignoreUnknownKeys = true }
    private val secureRandom = SecureRandom()

    @Volatile
    private var activeServerSocket: ServerSocket? = null

    @Volatile
    private var cancelledByUser: Boolean = false

    actual fun cancelPendingLogin() {
        cancelledByUser = true
        runCatching { activeServerSocket?.close() }
        activeServerSocket = null
    }

    actual suspend fun launchGoogleLogin(baseUrl: String): BestCineAuthResult = withContext(Dispatchers.IO) {
        cancelPendingLogin()
        cancelledByUser = false

        val context = BestCineAndroidContext.appContext
            ?: return@withContext BestCineAuthResult.Error("Contexto Android não inicializado.")

        val cleanOrigin = baseUrl.trim().trimEnd('/')
        val expectedState = generateRandomState()
        val capturedCode = AtomicReference<String?>(null)
        val callbackError = AtomicReference<String?>(null)

        val serverSocket = runCatching {
            ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).apply {
                soTimeout = 180_000 // 3 minutos
            }
        }.getOrElse { error ->
            return@withContext BestCineAuthResult.Error(
                "Não foi possível iniciar o servidor local de login: ${error.message}"
            )
        }
        activeServerSocket = serverSocket
        val port = serverSocket.localPort

        val authUrl = "$cleanOrigin/auth/app?port=$port&state=${URLEncoder.encode(expectedState, "UTF-8")}"
        val opened = runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.getOrDefault(false)

        if (!opened) {
            runCatching { serverSocket.close() }
            activeServerSocket = null
            return@withContext BestCineAuthResult.Error("Não foi possível abrir o navegador do Android.")
        }

        try {
            val deadline = System.currentTimeMillis() + 180_000L
            while (!cancelledByUser && capturedCode.get() == null && callbackError.get() == null) {
                val remaining = (deadline - System.currentTimeMillis()).toInt()
                if (remaining <= 0) break
                serverSocket.soTimeout = remaining.coerceAtLeast(1_000)

                val client = try {
                    serverSocket.accept()
                } catch (_: SocketTimeoutException) {
                    break
                } catch (_: Throwable) {
                    break
                }

                client.use { socket ->
                    socket.soTimeout = 5_000
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                    val requestLine = reader.readLine().orEmpty()
                    // Consome cabeçalhos HTTP restantes
                    while (true) {
                        val h = reader.readLine()
                        if (h.isNullOrEmpty()) break
                    }

                    val pathAndQuery = requestLine.split(' ').getOrNull(1).orEmpty()
                    if (pathAndQuery.startsWith("/callback")) {
                        val rawQuery = pathAndQuery.substringAfter('?', "")
                        val params = parseQuery(rawQuery)
                        val returnedState = params["state"].orEmpty()
                        val returnedCode = params["code"].orEmpty()

                        val isSuccess = returnedState == expectedState && returnedCode.isNotBlank()
                        if (isSuccess) {
                            capturedCode.set(returnedCode)
                        } else {
                            callbackError.set("Validação de login inválida. Tente novamente pelo aplicativo BestCine.")
                        }

                        val html = buildCallbackHtml(
                            success = isSuccess,
                            title = if (isSuccess) "Login concluído no BestCine!" else "Não foi possível validar o login",
                            message = if (isSuccess) {
                                "Sua conta foi autenticada com sucesso. Voltando automaticamente para o aplicativo BestCine..."
                            } else {
                                "Feche esta aba e tente entrar novamente pelo aplicativo BestCine."
                            },
                        )
                        val bodyBytes = html.toByteArray(StandardCharsets.UTF_8)
                        val statusLine = if (isSuccess) "HTTP/1.1 200 OK" else "HTTP/1.1 400 Bad Request"
                        val headerBytes = (
                            "$statusLine\r\n" +
                                "Content-Type: text/html; charset=utf-8\r\n" +
                                "Cache-Control: no-store\r\n" +
                                "Content-Length: ${bodyBytes.size}\r\n" +
                                "Connection: close\r\n\r\n"
                            ).toByteArray(StandardCharsets.UTF_8)

                        val out = socket.getOutputStream()
                        out.write(headerBytes)
                        out.write(bodyBytes)
                        out.flush()
                    } else {
                        val notFound = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        socket.getOutputStream().write(notFound.toByteArray(StandardCharsets.UTF_8))
                        socket.getOutputStream().flush()
                    }
                }
            }
        } finally {
            runCatching { serverSocket.close() }
            if (activeServerSocket === serverSocket) activeServerSocket = null
        }

        if (cancelledByUser) {
            return@withContext BestCineAuthResult.Cancelled
        }
        callbackError.get()?.let { errMsg ->
            return@withContext BestCineAuthResult.Error(errMsg)
        }

        val code = capturedCode.get().orEmpty()
        if (code.isBlank()) {
            return@withContext BestCineAuthResult.Error(
                "Tempo esgotado aguardando o login no navegador. Tente novamente."
            )
        }

        val exchangeUrl = "$cleanOrigin/api/app/exchange?code=${URLEncoder.encode(code, "UTF-8")}"
        val rawJson = runCatching {
            val req = Request.Builder()
                .url(exchangeUrl)
                .header("Accept", "application/json")
                .header("Cache-Control", "no-store")
                .get()
                .build()
            AddonHttpClientProvider.get().newCall(req).execute().use { resp ->
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

        BestCineAuthResult.Success(user)
    }

    private fun generateRandomState(): String {
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split('&').mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) return@mapNotNull null
            val k = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
            val v = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
            k to v
        }.toMap()
    }

    private fun buildCallbackHtml(
        success: Boolean,
        title: String,
        message: String,
    ): String {
        val accent = if (success) "#34D399" else "#F87171"
        val icon = if (success) "✓" else "✕"
        val redirectScript = if (success) {
            """
            <script>
              setTimeout(function() {
                window.location.href = "bestcine://auth/bestcine";
              }, 200);
            </script>
            """.trimIndent()
        } else ""
        val backButton = if (success) {
            """<a class="btn" href="bestcine://auth/bestcine">Voltar para o BestCine</a>"""
        } else ""

        return """
            <!DOCTYPE html>
            <html lang="pt-BR">
            <head>
              <meta charset="UTF-8">
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <title>$title</title>
              $redirectScript
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
                p { font-size: 14px; color: #94a3b8; line-height: 1.5; margin: 0 0 20px; }
                .btn {
                  display: inline-block;
                  padding: 12px 24px;
                  border-radius: 12px;
                  background: #2563eb;
                  color: #ffffff;
                  font-weight: 600;
                  text-decoration: none;
                  font-size: 15px;
                }
              </style>
            </head>
            <body>
              <div class="card">
                <div class="badge">$icon</div>
                <h1>$title</h1>
                <p>$message</p>
                $backButton
              </div>
            </body>
            </html>
        """.trimIndent()
    }
}

internal actual fun bestCineCurrentTimeMillis(): Long = System.currentTimeMillis()
