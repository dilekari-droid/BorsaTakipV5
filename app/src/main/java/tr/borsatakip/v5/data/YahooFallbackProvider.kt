package tr.borsatakip.v5.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONException
import org.json.JSONObject
import tr.borsatakip.v5.BuildConfig
import tr.borsatakip.v5.model.Candle
import tr.borsatakip.v5.model.Stock
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger

/** Deneysel yedek/gecikmeli veri sağlayıcısıdır. Hiçbir zaman REALTIME olarak etiketlenmez. */
class YahooFallbackProvider(context: Context) : MarketDataProvider {
    private val settings = SettingsStore(context.applicationContext)
    override val id = "yahoo_fallback"
    override val displayName = "Yahoo Finance • YEDEK / GECİKMELİ"

    override suspend fun scan(onProgress: (done: Int, total: Int) -> Unit): List<Stock> =
        scanDetailed { _, done, total -> onProgress(done, total) }.successfulStocks

    override suspend fun scanDetailed(
        onProgress: (result: ProviderSymbolResult, done: Int, total: Int) -> Unit
    ): ProviderScanReport = coroutineScope {
        val symbols = loadSymbols()
        require(symbols.isNotEmpty()) {
            "BIST sembol evreni alınamadı. İnternet bağlantısını kontrol edin veya Production backend yapılandırın."
        }

        val cacheFresh = System.currentTimeMillis() - settings.yahooSymbolCacheUpdatedAt in 0..SYMBOL_CACHE_TTL_MS
        val knownUnsupported = if (cacheFresh) settings.yahooUnsupportedSymbols else emptySet()
        if (!cacheFresh) settings.clearYahooSymbolCompatibilityCache()

        val semaphore = Semaphore(MAX_CONCURRENCY)
        val done = AtomicInteger(0)
        val results = symbols.map { symbol ->
            async(Dispatchers.IO) {
                val result = if (symbol in knownUnsupported) {
                    ProviderSymbolResult(
                        symbol = symbol,
                        status = ProviderSymbolStatus.DATA_UNAVAILABLE,
                        attempt = 0,
                        errorMessage = "Yahoo uyumluluk önbelleği: veri yok (TTL dolunca yeniden doğrulanır)"
                    )
                } else {
                    semaphore.withPermit { fetchWithRetry(symbol) }
                }
                updateCompatibilityCache(result)
                val current = done.incrementAndGet()
                Log.i(TAG, "[BIST_SCAN] SYMBOL=$symbol STATUS=${result.status} ATTEMPT=${result.attempt} HTTP=${result.httpCode ?: "-"}")
                onProgress(result, current, symbols.size)
                result
            }
        }.awaitAll()
        ProviderScanReport(symbols.size, results)
    }

    override suspend fun fetchOne(symbol: String): Stock? = withContext(Dispatchers.IO) {
        fetchWithRetry(symbol.trim().uppercase()).stock
    }

    private suspend fun loadSymbols(): List<String> = withContext(Dispatchers.IO) {
        val cached = settings.cachedBistSymbols.map { it.trim().uppercase() }
            .filter { it.matches(SYMBOL_REGEX) }.distinct().sorted()
        if (cached.isNotEmpty()) return@withContext cached
        val discovered = discoverBistSymbols()
        if (discovered.isNotEmpty()) settings.cachedBistSymbols = discovered.toSet()
        discovered
    }

    private fun discoverBistSymbols(): List<String> {
        var con: HttpURLConnection? = null
        return try {
            con = URL("https://m.doviz.com/borsa/hisseler").openConnection() as HttpURLConnection
            con.requestMethod = "GET"
            con.connectTimeout = 8_000
            con.readTimeout = 12_000
            con.instanceFollowRedirects = true
            con.setRequestProperty("Accept", "text/html,application/xhtml+xml")
            con.setRequestProperty("User-Agent", "Mozilla/5.0 BorsaTakip/${BuildConfig.VERSION_NAME} Android")
            if (con.responseCode !in 200..299) return emptyList()
            val html = con.inputStream.bufferedReader().use { it.readText() }
            val regex = Regex("(?:https://borsa\\.doviz\\.com)?/hisseler/([a-z0-9_]{3,12})-", RegexOption.IGNORE_CASE)
            regex.findAll(html).mapNotNull { it.groupValues.getOrNull(1)?.trim()?.uppercase()?.takeIf { s -> s.matches(SYMBOL_REGEX) } }
                .distinct().sorted().toList()
        } catch (_: Exception) {
            emptyList()
        } finally { runCatching { con?.disconnect() } }
    }

