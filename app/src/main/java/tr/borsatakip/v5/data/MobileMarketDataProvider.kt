package tr.borsatakip.v5.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import tr.borsatakip.v5.model.Candle
import tr.borsatakip.v5.model.Stock
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class MobileMarketDataProvider(context: Context) {
    private val settings = SettingsStore(context)

    suspend fun scan(onProgress: (done: Int, total: Int) -> Unit): List<Stock> = coroutineScope {
        require(settings.baseUrl.isNotBlank()) {
            "Mobil veri servisi tanımlı değil. Ayarlar > Veri Sağlayıcı bölümünden HTTPS servis adresini girin."
        }
        val symbols = loadSymbols()
        require(symbols.isNotEmpty()) { "Veri sağlayıcı BIST sembol listesi döndürmedi." }

        val semaphore = Semaphore(8)
        var done = 0
        symbols.map { symbol ->
            async(Dispatchers.IO) {
                val stock = semaphore.withPermit { fetchHistory(symbol) }
                synchronized(this@MobileMarketDataProvider) {
                    done++
                    onProgress(done, symbols.size)
                }
                stock
            }
        }.awaitAll().filterNotNull()
    }

    suspend fun fetchOne(symbol: String): Stock? = withContext(Dispatchers.IO) {
        require(settings.baseUrl.isNotBlank()) { "Mobil veri servisi tanımlı değil." }
        fetchHistory(symbol.trim().uppercase())
    }

    private fun loadSymbols(): List<String> {
        val json = getJson("/v1/bist/symbols") ?: return emptyList()
        val items = json.optJSONArray("items") ?: return emptyList()
        return (0 until items.length())
            .mapNotNull { i -> items.optString(i).trim().uppercase().takeIf { it.matches(Regex("[A-Z0-9]{3,12}")) } }
            .distinct()
    }

    private fun fetchHistory(symbol: String): Stock? {
        val encoded = URLEncoder.encode(symbol, "UTF-8")
        val json = getJson("/v1/bist/history/$encoded?range=1y&interval=1d") ?: return null
        val candlesArray = json.optJSONArray("candles") ?: JSONArray()
        val candles = mutableListOf<Candle>()

        for (i in 0 until candlesArray.length()) {
            val x = candlesArray.optJSONObject(i) ?: continue
            val ts = x.optLong("timestamp", 0L)
            val open = x.optDouble("open", Double.NaN)
            val high = x.optDouble("high", Double.NaN)
            val low = x.optDouble("low", Double.NaN)
            val close = x.optDouble("close", Double.NaN)
            val volume = x.optDouble("volume", Double.NaN)
            if (ts <= 0 || listOf(open, high, low, close, volume).any { it.isNaN() }) continue
            candles += Candle(ts, open, high, low, close, volume)
        }

        if (candles.size < 220) return null
        val source = json.optString("source").ifBlank { "Mobil veri servisi" }
        val sorted = candles.sortedBy { it.timestamp }
        val dataTimestamp = json.optLong("dataTimestamp", sorted.last().timestamp)
        return Stock(
            symbol = json.optString("symbol").ifBlank { symbol },
            companyName = json.optString("name").takeIf { it.isNotBlank() },
            candles = sorted,
            source = source,
            dataTimestamp = dataTimestamp
        )
    }

    private fun getJson(path: String): JSONObject? {
        val base = settings.baseUrl.trim().removeSuffix("/")
        require(base.startsWith("https://")) { "Mobil veri servisi HTTPS olmalıdır." }
        val con = URL(base + path).openConnection() as HttpURLConnection
        con.requestMethod = "GET"
        con.connectTimeout = 8000
        con.readTimeout = 12000
        con.setRequestProperty("Accept", "application/json")
        if (settings.apiKey.isNotBlank()) con.setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
        return try {
            if (con.responseCode !in 200..299) return null
            JSONObject(con.inputStream.bufferedReader().use { it.readText() })
        } catch (_: Exception) {
            null
        } finally {
            con.disconnect()
        }
    }
}
