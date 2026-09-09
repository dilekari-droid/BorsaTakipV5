package tr.borsatakip.v5.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import tr.borsatakip.v5.BuildConfig
import java.util.concurrent.TimeUnit

/**
 * Experimental TradingView web-session login client.
 * This uses TradingView's website sign-in/session flow, not a documented public developer API.
 * Credentials and resulting session tokens are stored only in Android Keystore-backed storage.
 */
class TradingViewAuthClient(context: Context) {
    private val settings = SettingsStore(context)
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    data class AuthResult(
        val ok: Boolean,
        val username: String?,
        val hasSession: Boolean,
        val hasAuthToken: Boolean,
        val message: String
    )

    suspend fun login(username: String, password: String): AuthResult = withContext(Dispatchers.IO) {
        val user = username.trim()
        if (user.isBlank() || password.isBlank()) {
            return@withContext AuthResult(false, null, false, false, "E-posta/kullanıcı adı ve şifre gerekli.")
        }

        val body = FormBody.Builder()
            .add("username", user)
            .add("password", password)
            .add("remember", "on")
            .build()

        val request = Request.Builder()
            .url(SIGN_IN_URL)
            .header("Accept", "application/json, text/plain, */*")
            .header("Origin", "https://www.tradingview.com")
            .header("Referer", "https://www.tradingview.com/")
            .header("User-Agent", USER_AGENT)
            .post(body)
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: Exception) {
            return@withContext AuthResult(false, user, false, false, "TradingView bağlantısı kurulamadı: ${e.message ?: "ağ hatası"}")
        }

        response.use { r ->
            val responseText = runCatching { r.body?.string().orEmpty() }.getOrDefault("")
            if (r.code == 401 || r.code == 403) {
                return@withContext AuthResult(false, user, false, false, "TradingView girişi reddedildi. Kimlik bilgileri, CAPTCHA veya 2FA kontrolü gerekebilir.")
            }
            if (r.code !in 200..399) {
                return@withContext AuthResult(false, user, false, false, "TradingView giriş servisi HTTP ${r.code} döndürdü.")
            }

            if (responseText.contains("error", ignoreCase = true) &&
                (responseText.contains("credentials", ignoreCase = true) || responseText.contains("password", ignoreCase = true))) {
                return@withContext AuthResult(false, user, false, false, "TradingView kullanıcı adı/e-posta veya şifreyi kabul etmedi.")
            }

            val setCookies = r.headers.values("Set-Cookie")
            val sessionId = cookieValue(setCookies, "sessionid")
            val sessionSign = cookieValue(setCookies, "sessionid_sign")
            if (sessionId.isNullOrBlank()) {
                return@withContext AuthResult(false, user, false, false, "TradingView oturum çerezi alınamadı. CAPTCHA/2FA veya giriş akışı değişmiş olabilir.")
            }

            val cookieHeader = buildString {
                append("sessionid=").append(sessionId)
                if (!sessionSign.isNullOrBlank()) append("; sessionid_sign=").append(sessionSign)
            }
            val validation = validateSession(cookieHeader)
            if (!validation.ok) return@withContext validation.copy(username = user)

            settings.tradingViewUsername = user
            settings.tradingViewPassword = password
            settings.tradingViewSessionId = sessionId
            settings.tradingViewSessionSign = sessionSign.orEmpty()
            settings.tradingViewAuthToken = validationToken.orEmpty()
            settings.tradingViewAuthenticatedAt = System.currentTimeMillis()

            return@withContext AuthResult(
                ok = true,
                username = user,
                hasSession = true,
                hasAuthToken = !validationToken.isNullOrBlank(),
                message = if (!validationToken.isNullOrBlank()) {
                    "TradingView oturumu doğrulandı ve WebSocket auth token bulundu."
                } else {
                    "TradingView oturumu doğrulandı; auth token bulunamadı. Scanner kullanılabilir, WebSocket oturumu ayrıca doğrulanmalı."
                }
            )
        }
    }

    @Volatile
    private var validationToken: String? = null

    suspend fun validateStoredSession(): AuthResult = withContext(Dispatchers.IO) {
        val sessionId = settings.tradingViewSessionId
        if (sessionId.isBlank()) return@withContext AuthResult(false, settings.tradingViewUsername, false, false, "Kayıtlı TradingView oturumu yok.")
        val cookieHeader = buildString {
            append("sessionid=").append(sessionId)
            if (settings.tradingViewSessionSign.isNotBlank()) append("; sessionid_sign=").append(settings.tradingViewSessionSign)
        }
        validateSession(cookieHeader).copy(username = settings.tradingViewUsername)
    }

    private fun validateSession(cookieHeader: String): AuthResult {
        val request = Request.Builder()
            .url("https://www.tradingview.com/")
            .header("Cookie", cookieHeader)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()

        val response = try {
            client.newBuilder().followRedirects(true).build().newCall(request).execute()
        } catch (e: Exception) {
            return AuthResult(false, null, true, false, "TradingView oturumu doğrulanamadı: ${e.message ?: "ağ hatası"}")
        }

        response.use { r ->
            if (!r.isSuccessful) return AuthResult(false, null, true, false, "TradingView oturum doğrulaması HTTP ${r.code} döndürdü.")
            val html = runCatching { r.body?.string().orEmpty() }.getOrDefault("")
            val tokenRegex = Regex("\\\"auth_token\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
            validationToken = tokenRegex.find(html)?.groupValues?.getOrNull(1)
            val loggedInMarker = validationToken != null || html.contains("is_authenticated", ignoreCase = true) || html.contains("pro_plan", ignoreCase = true)
            return if (loggedInMarker) {
                AuthResult(true, null, true, validationToken != null, "TradingView oturumu doğrulandı.")
            } else {
                AuthResult(false, null, true, false, "Oturum çerezi alındı ancak TradingView hesabı doğrulanamadı.")
            }
        }
    }

    fun logoutLocal() {
        settings.clearTradingViewSession()
        settings.tradingViewPassword = ""
        validationToken = null
    }

    private fun cookieValue(headers: List<String>, name: String): String? {
        val prefix = "$name="
        return headers.asSequence()
            .flatMap { it.split(';').asSequence() }
            .map { it.trim() }
            .firstOrNull { it.startsWith(prefix) }
            ?.substringAfter('=')
            ?.takeIf { it.isNotBlank() }
    }

    companion object {
        private const val SIGN_IN_URL = "https://www.tradingview.com/accounts/signin/"
        private val USER_AGENT = "Mozilla/5.0 (Android) BorsaTakip/${BuildConfig.VERSION_NAME}"
    }
}
