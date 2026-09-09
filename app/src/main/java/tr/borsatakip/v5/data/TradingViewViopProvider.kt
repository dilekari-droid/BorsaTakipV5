package tr.borsatakip.v5.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import tr.borsatakip.v5.BuildConfig
import tr.borsatakip.v5.model.ViopContract
import java.util.concurrent.TimeUnit

/**
 * Deneysel VİOP sağlayıcısı.
 * 1) TradingView symbol-search üzerinden BIST futures sözleşmelerini keşfeder.
 * 2) Bulunan gerçek sözleşme kodlarını TradingView Turkey Scanner'a göndererek
 * son fiyat / günlük değişim / hacim snapshot'ı almaya çalışır.
 * Bu akış resmî TradingView geliştirici API'si değildir. Veri bulunamazsa sahte fiyat üretmez.
 */
class TradingViewViopProvider {

    data class Output(
        val contracts: List<ViopContract>,
        val discovered: Int,
        val snapshots: Int,
        val message: String
    )

    private data class Discovered(
        val symbol: String,
        val base: String,
        val description: String,
        val expiry: String
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    suspend fun load(): Result<Output> = withContext(Dispatchers.IO) {
        runCatching {
            val discovered = LinkedHashMap<String, Discovered>()
            BASE_SYMBOLS.forEach { base -> discoverBase(base).forEach { d -> discovered[d.symbol] = d } }

            if (discovered.isEmpty()) {
                return@runCatching Output(emptyList(), 0, 0, "TradingView Symbol Search BIST futures sözleşmesi döndürmedi.")
            }

            val snapshot = loadSnapshots(discovered.values.toList())
            val now = System.currentTimeMillis()
            val contracts = discovered.values.map { d ->
                val q = snapshot[d.symbol]
                ViopContract(
                    symbol = d.symbol,
                    underlying = d.base,
                    expiry = d.expiry,
                    lastPrice = q?.price,
                    dailyChangePct = q?.change,
                    volume = q?.volume,
                    providerId = "tradingview_viop_experimental",
                    providerLabel = "TradingView VİOP • deneysel",
                    isManual = false,
                    status = if (q != null) "Snapshot alındı" else "Sözleşme bulundu • snapshot veri yok",
                    dataTimestamp = now
                )
            }.sortedWith(compareBy<ViopContract> { it.underlying }.thenBy { it.expiry }.thenBy { it.symbol })

            Output(
                contracts = contracts,
                discovered = contracts.size,
                snapshots = snapshot.size,
                message = "TradingView VİOP: ${contracts.size} sözleşme bulundu • ${snapshot.size} snapshot alındı."
            )
        }
    }

    private fun discoverBase(base: String): List<Discovered> {
        val url = SEARCH_URL.toHttpUrl().newBuilder()
            .addQueryParameter("text", base)
            .addQueryParameter("exchange", "BIST")
            .addQueryParameter("type", "futures")
            .addQueryParameter("lang", "tr")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Origin", "https://www.tradingview.com")
            .header("Referer", "https://www.tradingview.com/")
            .header("User-Agent", USER_AGENT)
            .get()
            .build()

        val body = client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) return emptyList()
            r.body?.string().orEmpty()
        }
        if (body.isBlank()) return emptyList()

        val root = runCatching { JSONArray(body) }.getOrNull()
            ?: runCatching { JSONObject(body).optJSONArray("symbols") }.getOrNull()
            ?: return emptyList()

        val out = LinkedHashMap<String, Discovered>()
        for (i in 0 until root.length()) {
            val item = root.optJSONObject(i) ?: continue
            if (!item.optString("exchange").equals("BIST", ignoreCase = true)) continue
            val itemSymbol = item.optString("symbol").trim().uppercase()

            val nested = item.optJSONArray("contracts")
            if (itemSymbol == base && nested != null) {
                for (j in 0 until nested.length()) {
                    val c = nested.optJSONObject(j) ?: continue
                    addDiscovered(out, c.optString("symbol"), base, c.optString("description"))
                }
            }
            if (itemSymbol.startsWith(base) && item.optString("type", "futures").contains("future", true)) {
                addDiscovered(out, itemSymbol, base, item.optString("description"))
            }
        }
        return out.values.toList()
    }

    private fun addDiscovered(out: MutableMap<String, Discovered>, rawSymbol: String, base: String, description: String) {
        val symbol = rawSymbol.trim().uppercase()
        if (symbol.isBlank() || symbol == base || symbol.endsWith("!")) return
        val expiry = parseExpiry(base, symbol) ?: return
        out[symbol] = Discovered(symbol, base, description, expiry)
    }

    private data class Quote(val price: Double?, val change: Double?, val volume: Double?)

    private fun loadSnapshots(items: List<Discovered>): Map<String, Quote> {
        if (items.isEmpty()) return emptyMap()
        val tickers = JSONArray()
        items.take(MAX_SNAPSHOT_SYMBOLS).forEach { tickers.put("BIST:${it.symbol}") }

        val payload = JSONObject().apply {
            put("columns", JSONArray().apply { put("name"); put("close"); put("change"); put("volume") })
            put("markets", JSONArray().put("turkey"))
            put("symbols", JSONObject().apply {
                put("query", JSONObject().put("types", JSONArray()))
                put("tickers", tickers)
            })
            put("options", JSONObject().put("lang", "tr"))
            put("range", JSONArray().put(0).put(MAX_SNAPSHOT_SYMBOLS))
        }

        val request = Request.Builder()
            .url(SCANNER_URL)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("User-Agent", USER_AGENT)
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()

        val body = client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) return emptyMap()
            r.body?.string().orEmpty()
        }
        val data = runCatching { JSONObject(body).optJSONArray("data") }.getOrNull() ?: return emptyMap()
        val result = LinkedHashMap<String, Quote>()
        for (i in 0 until data.length()) {
            val row = data.optJSONObject(i) ?: continue
            val symbol = row.optString("s").substringAfter("BIST:").trim().uppercase()
            val d = row.optJSONArray("d") ?: continue
            fun num(index: Int): Double? {
                if (index !in 0 until d.length() || d.isNull(index)) return null
                val value = d.opt(index)
                return when (value) {
                    is Number -> value.toDouble()
                    is String -> value.toDoubleOrNull()
                    else -> null
                }?.takeIf { it.isFinite() }
            }
            val q = Quote(num(1), num(2), num(3))
            if (symbol.isNotBlank() && q.price != null) result[symbol] = q
        }
        return result
    }

    private fun parseExpiry(base: String, symbol: String): String? {
        if (!symbol.startsWith(base)) return null
        val suffix = symbol.removePrefix(base)
        if (suffix.length < 5) return null
        val month = MONTHS[suffix[0]] ?: return null
        val year = suffix.substring(1, 5).toIntOrNull() ?: return null
        return "%04d-%02d".format(year, month)
    }

    companion object {
        private const val SEARCH_URL = "https://symbol-search.tradingview.com/symbol_search/"
        private const val SCANNER_URL = "https://scanner.tradingview.com/turkey/scan"
        private val USER_AGENT = "BorsaTakip/${BuildConfig.VERSION_NAME} Android"
        private const val MAX_SNAPSHOT_SYMBOLS = 200
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        val BASE_SYMBOLS = listOf("XU030D", "USDTRYD", "XAUTRYD")
        private val MONTHS = mapOf(
            'F' to 1, 'G' to 2, 'H' to 3, 'J' to 4, 'K' to 5, 'M' to 6,
            'N' to 7, 'Q' to 8, 'U' to 9, 'V' to 10, 'X' to 11, 'Z' to 12
        )
    }
}
