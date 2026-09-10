package tr.borsatakip.v5.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Üretim BIST taraması başlamadan önce backend sözleşmesini uçtan uca doğrular.
 * Gerçek veri yoksa tarama başlatılmaz; hiçbir demo/sahte sembol veya fiyat üretilmez.
 */
class BackendPreflightClient(context: Context) {
    private val settings = SettingsStore(context)

    enum class FailureKind {
        NONE,
        BACKEND_NOT_CONFIGURED,
        HTTPS_REQUIRED,
        AUTH_ERROR,
        HTTP_ERROR,
        NETWORK_TIMEOUT,
        DNS_ERROR,
        TLS_ERROR,
        EMPTY_DATA,
        SYMBOLS_ERROR,
        HISTORY_ERROR,
        INVALID_DATA
    }

    data class Result(
        val ok: Boolean,
        val failureKind: FailureKind,
        val message: String,
        val healthOk: Boolean = false,
        val authOk: Boolean = false,
        val symbolsOk: Boolean = false,
        val historyOk: Boolean = false,
        val symbolCount: Int = 0,
        val sampleSymbol: String? = null,
        val provider: String? = null,
        val elapsedMs: Long = 0L
    )

    suspend fun check(): Result = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val base = settings.baseUrl.trim().removeSuffix("/")
        if (base.isBlank()) {
            return@withContext fail(FailureKind.BACKEND_NOT_CONFIGURED, "Üretim veri sağlayıcısı için backend adresi tanımlanmamış.", started)
        }
        if (!base.startsWith("https://")) {
            return@withContext fail(FailureKind.HTTPS_REQUIRED, "Üretim backend için HTTPS adresi zorunludur.", started)
        }

        val health = requestJson(base, "/v1/health")
        if (!health.ok) return@withContext health.toPreflight(started, "Health kontrolü başarısız")
        val healthJson = health.json ?: return@withContext fail(FailureKind.INVALID_DATA, "Backend health cevabı geçersiz.", started)
        if (!healthJson.optBoolean("ok", false)) {
            return@withContext fail(FailureKind.HTTP_ERROR, healthJson.optString("message").ifBlank { "Üretim veri servisi hazır değil." }, started)
        }
        val provider = healthJson.optString("provider").ifBlank { "Production Backend" }

        val symbolsResponse = requestJson(base, "/v1/bist/symbols")
        if (!symbolsResponse.ok) return@withContext symbolsResponse.toPreflight(started, "BIST sembol listesi alınamadı", healthOk = true, provider = provider)
        val symbolsJson = symbolsResponse.json ?: return@withContext fail(FailureKind.SYMBOLS_ERROR, "Backend cevap verdi ancak BIST sembol listesi alınamadı.", started, healthOk = true, authOk = true, provider = provider)
        val items = symbolsJson.optJSONArray("items") ?: JSONArray()
        val symbols = (0 until items.length())
            .mapNotNull { i -> items.optString(i).trim().uppercase().takeIf { it.matches(Regex("[A-Z0-9_]{3,12}")) } }
            .distinct()
        if (symbols.isEmpty()) {
            return@withContext fail(FailureKind.EMPTY_DATA, "Backend bağlantısı başarılı ancak BIST sembol listesi boş.", started, healthOk = true, authOk = true, provider = provider)
        }

        val sample = symbols.first()
        val encoded = URLEncoder.encode(sample, "UTF-8")
        val historyResponse = requestJson(base, "/v1/bist/history/$encoded?range=1y&interval=1d")
        if (!historyResponse.ok) return@withContext historyResponse.toPreflight(started, "Örnek sembol OHLCV kontrolü başarısız", healthOk = true, authOk = true, symbolsOk = true, symbolCount = symbols.size, sampleSymbol = sample, provider = provider)
        val historyJson = historyResponse.json ?: return@withContext fail(FailureKind.HISTORY_ERROR, "Piyasa verisi cevabı geçersiz.", started, true, true, true, false, symbols.size, sample, provider)
        val candles = historyJson.optJSONArray("candles") ?: JSONArray()
        if (candles.length() == 0) {
            return@withContext fail(FailureKind.EMPTY_DATA, "BIST sembol listesi alındı ancak piyasa OHLCV verisi alınamadı.", started, true, true, true, false, symbols.size, sample, provider)
        }

        settings.cachedBistSymbols = symbols.toSet()
        settings.cachedBistSymbolCount = symbols.size
        settings.cachedBistSymbolsFetchedAt = System.currentTimeMillis()
        settings.cachedBistSymbolsProviderId = symbolsJson.optString("source").ifBlank { provider }
        settings.lastBackendHealthAt = System.currentTimeMillis()
        settings.lastBackendHealthOk = true

        Result(
            ok = true,
            failureKind = FailureKind.NONE,
            message = "Backend hazır • Health ✓ • Authentication ✓ • Symbols ✓ • History ✓",
            healthOk = true,
            authOk = true,
            symbolsOk = true,
            historyOk = true,
            symbolCount = symbols.size,
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
            con.readTimeout = 12_000
            con.setRequestProperty("Accept", "application/json")
            if (settings.apiKey.isNotBlank()) con.setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
            val code = con.responseCode
            if (code == 401 || code == 403) return HttpResult(false, code, kind = FailureKind.AUTH_ERROR, detail = "Backend bağlantısı kuruldu ancak kimlik doğrulama başarısız (HTTP $code).")
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

    private fun HttpResult.toPreflight(
        started: Long,
        prefix: String,
        healthOk: Boolean = false,
        authOk: Boolean = this.kind != FailureKind.AUTH_ERROR,
        symbolsOk: Boolean = false,
        symbolCount: Int = 0,
        sampleSymbol: String? = null,
        provider: String? = null
    ): Result = fail(
        kind.takeIf { it != FailureKind.NONE } ?: FailureKind.HTTP_ERROR,
        "$prefix • ${detail.ifBlank { "Bağlantı başarısız" }}",
        started,
        healthOk,
        authOk,
        symbolsOk,
        false,
        symbolCount,
        sampleSymbol,
        provider
    )

    private fun fail(
        kind: FailureKind,
        message: String,
        started: Long,
        healthOk: Boolean = false,
        authOk: Boolean = false,
        symbolsOk: Boolean = false,
        historyOk: Boolean = false,
        symbolCount: Int = 0,
        sampleSymbol: String? = null,
        provider: String? = null
    ): Result {
        settings.lastBackendHealthAt = System.currentTimeMillis()
        settings.lastBackendHealthOk = false
        return Result(false, kind, message, healthOk, authOk, symbolsOk, historyOk, symbolCount, sampleSymbol, provider, System.currentTimeMillis() - started)
    }
}
