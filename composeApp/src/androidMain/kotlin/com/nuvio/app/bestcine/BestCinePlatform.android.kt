package com.nuvio.app.bestcine

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import com.nuvio.app.features.addons.AddonHttpClientProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.ref.WeakReference
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

    @Volatile
    private var activityRef: WeakReference<Activity>? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    fun bindActivity(activity: Activity) {
        appContext = activity.applicationContext
        activityRef = WeakReference(activity)
    }

    fun unbindActivity(activity: Activity) {
        if (activityRef?.get() === activity) {
            activityRef = null
        }
    }

    fun currentActivity(): Activity? =
        activityRef?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
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

    @Volatile
    private var fallbackMemoryTvFavorites: Set<String> = emptySet()

    actual fun loadTvFavoriteChannelIds(): Set<String> {
        val raw = prefs()?.getString("tv_favorite_channel_ids", null) ?: return fallbackMemoryTvFavorites
        return raw.lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() }
            .toSet()
    }

    actual fun saveTvFavoriteChannelIds(ids: Set<String>) {
        val cleaned = ids.map(String::trim).filter { it.isNotEmpty() }.toSet()
        fallbackMemoryTvFavorites = cleaned
        prefs()?.edit()?.putString("tv_favorite_channel_ids", cleaned.joinToString("\n"))?.apply()
    }
}