    private suspend fun fetchWithRetry(symbol: String): ProviderSymbolResult {
        var last: ProviderSymbolResult? = null
        for (attempt in 1..MAX_ATTEMPTS) {
            var retryDelay = backoffMs(attempt)
            try {
                val stock = withTimeout(SYMBOL_TIMEOUT_MS) { fetchOrThrow(symbol) }
                return ProviderSymbolResult(symbol, ProviderSymbolStatus.SUCCESS, stock, attempt)
            } catch (t: TimeoutCancellationException) {
                last = ProviderSymbolResult(symbol, ProviderSymbolStatus.TIMEOUT, attempt = attempt, errorMessage = "Yahoo isteği zaman aşımına uğradı")
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: DataUnavailableException) {
                return ProviderSymbolResult(symbol, ProviderSymbolStatus.DATA_UNAVAILABLE, attempt = attempt, httpCode = e.httpCode, errorMessage = e.message)
            } catch (e: DataInsufficientException) {
                return ProviderSymbolResult(symbol, ProviderSymbolStatus.DATA_INSUFFICIENT, attempt = attempt, errorMessage = e.message)
            } catch (e: HttpStatusException) {
                val status = if (e.statusCode == 429) ProviderSymbolStatus.RATE_LIMIT else ProviderSymbolStatus.HTTP_ERROR
                last = ProviderSymbolResult(symbol, status, attempt = attempt, httpCode = e.statusCode, errorMessage = e.message)
                if (!isRetryableHttp(e.statusCode)) return last
                retryDelay = maxOf(retryDelay, e.retryAfterMs ?: 0L)
            } catch (e: JSONException) {
                return ProviderSymbolResult(symbol, ProviderSymbolStatus.PARSE_ERROR, attempt = attempt, errorMessage = e.message)
            } catch (e: IOException) {
                last = ProviderSymbolResult(symbol, ProviderSymbolStatus.NETWORK_ERROR, attempt = attempt, errorMessage = e.message)
            } catch (t: Throwable) {
                last = ProviderSymbolResult(symbol, ProviderSymbolStatus.UNKNOWN_ERROR, attempt = attempt, errorMessage = t.message)
            }
            if (attempt < MAX_ATTEMPTS) delay(retryDelay)
        }
        return last ?: ProviderSymbolResult(symbol, ProviderSymbolStatus.UNKNOWN_ERROR, attempt = MAX_ATTEMPTS, errorMessage = "Bilinmeyen Yahoo hatası")
    }

    private fun fetchOrThrow(symbol: String): Stock {
        val encoded = URLEncoder.encode("$symbol.IS", "UTF-8")
        val con = URL("https://query1.finance.yahoo.com/v8/finance/chart/$encoded?range=1y&interval=1d&events=history")
            .openConnection() as HttpURLConnection
        con.connectTimeout = 7_000
        con.readTimeout = 7_000
        con.setRequestProperty("Accept", "application/json")
        con.setRequestProperty("User-Agent", "Mozilla/5.0 BorsaTakip/${BuildConfig.VERSION_NAME} Android")
        try {
            val code = con.responseCode
            if (code == 400 || code == 404) throw DataUnavailableException(code, "Yahoo sembol/veri bulunamadı • HTTP $code")
            if (code !in 200..299) {
                val retryAfterMs = parseRetryAfterMs(con.getHeaderField("Retry-After"))
                throw HttpStatusException(code, retryAfterMs, "HTTP $code")
            }
            val body = con.inputStream.bufferedReader().use { it.readText() }
            return parseOrThrow(body, symbol)
        } finally { con.disconnect() }
    }

