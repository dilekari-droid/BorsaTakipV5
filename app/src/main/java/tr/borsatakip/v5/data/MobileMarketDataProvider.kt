package tr.borsatakip.v5.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import tr.borsatakip.v5.model.Candle
import tr.borsatakip.v5.model.Stock
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger

class MobileMarketDataProvider(context: Context) : MarketDataProvider {
    private val settings = SettingsStore(context)
    override val id = "mobile_backend"
    override val displayName = "Üretim canlı veri servisi"

    override suspend fun scan(onProgress: (done: Int, total: Int) -> Unit): List<Stock> =
        scanDetailed { _, done, total -> onProgress(done, total) }.successfulStocks

    override suspend fun scanDetailed(
        onProgress: (result: ProviderSymbolResult, done: Int, total: Int) -> Unit
    ): ProviderScanReport = supervisorScope {
        require(settings.baseUrl.startsWith("https://")) {
            "Canlı veri sağlayıcısı yapılandırılmamış."
        }
        val symbols = withContext(Dispatchers.IO) { loadSymbols() }
        require(symbols.isNotEmpty()) { "BIST sembol listesi alınamadı." }
        Log.i(TAG, "[BIST_SCAN] TOTAL=${symbols.size}")

        val semaphore = Semaphore(MAX_CONCURRENCY)
        val done = AtomicInteger(0)
        val results = symbols.map { symbol ->
            async(Dispatchers.IO) {
                val result = semaphore.withPermit { fetchWithRetry(symbol) }
                val current = done.incrementAndGet()
                Log.d(TAG, "[BIST_SCAN] SYMBOL=$symbol TERMINAL=${result.status} ATTEMPT=${result.attempt}")
                onProgress(result, current, symbols.size)
                result
            }
        }.awaitAll()

        ProviderScanReport(total = symbols.size, results = results)
    }

    override suspend fun fetchOne(symbol: String): Stock? = withContext(Dispatchers.IO) {
        if (!settings.baseUrl.startsWith("https://")) return@withContext null
        fetchWithRetry(symbol.trim().uppercase()).stock
    }

