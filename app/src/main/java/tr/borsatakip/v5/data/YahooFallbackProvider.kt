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
import tr.borsatakip.v5.model.Candle
import tr.borsatakip.v5.model.Stock
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Yalnızca yedek/gecikmeli veri sağlayıcısıdır. Ana sağlayıcı başarısız olduğunda ve kullanıcı
 * Ayarlar'da yedeği açık bıraktığında devreye girer. Canlı veri olarak etiketlenmez.
 *
 * Önemli: Sabit 28 hisselik bir evren kullanmaz. Yalnızca ana sağlayıcıdan daha önce başarıyla
 * alınmış dinamik BIST sembol önbelleğini kullanır. Böylece yedek kaynak da ana sağlayıcının
 * gerçek sembol evrenine bağlı kalır.
 */
class YahooFallbackProvider(context: Context) : MarketDataProvider {
    private val settings = SettingsStore(context)
    override val id = "yahoo_fallback"
    override val displayName = "Yahoo Finance • YEDEK / GECİKMELİ"

    override suspend fun scan(onProgress: (done: Int, total: Int) -> Unit): List<Stock> = coroutineScope {
        val symbols = settings.cachedBistSymbols.toList().sorted()
        require(symbols.isNotEmpty()) {
            "Dinamik BIST sembol önbelleği boş. Önce ana mobil veri sağlayıcısından /v1/bist/symbols alınmalıdır."
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

    private fun fetch(symbol: String): Stock? {
        val encoded = URLEncoder.encode("$symbol.IS", "UTF-8")
        val con = URL("https://query1.finance.yahoo.com/v8/finance/chart/$encoded?range=1y&interval=1d&events=history")
            .openConnection() as HttpURLConnection
        con.connectTimeout = 7000
        con.readTimeout = 7000
        con.setRequestProperty("User-Agent", "Mozilla/5.0 BorsaTakip/5.1.2")
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
            candles += Candle(ts.getLong(i) * 1000, o.getDouble(i), h.getDouble(i), l.getDouble(i), c.getDouble(i), v.getDouble(i))
        }
        if (candles.size < 220) return null
        val meta = r.optJSONObject("meta")
        return Stock(
            symbol = fallback,
            companyName = meta?.optString("longName")?.takeIf { it.isNotBlank() },
            candles = candles,
            source = displayName,
            dataTimestamp = candles.last().timestamp
        )
    }
}
