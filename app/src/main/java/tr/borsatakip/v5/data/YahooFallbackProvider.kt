package tr.borsatakip.v5.data

import android.content.Context
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
 * Deneysel yedek/gecikmeli veri sağlayıcısıdır. Hiçbir zaman REALTIME olarak etiketlenmez.
 * Production backend yapılandırılmamışsa ve sembol önbelleği boşsa yalnız sembol evrenini
 * oluşturmak için herkese açık BIST hisse listesinden best-effort ticker keşfi yapar.
 */
class YahooFallbackProvider(context: Context) : MarketDataProvider {
    private val settings = SettingsStore(context)
    override val id = "yahoo_fallback"
    override val displayName = "Yahoo Finance • YEDEK / GECİKMELİ"

    override suspend fun scan(onProgress: (done: Int, total: Int) -> Unit): List<Stock> = coroutineScope {
        val symbols = loadSymbols()
        require(symbols.isNotEmpty()) {
            "BIST sembol evreni alınamadı. İnternet bağlantısını kontrol edin veya Production backend yapılandırın."
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

    private suspend fun loadSymbols(): List<String> = withContext(Dispatchers.IO) {
        val cached = settings.cachedBistSymbols
            .map { it.trim().uppercase() }
            .filter { it.matches(SYMBOL_REGEX) }
            .distinct()
            .sorted()
        if (cached.isNotEmpty()) return@withContext cached

        val discovered = discoverBistSymbols()
        if (discovered.isNotEmpty()) settings.cachedBistSymbols = discovered.toSet()
        discovered
    }

    /**
     * Bu sayfadan fiyat/sinyal alınmaz; yalnız ticker kodları keşfedilir.
     * Fiyat geçmişi Yahoo chart endpoint'inden alınır ve gecikmeli/deneysel olarak etiketlenir.
     */
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
            regex.findAll(html)
                .mapNotNull { it.groupValues.getOrNull(1)?.trim()?.uppercase()?.takeIf { s -> s.matches(SYMBOL_REGEX) } }
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
        con.connectTimeout = 7000
        con.readTimeout = 7000
        con.setRequestProperty("User-Agent", "Mozilla/5.0 BorsaTakip/${BuildConfig.VERSION_NAME} Android")
        return try {
            if (con.responseCode !in 200..299) return null
            parse(con.inputStream.bufferedReader().use { it.readText() }, symbol)
        } catch (_: Exception) {
            null
        } finally {
            con.disconnect()
        }
    }

    private fun parse(json: String, fallback: String): Stock? {
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
        val sorted = candles.sortedBy { it.timestamp }
        val meta = r.optJSONObject("meta")
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

    companion object {
        private val SYMBOL_REGEX = Regex("[A-Z0-9_]{3,12}")
    }
}
