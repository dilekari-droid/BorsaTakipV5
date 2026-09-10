package tr.borsatakip.v5.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Üretim taraması başlamadan önce backend'in tek preflight sözleşmesini doğrular.
 * Sahte/demo fallback üretmez; config/auth/symbols/quote/history adımlarından biri FAIL ise tarama başlamaz.
 */
class BackendPreflightClient(context: Context) {
    private val settings = SettingsStore(context)

    enum class FailureKind {
        NONE, BACKEND_NOT_CONFIGURED, HTTPS_REQUIRED, AUTH_ERROR, HTTP_ERROR,
        NETWORK_TIMEOUT, DNS_ERROR, TLS_ERROR, EMPTY_DATA, SYMBOLS_ERROR,
        QUOTE_ERROR, HISTORY_ERROR, INVALID_DATA
    }

    data class Result(
        val ok: Boolean,
        val failureKind: FailureKind,
        val message: String,
        val healthOk: Boolean = false,
        val authOk: Boolean = false,
        val symbolsOk: Boolean = false,
        val quoteOk: Boolean = false,
        val historyOk: Boolean = false,
        val symbolCount: Int = 0,
        val sampleSymbol: String? = null,
        val provider: String? = null,
        val elapsedMs: Long = 0L
    )

    suspend fun check(): Result = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val base = settings.baseUrl.trim().removeSuffix("/")
        if (base.isBlank()) return@withContext fail(FailureKind.BACKEND_NOT_CONFIGURED, "Üretim backend adresi tanımlanmamış.", started)
        if (!base.startsWith("https://")) return@withContext fail(FailureKind.HTTPS_REQUIRED, "Üretim backend için HTTPS zorunludur.", started)
        if (settings.apiKey.isBlank()) return@withContext fail(FailureKind.AUTH_ERROR, "Production client API key tanımlanmamış.", started)

        val health = requestJson(base, "/v1/health")
        if (!health.ok) return@withContext health.toResult(started, "Health kontrolü başarısız")
        val healthJson = health.json ?: return@withContext fail(FailureKind.INVALID_DATA, "Health cevabı geçersiz.", started)
        val healthOk = healthJson.optBoolean("ok", false)
        val provider = healthJson.optString("provider").ifBlank { "Production Backend" }
        if (!healthOk) return@withContext fail(FailureKind.HTTP_ERROR, healthJson.optString("message").ifBlank { "Backend ayakta değil." }, started, provider = provider)

        val preflight = requestJson(base, "/v1/preflight")
        if (!preflight.ok) return@withContext preflight.toResult(started, "Production preflight başarısız", healthOk = true, provider = provider)
        val json = preflight.json ?: return@withContext fail(FailureKind.INVALID_DATA, "Preflight cevabı geçersiz.", started, healthOk = true, provider = provider)

        fun check(name: String): JSONObject? = json.optJSONObject(name)
        val authOk = check("authentication")?.optBoolean("ok", false) == true
        val symbolsOk = check("symbols")?.optBoolean("ok", false) == true
        val quoteOk = check("quote")?.optBoolean("ok", false) == true
        val historyOk = check("history")?.optBoolean("ok", false) == true
        val symbolCount = json.optInt("symbolCount", 0)
        val sample = json.optString("sampleSymbol").takeIf { it.isNotBlank() }
        val overall = json.optBoolean("ok", false)

        if (!authOk) return@withContext fail(FailureKind.AUTH_ERROR, check("authentication")?.optString("message").orEmpty().ifBlank { "Authentication başarısız." }, started, true, false, false, false, false, symbolCount, sample, provider)
        if (!symbolsOk || symbolCount <= 0) return@withContext fail(FailureKind.SYMBOLS_ERROR, check("symbols")?.optString("message").orEmpty().ifBlank { "BIST sembol evreni alınamadı." }, started, true, true, false, false, false, symbolCount, sample, provider)
        if (!quoteOk) return@withContext fail(FailureKind.QUOTE_ERROR, check("quote")?.optString("message").orEmpty().ifBlank { "Canlı quote doğrulanamadı." }, started, true, true, true, false, false, symbolCount, sample, provider)
        if (!historyOk) return@withContext fail(FailureKind.HISTORY_ERROR, check("history")?.optString("message").orEmpty().ifBlank { "History doğrulanamadı." }, started, true, true, true, true, false, symbolCount, sample, provider)
        if (!overall) return@withContext fail(FailureKind.INVALID_DATA, "Preflight tüm adımları geçmedi.", started, true, true, true, true, true, symbolCount, sample, provider)