internal actual object BestCinePlatformAuth {
    private val json = Json { ignoreUnknownKeys = true }
    private val secureRandom = SecureRandom()

    @Serializable
    private data class AppPollResponse(
        val ready: Boolean = false,
        val code: String? = null,
    )

    @Volatile
    private var activeServerSocket: ServerSocket? = null

    @Volatile
    private var activeExpectedState: String? = null

    @Volatile
    private var activeCapturedCode: AtomicReference<String?>? = null

    @Volatile
    private var cancelledByUser: Boolean = false

    private fun closeSocketAsync(socket: ServerSocket?) {
        if (socket == null) return
        Thread(
            {
                runCatching { socket.close() }
            },
            "bestcine-auth-socket-close",
        ).apply {
            isDaemon = true
            start()
        }
    }

    fun deliverDeepLinkCallback(rawUrl: String) {
        runCatching {
            val rawQuery = rawUrl.substringAfter('?', "")
            if (rawQuery.isBlank()) return
            val params = parseQuery(rawQuery)
            val returnedState = params["state"].orEmpty()
            val returnedCode = params["code"].orEmpty()
            val expected = activeExpectedState
            if (!expected.isNullOrBlank() && returnedState == expected && returnedCode.isNotBlank()) {
                activeCapturedCode?.compareAndSet(null, returnedCode)
                closeSocketAsync(activeServerSocket)
            }
        }
    }

    actual fun cancelPendingLogin() {
        cancelledByUser = true
        activeExpectedState = null
        activeCapturedCode = null
        val socket = activeServerSocket
        activeServerSocket = null
        closeSocketAsync(socket)
    }

    actual suspend fun launchGoogleLogin(baseUrl: String): BestCineAuthResult = withContext(Dispatchers.IO) {
        try {
            launchGoogleLoginInternal(baseUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            BestCineAuthResult.Error(
                "Erro ao concluir login no BestCine: ${t.message ?: t::class.simpleName ?: "erro inesperado"}"
            )
        }
    }

    private suspend fun launchGoogleLoginInternal(baseUrl: String): BestCineAuthResult {
        cancelPendingLogin()
        cancelledByUser = false

        val context = BestCineAndroidContext.appContext
            ?: return BestCineAuthResult.Error("Contexto Android não inicializado.")

        val cleanOrigin = baseUrl.trim().trimEnd('/')
        val expectedState = generateRandomState()
        val capturedCode = AtomicReference<String?>(null)
        val callbackError = AtomicReference<String?>(null)
        activeExpectedState = expectedState
        activeCapturedCode = capturedCode

        val serverSocket = runCatching {
            ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).apply {
                soTimeout = 180_000 // 3 minutos
            }
        }.getOrElse { error ->
            return BestCineAuthResult.Error(
                "Não foi possível iniciar o servidor local de login: ${error.message}"
            )
        }
        activeServerSocket = serverSocket
        val port = serverSocket.localPort

        val authUrl = "$cleanOrigin/auth/app?port=$port&state=${URLEncoder.encode(expectedState, "UTF-8")}&cb=deeplink"
        val opened = withContext(Dispatchers.Main) {
            openAuthInRealBrowser(context, authUrl)
        }

        if (!opened) {
            runCatching { serverSocket.close() }
            activeServerSocket = null
            activeExpectedState = null
            activeCapturedCode = null
            return BestCineAuthResult.Error("Não foi possível abrir o navegador do Android.")
        }

        // Camada tripla de conclusão:
        // 1) Deep link `bestcine://auth/bestcine?code=...&state=...` (LinkActivity)
        // 2) Servidor local `127.0.0.1:<port>/callback`
        // 3) Polling `/api/app/poll?state=<state>` (imune a PWA/WebAPK/MIUI!)
        coroutineScope {
            val pollJob = launch {
                val pollUrl = "$cleanOrigin/api/app/poll?state=${URLEncoder.encode(expectedState, "UTF-8")}"
                delay(1_800L)
                val pollDeadline = System.currentTimeMillis() + 180_000L
                while (isActive && !cancelledByUser && capturedCode.get() == null && callbackError.get() == null && System.currentTimeMillis() < pollDeadline) {
                    runCatching {
                        val req = Request.Builder()
                            .url(pollUrl)
                            .header("Accept", "application/json")
                            .header("Cache-Control", "no-store")
                            .get()
                            .build()
                        AddonHttpClientProvider.get().newCall(req).execute().use { resp ->
                            if (resp.isSuccessful) {
                                val text = resp.body?.string().orEmpty()
                                val parsedPoll = json.decodeFromString<AppPollResponse>(text)
                                if (parsedPoll.ready && !parsedPoll.code.isNullOrBlank()) {
                                    capturedCode.compareAndSet(null, parsedPoll.code)
                                    runCatching { serverSocket.close() }
                                }
                            }
                        }
                    }
                    if (capturedCode.get() != null) break
                    delay(1_500L)
                }
            }

            try {
                val deadline = System.currentTimeMillis() + 180_000L
                while (!cancelledByUser && capturedCode.get() == null && callbackError.get() == null) {
                    val remaining = (deadline - System.currentTimeMillis()).toInt()
                    if (remaining <= 0) break
                    runCatching { serverSocket.soTimeout = remaining.coerceAtLeast(1_000) }
                        .onFailure { break }

                    val client = try {
                        serverSocket.accept()
                    } catch (_: SocketTimeoutException) {
                        break
                    } catch (_: Throwable) {
                        break
                    }

                    // IMPORTANTE: protege contra Broken pipe / Connection reset quando o navegador
                    // dispara fetch()/Image() para 127.0.0.1 e logo em seguida abre bestcine://!
                    runCatching {
                        client.use { socket ->
                            socket.soTimeout = 5_000
                            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                            val requestLine = reader.readLine().orEmpty()
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
                                    capturedCode.compareAndSet(null, returnedCode)
                                } else if (capturedCode.get() == null) {
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
                }
            } finally {
                pollJob.cancel()
                runCatching { serverSocket.close() }
                if (activeServerSocket === serverSocket) activeServerSocket = null
                if (activeExpectedState == expectedState) {
                    activeExpectedState = null
                    activeCapturedCode = null
                }
            }
        }

        if (cancelledByUser) {
            return BestCineAuthResult.Cancelled
        }
        callbackError.get()?.let { errMsg ->
            if (capturedCode.get().isNullOrBlank()) {
                return BestCineAuthResult.Error(errMsg)
            }
        }

        val code = capturedCode.get().orEmpty()
        if (code.isBlank()) {
            return BestCineAuthResult.Error(
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
            return BestCineAuthResult.Error(
                "Falha ao finalizar login no BestCine: ${err.message ?: "erro de conexão"}"
            )
        }

        val parsed = runCatching {
            json.decodeFromString<BestCineMeResponse>(rawJson)
        }.getOrElse { error ->
            return BestCineAuthResult.Error(
                "Resposta inválida do servidor BestCine: ${error.message}"
            )
        }

        val user = parsed.usuario
        if (!parsed.logado || user == null || user.tokenInstalacao.isBlank()) {
            return BestCineAuthResult.Error(
                "Não foi possível obter os dados da sua conta BestCine."
            )
        }

        return BestCineAuthResult.Success(user)
    }

    private fun openAuthInRealBrowser(context: Context, authUrl: String): Boolean {
        val uri = Uri.parse(authUrl)
        val browserPkg = resolveRealBrowserPackage(context)
        val hostActivity = BestCineAndroidContext.currentActivity()
        val launchContext: Context = hostActivity ?: context
        val needsNewTask = hostActivity == null
        if (!browserPkg.isNullOrBlank()) {
            // Abre direto no navegador real via Custom Tab dentro da mesma task da MainActivity
            // (sem FLAG_ACTIVITY_NEW_TASK quando temos Activity). Assim:
            // 1) evita sequestro pelo WebAPK do PWA BestCine;
            // 2) não cria aba avulsa presa no Chrome;
            // 3) fecha automaticamente quando LinkActivity traz MainActivity com CLEAR_TOP!
            val customTabLaunched = runCatching {
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    setPackage(browserPkg)
                    if (needsNewTask) {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    val extras = Bundle()
                    extras.putBinder("android.support.customtabs.extra.SESSION", null)
                    putExtras(extras)
                    putExtra("android.support.customtabs.extra.TOOLBAR_COLOR", 0xFF0A1020.toInt())
                    putExtra("android.support.customtabs.extra.TITLE_VISIBILITY", 1)
                    putExtra("android.support.customtabs.extra.SHARE_STATE", 2)
                }
                launchContext.startActivity(intent)
                true
            }.getOrDefault(false)
            if (customTabLaunched) return true
        }
        return runCatching {
            val fallback = Intent(Intent.ACTION_VIEW, uri).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                if (needsNewTask) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            launchContext.startActivity(fallback)
            true
        }.getOrDefault(false)
    }

    private fun resolveRealBrowserPackage(context: Context): String? {
        val pm = context.packageManager
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://accounts.google.com")).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        val defaultPkg = runCatching {
            pm.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        }.getOrNull()
        if (isValidRealBrowserPackage(defaultPkg)) return defaultPkg

        val preferred = listOf(
            "com.android.chrome",
            "com.chrome.beta",
            "com.sec.android.app.sbrowser",
            "org.mozilla.firefox",
            "com.microsoft.emmx",
            "com.brave.browser",
            "com.opera.browser",
        )
        for (pkg in preferred) {
            val test = Intent(Intent.ACTION_VIEW, Uri.parse("https://accounts.google.com")).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                setPackage(pkg)
            }
            val match = runCatching { pm.resolveActivity(test, 0) }.getOrNull()
            if (match != null) return pkg
        }
        return null
    }

    private fun isValidRealBrowserPackage(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        if (pkg == "android") return false
        if (pkg.startsWith("org.chromium.webapk", ignoreCase = true)) return false
        if (pkg.contains("bestcine", ignoreCase = true)) return false
        return true
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
