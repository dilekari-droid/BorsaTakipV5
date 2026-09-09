package tr.borsatakip.v5.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import tr.borsatakip.v5.model.Candle
import tr.borsatakip.v5.model.Stock
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger

class MobileMarketDataProvider(context: Context) : MarketDataProvider {
    private val settings = SettingsStore(context)
    override val id = "mobile_backend"
    override val displayName = "Üretim canlı veri servisi"

    override suspend fun scan(onProgress: (done: Int, total: Int) -> Unit): List<Stock> = supervisorScope {
        require(settings.baseUrl.startsWith("https://")) {
            "Canlı veri sağlayıcısı yapılandırılmamış."
        }
        val symbols = withContext(Dispatchers.IO) { loadSymbols() }
        require(symbols.isNotEmpty()) { "BIST sembol listesi alınamadı." }
        Log.i(TAG, "[BIST_SCAN] TOTAL=${symbols.size}")

        val semaphore = Semaphore(8)
        val done = AtomicInteger(0)
        symbols.map { symbol ->
            async(Dispatchers.IO) {
                Log.d(TAG, "[BIST_SCAN] SYMBOL=$symbol DATA_REQUEST")
                val stock = try {
                    semaphore.withPermit {
                        withTimeoutOrNull(15_000) { fetchHistory(symbol) }
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    Log.w(TAG, "[BIST_SCAN] SYMBOL_SKIPPED $symbol ${t.message}")
                    null
                }
                if (stock != null) Log.d(TAG, "[BIST_SCAN] SYMBOL=$symbol DATA_RECEIVED")
                else Log.w(TAG, "[BIST_SCAN] SYMBOL_SKIPPED $symbol")
                val current = done.incrementAndGet()
                onProgress(current, symbols.size)
                stock
            }
        }.awaitAll().filterNotNull()
    }

    override suspend fun fetchOne(symbol: String): Stock? = withContext(Dispatchers.IO) {
        if (!settings.baseUrl.startsWith("https://")) return@withContext null
        withTimeoutOrNull(15_000) { fetchHistory(symbol.trim().uppercase()) }
    }

    private fun loadSymbols(): List<String> {
        val json = getJson("/v1/bist/symbols") ?: return emptyList()
        val items = json.optJSONArray("items") ?: return emptyList()
        val symbols = (0 until items.length())
            .mapNotNull { i -> items.optString(i).trim().uppercase().takeIf { it.matches(Regex("[A-Z0-9]{3,12}")) } }
            .distinct()
        if (symbols.isNotEmpty()) settings.cachedBistSymbols = symbols.toSet()
        return symbols
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
            if (ts <= 0 || listOf(open, high, low, close, volume).any { !it.isFinite() }) continue
            if (high < low || volume < 0.0) continue
            candles += Candle(ts, open, high, low, close, volume)
        }
        if (candles.size < 220) return null
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

    private fun getJson(path: String): JSONObject? {
        val base = settings.baseUrl.trim().removeSuffix("/")
        if (!base.startsWith("https://")) return null
        var con: HttpURLConnection? = null
        return try {
            con = URL(base + path).openConnection() as HttpURLConnection
            con.requestMethod = "GET"
            con.connectTimeout = 8_000
            con.readTimeout = 12_000
            con.setRequestProperty("Accept", "application/json")
            if (settings.apiKey.isNotBlank()) con.setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
            if (con.responseCode !in 200..299) return null
            val body = con.inputStream.bufferedReader().use { it.readText() }
            if (body.isBlank()) return null
            JSONObject(body)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { con?.disconnect() }
        }
    }

    companion object { private const val TAG = "BIST_SCAN" }
}
