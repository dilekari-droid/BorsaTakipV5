package tr.borsatakip.v5.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import tr.borsatakip.v5.BuildConfig
import tr.borsatakip.v5.analysis.TradingViewSnapshotScorer
import tr.borsatakip.v5.model.Opportunity
import java.util.concurrent.TimeUnit

/**
 * TradingView web screener endpoint'inden BIST snapshot alan sağlayıcı.
 * Bu endpoint resmî/garantili geliştirici API'si değildir; gecikme ve erişilebilirlik sağlayıcı koşullarına bağlıdır.
 */
class TradingViewScannerOpportunityProvider {

    data class ScanOutput(
        val opportunities: List<Opportunity>,
        val receivedRows: Int,
        val skippedRows: Int,
        val sourceLabel: String
    )

    sealed class ScanFailure(message: String) : Exception(message) {
        class Network(message: String) : ScanFailure(message)
        class Http(val code: Int) : ScanFailure("TradingView Scanner HTTP $code")
        class InvalidJson(message: String) : ScanFailure(message)
        class Empty : ScanFailure("TradingView BIST tarayıcısı veri döndürmedi.")
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun scan(onProgress: (done: Int, total: Int) -> Unit): Result<ScanOutput> = withContext(Dispatchers.IO) {
        runCatching {
            val columns = listOf(
                "name", "description", "close", "change", "volume", "relative_volume_10d_calc",
                "RSI", "MACD.macd", "MACD.signal", "EMA20", "EMA50", "EMA200", "ATR", "VWMA", "Recommend.All"
            )

            val payload = JSONObject().apply {
                put("columns", JSONArray(columns))
                put("filter", JSONArray().apply {
                    put(JSONObject().apply { put("left", "exchange"); put("operation", "equal"); put("right", "BIST") })
                    put(JSONObject().apply { put("left", "type"); put("operation", "equal"); put("right", "stock") })
                })
                put("options", JSONObject().put("lang", "tr"))
                put("markets", JSONArray().put("turkey"))
                put("range", JSONArray().put(0).put(900))
                put("sort", JSONObject().apply { put("sortBy", "volume"); put("sortOrder", "desc") })
                put("symbols", JSONObject().apply {
                    put("query", JSONObject().put("types", JSONArray()))
                    put("tickers", JSONArray())
                })
            }

            val request = Request.Builder()
                .url(SCANNER_URL)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("User-Agent", USER_AGENT)
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .build()

            val response = try {
                client.newCall(request).execute()
            } catch (e: Exception) {
                throw ScanFailure.Network(e.message ?: "TradingView Scanner bağlantısı kurulamadı.")
            }

            response.use { r ->
                if (!r.isSuccessful) throw ScanFailure.Http(r.code)
                val body = r.body?.string().orEmpty()
                if (body.isBlank()) throw ScanFailure.Empty()

                val json = try { JSONObject(body) } catch (_: Exception) {
                    throw ScanFailure.InvalidJson("TradingView Scanner cevabı geçerli JSON değil.")
                }
                val data = json.optJSONArray("data") ?: throw ScanFailure.Empty()
                if (data.length() == 0) throw ScanFailure.Empty()

                val now = System.currentTimeMillis()
                val opportunities = ArrayList<Opportunity>(data.length())
                var skipped = 0
                onProgress(0, data.length())

                for (i in 0 until data.length()) {
                    val row = data.optJSONObject(i)
                    val values = row?.optJSONArray("d")
                    val rawSymbol = row?.optString("s").orEmpty()
                    val symbol = rawSymbol.substringAfter("BIST:", rawSymbol).trim().uppercase()
                    if (row == null || values == null || symbol.isBlank() || !symbol.matches(Regex("[A-Z0-9]{3,12}"))) {
                        skipped++
                        onProgress(i + 1, data.length())
                        continue
                    }

                    fun num(index: Int): Double? {
                        if (index !in 0 until values.length() || values.isNull(index)) return null
                        val value = values.opt(index)
                        val n = when (value) {
                            is Number -> value.toDouble()
                            is String -> value.toDoubleOrNull()
                            else -> null
                        }
                        return n?.takeIf { it.isFinite() }
                    }

                    fun text(index: Int): String? {
                        if (index !in 0 until values.length() || values.isNull(index)) return null
                        return values.optString(index).takeIf { it.isNotBlank() && it != "null" }
                    }

                    val price = num(2)
                    val change = num(3)
                    if (price == null || change == null || price <= 0.0) {
                        skipped++
                        onProgress(i + 1, data.length())
                        continue
                    }

                    val snapshot = TradingViewSnapshotScorer.Snapshot(
                        symbol = symbol,
                        companyName = text(1) ?: text(0),
                        price = price,
                        changePct = change,
                        volume = num(4),
                        relativeVolume = num(5),
                        rsi = num(6),
                        macd = num(7),
                        macdSignal = num(8),
                        ema20 = num(9),
                        ema50 = num(10),
                        ema200 = num(11),
                        atr = num(12),
                        vwma = num(13),
                        recommendation = num(14),
                        receivedAt = now,
                        source = SOURCE_LABEL
                    )
                    val opportunity = TradingViewSnapshotScorer.score(snapshot)
                    if (opportunity != null) opportunities += opportunity else skipped++
                    onProgress(i + 1, data.length())
                }

                if (opportunities.isEmpty()) throw ScanFailure.Empty()
                ScanOutput(
                    opportunities = opportunities.sortedByDescending { it.finalSignalScore },
                    receivedRows = data.length(),
                    skippedRows = skipped,
                    sourceLabel = SOURCE_LABEL
                )
            }
        }
    }

    companion object {
        private const val SCANNER_URL = "https://scanner.tradingview.com/turkey/scan"
        private val USER_AGENT = "BorsaTakip/${BuildConfig.VERSION_NAME} Android"
        const val SOURCE_LABEL = "TradingView Scanner • gecikme/erişim TradingView koşullarına bağlı"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