        settings.cachedBistSymbolCount = symbolCount
        settings.lastBackendHealthAt = System.currentTimeMillis()
        settings.lastBackendHealthOk = true

        Result(
            ok = true,
            failureKind = FailureKind.NONE,
            message = "Backend hazır • Health ✓ • Authentication ✓ • Symbols ✓ • Quote ✓ • History ✓",
            healthOk = true,
            authOk = true,
            symbolsOk = true,
            quoteOk = true,
            historyOk = true,
            symbolCount = symbolCount,
            sampleSymbol = sample,
            provider = provider,
            elapsedMs = System.currentTimeMillis() - started
        )
    }

    private data class HttpResult(
        val ok: Boolean,
        val code: Int? = null,
        val json: JSONObject? = null,
        val kind: FailureKind = FailureKind.NONE,
        val detail: String = ""
    )

    private fun requestJson(base: String, path: String): HttpResult {
        var con: HttpURLConnection? = null
        return try {
            con = URL(base + path).openConnection() as HttpURLConnection
            con.requestMethod = "GET"
            con.connectTimeout = 8_000
            con.readTimeout = 15_000
            con.setRequestProperty("Accept", "application/json")
            con.setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
            val code = con.responseCode
            if (code == 401 || code == 403) return HttpResult(false, code, kind = FailureKind.AUTH_ERROR, detail = "Kimlik doğrulama başarısız (HTTP $code).")
            if (code !in 200..299) return HttpResult(false, code, kind = FailureKind.HTTP_ERROR, detail = "Backend HTTP $code hatası döndürdü.")
            val body = con.inputStream.bufferedReader().use { it.readText() }
            if (body.isBlank()) return HttpResult(false, code, kind = FailureKind.EMPTY_DATA, detail = "Backend boş cevap döndürdü.")
            HttpResult(true, code, JSONObject(body))
        } catch (_: SocketTimeoutException) {
            HttpResult(false, kind = FailureKind.NETWORK_TIMEOUT, detail = "Üretim veri servisi zaman aşımına uğradı.")
        } catch (_: UnknownHostException) {
            HttpResult(false, kind = FailureKind.DNS_ERROR, detail = "Backend alan adı çözümlenemedi (DNS).")
        } catch (_: SSLException) {
            HttpResult(false, kind = FailureKind.TLS_ERROR, detail = "Backend TLS/SSL bağlantısı kurulamadı.")
        } catch (e: Exception) {
            HttpResult(false, kind = FailureKind.HTTP_ERROR, detail = e.message ?: "Üretim veri servisine ulaşılamıyor.")
        } finally {
            runCatching { con?.disconnect() }
        }
    }

    private fun HttpResult.toResult(started: Long, prefix: String, healthOk: Boolean = false, provider: String? = null): Result =
        fail(kind.takeIf { it != FailureKind.NONE } ?: FailureKind.HTTP_ERROR, "$prefix • ${detail.ifBlank { "Bağlantı başarısız" }}", started, healthOk = healthOk, provider = provider)

    private fun fail(
        kind: FailureKind,
        message: String,
        started: Long,
        healthOk: Boolean = false,
        authOk: Boolean = false,
        symbolsOk: Boolean = false,
        quoteOk: Boolean = false,
        historyOk: Boolean = false,
        symbolCount: Int = 0,
        sampleSymbol: String? = null,
        provider: String? = null
    ): Result {
        settings.lastBackendHealthAt = System.currentTimeMillis()
        settings.lastBackendHealthOk = false
        return Result(false, kind, message, healthOk, authOk, symbolsOk, quoteOk, historyOk, symbolCount, sampleSymbol, provider, System.currentTimeMillis() - started)
    }
}