    private fun parseOrThrow(json: String, fallback: String): Stock {
        val chart = JSONObject(json).optJSONObject("chart") ?: throw JSONException("Yahoo chart bulunamadı")
        val result = chart.optJSONArray("result")?.optJSONObject(0)
        if (result == null) {
            val error = chart.optJSONObject("error")
            val code = error?.optString("code").orEmpty()
            val description = error?.optString("description").orEmpty()
            if (code.contains("Not Found", true) || description.contains("not found", true) || description.contains("no data", true)) {
                throw DataUnavailableException(null, "Yahoo veri yok: ${description.ifBlank { code.ifBlank { "sembol desteklenmiyor" } }}")
            }
            throw JSONException("Yahoo chart.result bulunamadı${description.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}")
        }
        val ts = result.optJSONArray("timestamp") ?: throw JSONException("timestamp bulunamadı")
        val q = result.optJSONObject("indicators")?.optJSONArray("quote")?.optJSONObject(0)
            ?: throw JSONException("quote bulunamadı")
        val o = q.optJSONArray("open") ?: throw JSONException("open bulunamadı")
        val h = q.optJSONArray("high") ?: throw JSONException("high bulunamadı")
        val l = q.optJSONArray("low") ?: throw JSONException("low bulunamadı")
        val c = q.optJSONArray("close") ?: throw JSONException("close bulunamadı")
        val v = q.optJSONArray("volume") ?: throw JSONException("volume bulunamadı")
        val candles = mutableListOf<Candle>()
        for (i in 0 until ts.length()) {
            if (o.isNull(i) || h.isNull(i) || l.isNull(i) || c.isNull(i) || v.isNull(i)) continue
            val candle = Candle(ts.getLong(i) * 1000, o.getDouble(i), h.getDouble(i), l.getDouble(i), c.getDouble(i), v.getDouble(i))
            if (listOf(candle.open, candle.high, candle.low, candle.close, candle.volume).any { !it.isFinite() }) continue
            if (candle.open <= 0.0 || candle.high <= 0.0 || candle.low <= 0.0 || candle.close <= 0.0 || candle.volume < 0.0) continue
            if (candle.high < maxOf(candle.open, candle.close) || candle.low > minOf(candle.open, candle.close) || candle.high < candle.low) continue
            candles += candle
        }
        val sorted = candles.sortedBy { it.timestamp }.distinctBy { it.timestamp }
        if (sorted.isEmpty()) throw DataUnavailableException(null, "Yahoo geçerli OHLCV verisi döndürmedi")
        if (sorted.size < MIN_CANDLES) throw DataInsufficientException("En az $MIN_CANDLES mum gerekli; ${sorted.size} mum alındı")
        val meta = result.optJSONObject("meta")
        return Stock(
            symbol = fallback,
            companyName = meta?.optString("longName")?.takeIf { it.isNotBlank() },
            candles = sorted,
            source = displayName,
            dataTimestamp = sorted.last().timestamp,
            isRealtime = false,
            delaySeconds = null,
            currentSessionIncluded = false
        )
    }

    @Synchronized
    private fun updateCompatibilityCache(result: ProviderSymbolResult) {
        when (result.status) {
            ProviderSymbolStatus.SUCCESS -> {
                settings.yahooSupportedSymbols = settings.yahooSupportedSymbols + result.symbol
                settings.yahooUnsupportedSymbols = settings.yahooUnsupportedSymbols - result.symbol
                settings.yahooSymbolCacheUpdatedAt = System.currentTimeMillis()
            }
            ProviderSymbolStatus.DATA_UNAVAILABLE -> {
                settings.yahooUnsupportedSymbols = settings.yahooUnsupportedSymbols + result.symbol
                settings.yahooSupportedSymbols = settings.yahooSupportedSymbols - result.symbol
                settings.yahooSymbolCacheUpdatedAt = System.currentTimeMillis()
            }
            else -> Unit
        }
    }

    private fun isRetryableHttp(code: Int): Boolean = code == 408 || code == 429 || code in 500..599

    private fun backoffMs(attempt: Int): Long = when (attempt) {
        1 -> 1_000L
        2 -> 2_500L
        else -> 5_000L
    }

    private fun parseRetryAfterMs(value: String?): Long? {
        val seconds = value?.trim()?.toLongOrNull() ?: return null
        return (seconds * 1_000L).coerceIn(0L, MAX_RETRY_AFTER_MS)
    }

    private class HttpStatusException(
        val statusCode: Int,
        val retryAfterMs: Long?,
        message: String
    ) : IOException(message)

    private class DataUnavailableException(val httpCode: Int?, message: String) : IllegalStateException(message)
    private class DataInsufficientException(message: String) : IllegalStateException(message)

    companion object {
        private const val TAG = "YahooFallback"
        private val SYMBOL_REGEX = Regex("[A-Z0-9_]{3,12}")
        private const val MAX_CONCURRENCY = 2
        private const val MAX_ATTEMPTS = 3
        private const val MIN_CANDLES = 220
        private const val SYMBOL_TIMEOUT_MS = 15_000L
        private const val MAX_RETRY_AFTER_MS = 30_000L
        private const val SYMBOL_CACHE_TTL_MS = 24L * 60L * 60L * 1000L
    }
}
