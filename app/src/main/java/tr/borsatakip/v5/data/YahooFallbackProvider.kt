package tr.borsatakip.v5.data

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import tr.borsatakip.v5.BuildConfig
import tr.borsatakip.v5.model.Candle
import tr.borsatakip.v5.model.Stock
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Deneysel yedek/gecikmeli kaynaktır. Hiçbir zaman REALTIME olarak etiketlenmez.
 *
 * Üretim backend'i yokken yalnız kullanıcı deneysel sağlayıcıyı açıkça etkinleştirmişse çalışır.
 * Sembol evreni cache'de yoksa doviz.com BIST hisse listesinden best-effort olarak yenilenir.
 * Bu yol üretim/lisanslı BIST veri sağlayıcısının yerine geçmez.
 */
class YahooFallbackProvider(context: Context) : MarketDataProvider {
    private val settings = SettingsStore(context)
    override val id = "yahoo_fallback"
    override val displayName = "Yahoo Finance • YEDEK / GECİKMELİ"

    override suspend fun scan(onProgress: (done: Int, total: Int) -> Unit): List<Stock> = coroutineScope {
        val symbols = loadExperimentalSymbols()
        require(symbols.isNotEmpty()) {
            "Deneysel BIST sembol evreni alınamadı. Production backend yapılandırın veya ağ bağlantısını kontrol edip tekrar deneyin."
        }

        val semaphore = Semaphore(6)
        var done = 0
        symbols.map { symbol ->
            async(Dispatchers.IO) {
                val stock = semaphore.withPermit { fetch(symbol) }
                synchronized(this@YahooFallbackProvider) {
                    done++
                    onProgress(done, symbols.size)
                }
                stock
            }
        }.awaitAll().filterNotNull()
    }

    override suspend fun fetchOne(symbol: String): Stock? = withContext(Dispatchers.IO) {
        fetch(symbol.trim().uppercase())
    }

    private suspend fun loadExperimentalSymbols(): List<String> = withContext(Dispatchers.IO) {
        val cached = settings.cachedBistSymbols
            .map { it.trim().uppercase() }
            .filter { it.matches(SYMBOL_REGEX) }
            .distinct()
            .sorted()
        if (cached.isNotEmpty()) return@withContext cached

        val discovered = discoverBistSymbolsFromPublicList()
        if (discovered.isNotEmpty()) {
            settings.cachedBistSymbols = discovered.toSet()
            settings.cachedBistSymbolCount = discovered.size
            settings.cachedBistSymbolsFetchedAt = System.currentTimeMillis()
            settings.cachedBistSymbolsProviderId = "doviz.com:bist-universe-experimental"
        }
        discovered
    }

    /**
     * Experimental bootstrap only. No price/quote is taken from this page; it is used only to discover
     * publicly listed BIST ticker codes when there is no production symbol cache yet.
     */
    private fun discoverBistSymbolsFromPublicList(): List<String> {
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
            if (html.isBlank()) return emptyList()

            val regex = Regex("(?:https://borsa\\.doviz\\.com)?/hisseler/([a-z0-9_]{3,12})-", RegexOption.IGNORE_CASE)
            regex.findAll(html)
                .mapNotNull { match ->
                    match.groupValues.getOrNull(1)
                        ?.trim()
                        ?.uppercase()
                        ?.takeIf { it.matches(SYMBOL_REGEX) }
                }
                .distinct()
                .sorted()
                .toList()
        } catch (_: Exception) {
            emptyList()
        } finally {
            runCatching { con?.disconnect() }
        }
    }

    private fun fetch(symbol: String): Stock? {
        val encoded = URLEncoder.encode("$symbol.IS", "UTF-8")
        val con = URL("https://query1.finance.yahoo.com/v8/finance/chart/$encoded?range=1y&interval=1d&events=history")
            .openConnection() as HttpURLConnection
        con.connectTimeout = 7_000
        con.readTimeout = 7_000
        con.setRequestProperty("User-Agent", "Mozilla/5.0 BorsaTakip/${BuildConfig.VERSION_NAME} Android")
        return try {
            if (con.responseCode !in 200..299) return null
            val body = con.inputStream.bufferedReader().use { it.readText() }
            val receivedAt = System.currentTimeMillis()
            val receivedElapsed = SystemClock.elapsedRealtime()
            parse(body, symbol, receivedAt, receivedElapsed)
        } catch (_: Exception) {
            null
        } finally {
            con.disconnect()
        }
    }

    private fun parse(json: String, fallback: String, receivedAt: Long, receivedElapsed: Long): Stock? {
        val r = JSONObject(json).optJSONObject("chart")?.optJSONArray("result")?.optJSONObject(0) ?: return null
        val ts = r.optJSONArray("timestamp") ?: return null
        val q = r.optJSONObject("indicators")?.optJSONArray("quote")?.optJSONObject(0) ?: return null
        val o = q.optJSONArray("open") ?: return null
        val h = q.optJSONArray("high") ?: return null
        val l = q.optJSONArray("low") ?: return null
        val c = q.optJSONArray("close") ?: return null
        val v = q.optJSONArray("volume") ?: return null
        val candles = mutableListOf<Candle>()
        for (i in 0 until ts.length()) {
            if (o.isNull(i) || h.isNull(i) || l.isNull(i) || c.isNull(i) || v.isNull(i)) continue
            val candle = Candle(ts.getLong(i) * 1000, o.getDouble(i), h.getDouble(i), l.getDouble(i), c.getDouble(i), v.getDouble(i))
            if (listOf(candle.open, candle.high, candle.low, candle.close, candle.volume).any { !it.isFinite() }) continue
            if (candle.high < candle.low || candle.close <= 0.0 || candle.volume < 0.0) continue
            candles += candle
        }
        if (candles.size < 220) return null
        val meta = r.optJSONObject("meta")
        return Stock(
            symbol = fallback,
            companyName = meta?.optString("longName")?.takeIf { it.isNotBlank() },
            candles = candles.sortedBy { it.timestamp },
            source = displayName,
            dataTimestamp = candles.maxOf { it.timestamp },
            isRealtime = false,
            delaySeconds = null,
            currentSessionIncluded = false,
            receivedAt = receivedAt,
            receivedElapsedRealtime = receivedElapsed
        )
    }

    companion object {
        private val SYMBOL_REGEX = Regex("[A-Z0-9_]{3,12}")
    }
}