    private suspend fun fetchWithRetry(symbol: String): ProviderSymbolResult {
        var last: ProviderSymbolResult? = null
        for (attempt in 1..MAX_ATTEMPTS) {
            try {
                Log.d(TAG, "[BIST_SCAN] SYMBOL=$symbol DATA_REQUEST attempt=$attempt")
                val stock = withTimeout(SYMBOL_TIMEOUT_MS) { fetchHistory(symbol) }
                return ProviderSymbolResult(
                    symbol = symbol,
                    status = ProviderSymbolStatus.SUCCESS,
                    stock = stock,
                    attempt = attempt
                )
            } catch (t: TimeoutCancellationException) {
                last = ProviderSymbolResult(
                    symbol = symbol,
                    status = ProviderSymbolStatus.TIMEOUT,
                    attempt = attempt,
                    errorMessage = "${SYMBOL_TIMEOUT_MS / 1000} sn içinde yanıt alınamadı."
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: DataInsufficientException) {
                return ProviderSymbolResult(
                    symbol = symbol,
                    status = ProviderSymbolStatus.DATA_INSUFFICIENT,
                    attempt = attempt,
                    errorMessage = e.message
                )
            } catch (e: HttpStatusException) {
                val status = if (e.statusCode == 429) ProviderSymbolStatus.RATE_LIMIT else ProviderSymbolStatus.HTTP_ERROR
                last = ProviderSymbolResult(
                    symbol = symbol,
                    status = status,
                    attempt = attempt,
                    httpCode = e.statusCode,
                    errorMessage = e.message
                )
                if (!isRetryableHttp(e.statusCode)) return last
            } catch (e: JSONException) {
                return ProviderSymbolResult(
                    symbol = symbol,
                    status = ProviderSymbolStatus.PARSE_ERROR,
                    attempt = attempt,
                    errorMessage = e.message ?: "JSON ayrıştırma hatası"
                )
            } catch (e: IOException) {
                last = ProviderSymbolResult(
                    symbol = symbol,
                    status = ProviderSymbolStatus.NETWORK_ERROR,
                    attempt = attempt,
                    errorMessage = e.message ?: "Ağ hatası"
                )
            } catch (t: Throwable) {
                last = ProviderSymbolResult(
                    symbol = symbol,
                    status = ProviderSymbolStatus.UNKNOWN_ERROR,
                    attempt = attempt,
                    errorMessage = t.message ?: t.javaClass.simpleName
                )
            }

            if (attempt < MAX_ATTEMPTS) delay(backoffMs(attempt))
        }
        return last ?: ProviderSymbolResult(
            symbol = symbol,
            status = ProviderSymbolStatus.UNKNOWN_ERROR,
            attempt = MAX_ATTEMPTS,
            errorMessage = "Bilinmeyen veri alma hatası"
        )
    }

    private fun loadSymbols(): List<String> {
        val json = getJsonOrThrow("/v1/bist/symbols")
        val items = json.optJSONArray("items") ?: throw JSONException("items alanı bulunamadı")
        val symbols = (0 until items.length())
            .mapNotNull { i -> items.optString(i).trim().uppercase().takeIf { it.matches(Regex("[A-Z0-9]{3,12}")) } }
            .distinct()
        if (symbols.isNotEmpty()) settings.cachedBistSymbols = symbols.toSet()
        return symbols
    }

    private fun fetchHistory(symbol: String): Stock {
        val encoded = URLEncoder.encode(symbol, "UTF-8")
        val json = getJsonOrThrow("/v1/bist/history/$encoded?range=1y&interval=1d")
        val candlesArray = json.optJSONArray("candles") ?: throw JSONException("candles alanı bulunamadı")
        val candles = mutableListOf<Candle>()
        for (i in 0 until candlesArray.length()) {
            val x = candlesArray.optJSONObject(i) ?: continue
            val ts = x.optLong("timestamp", 0L)
            val open = x.optDouble("open", Double.NaN)
            val high = x.optDouble("high", Double.NaN)
            val low = x.optDouble("low", Double.NaN)
            val close = x.optDouble("close", Double.NaN)
            val volume = x.optDouble("volume", Double.NaN)
            if (ts <= 0 || listOf(open, high, low, close, volume).any { !it.isFinite() }) continue
            if (high < low || close <= 0.0 || volume < 0.0) continue
            candles += Candle(ts, open, high, low, close, volume)
        }
        if (candles.size < MIN_CANDLES) {
            throw DataInsufficientException("Teknik analiz için en az $MIN_CANDLES mum gerekli; ${candles.size} mum alındı.")
        }
        val sorted = candles.sortedBy { it.timestamp }
        val delaySeconds = if (json.has("delaySeconds") && !json.isNull("delaySeconds")) {
            json.optInt("delaySeconds", Int.MAX_VALUE).takeIf { it != Int.MAX_VALUE }
        } else null
        return Stock(
            symbol = json.optString("symbol").ifBlank { symbol },
            companyName = json.optString("name").takeIf { it.isNotBlank() },
            candles = sorted,
            source = json.optString("source").ifBlank { displayName },
            dataTimestamp = json.optLong("dataTimestamp", 0L),
            isRealtime = json.optBoolean("realtime", false),
            delaySeconds = delaySeconds,
            currentSessionIncluded = json.optBoolean("currentSessionIncluded", false)
        )
    }

    private fun getJsonOrThrow(path: String): JSONObject {
        val base = settings.baseUrl.trim().removeSuffix("/")
        require(base.startsWith("https://")) { "HTTPS backend yapılandırılmamış." }
        var con: HttpURLConnection? = null
        try {
            con = URL(base + path).openConnection() as HttpURLConnection
            con.requestMethod = "GET"
            con.connectTimeout = CONNECT_TIMEOUT_MS
            con.readTimeout = READ_TIMEOUT_MS
            con.setRequestProperty("Accept", "application/json")
            if (settings.apiKey.isNotBlank()) con.setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
            val code = con.responseCode
            if (code !in 200..299) throw HttpStatusException(code, "HTTP $code")
            val body = con.inputStream.bufferedReader().use { it.readText() }
            if (body.isBlank()) throw JSONException("Boş JSON yanıtı")
            return JSONObject(body)
        } finally {
            runCatching { con?.disconnect() }
        }
    }

    private fun isRetryableHttp(code: Int): Boolean = code == 408 || code == 429 || code in 500..599

    private fun backoffMs(attempt: Int): Long = when (attempt) {
        1 -> 500L
        2 -> 1_200L
        else -> 2_500L
    }

    private class HttpStatusException(val statusCode: Int, message: String) : IOException(message)
    private class DataInsufficientException(message: String) : IllegalStateException(message)

    companion object {
        private const val TAG = "BIST_SCAN"
        private const val MAX_CONCURRENCY = 8
        private const val MAX_ATTEMPTS = 3
        private const val MIN_CANDLES = 220
        private const val SYMBOL_TIMEOUT_MS = 15_000L
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 12_000
    }
}
