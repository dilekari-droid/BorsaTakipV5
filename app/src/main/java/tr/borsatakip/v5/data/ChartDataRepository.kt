package tr.borsatakip.v5.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import tr.borsatakip.v5.BuildConfig
import tr.borsatakip.v5.model.Candle
import tr.borsatakip.v5.ui.chart.ChartMath
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

enum class ChartPeriod(
    val label: String,
    val range: String,
    val interval: String
) {
    DAY("1G", "1d", "5m"),
    WEEK("1H", "5d", "30m"),
    MONTH("1A", "1mo", "1d"),
    THREE_MONTHS("3A", "3mo", "1d"),
    YEAR("1Y", "1y", "1d")
}

data class ChartDataSeries(
    val symbol: String,
    val candles: List<Candle>,
    val source: String,
    val period: ChartPeriod,
    val dataTimestamp: Long,
    val isRealtime: Boolean,
    val delaySeconds: Int?,
    val currentSessionIncluded: Boolean,
    val rejectedCount: Int = 0,
    val duplicateCount: Int = 0
)

/**
 * Loads real OHLCV for the selected chart period. Production HTTPS backend is primary;
 * Yahoo is an explicitly delayed fallback. No synthetic candle/price/volume is created.
 */
class ChartDataRepository(context: Context) {
    private val settings = SettingsStore(context.applicationContext)

    suspend fun load(symbol: String, period: ChartPeriod): Result<ChartDataSeries> = withContext(Dispatchers.IO) {
        runCatching {
            val normalized = symbol.trim().uppercase()
            require(normalized.matches(Regex("[A-Z0-9_]{3,12}"))) { "Geçersiz sembol" }

            if (settings.baseUrl.startsWith("https://")) {
                runCatching { loadBackend(normalized, period) }
                    .getOrElse { backendError ->
                        if (settings.experimentalProvidersEnabled && settings.yahooFallbackEnabled) {
                            loadYahoo(normalized, period)
                        } else throw backendError
                    }
            } else {
                require(settings.experimentalProvidersEnabled && settings.yahooFallbackEnabled) {
                    "Grafik veri kaynağı yapılandırılmamış."
                }
                loadYahoo(normalized, period)
            }
        }
    }

    private fun loadBackend(symbol: String, period: ChartPeriod): ChartDataSeries {
        val encoded = URLEncoder.encode(symbol, "UTF-8")
        val path = "/v1/bist/history/$encoded?range=${period.range}&interval=${period.interval}"
        val json = getJson(settings.baseUrl.trim().removeSuffix("/") + path, true)
        return parseBackend(json, symbol, period)
    }

    private fun loadYahoo(symbol: String, period: ChartPeriod): ChartDataSeries {
        val encoded = URLEncoder.encode("$symbol.IS", "UTF-8")
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/$encoded?range=${period.range}&interval=${period.interval}&events=history"
        val json = getJson(url, false)
        val r = json.optJSONObject("chart")?.optJSONArray("result")?.optJSONObject(0)
            ?: throw JSONException("Yahoo chart.result bulunamadı")
        val timestamps = r.optJSONArray("timestamp") ?: throw JSONException("timestamp bulunamadı")
        val quote = r.optJSONObject("indicators")?.optJSONArray("quote")?.optJSONObject(0)
            ?: throw JSONException("quote bulunamadı")
        val open = quote.optJSONArray("open") ?: throw JSONException("open bulunamadı")
        val high = quote.optJSONArray("high") ?: throw JSONException("high bulunamadı")
        val low = quote.optJSONArray("low") ?: throw JSONException("low bulunamadı")
        val close = quote.optJSONArray("close") ?: throw JSONException("close bulunamadı")
        val volume = quote.optJSONArray("volume") ?: throw JSONException("volume bulunamadı")

        val raw = mutableListOf<Candle>()
        for (i in 0 until timestamps.length()) {
            if (open.isNull(i) || high.isNull(i) || low.isNull(i) || close.isNull(i) || volume.isNull(i)) continue
            raw += Candle(
                timestamp = timestamps.getLong(i) * 1000L,
                open = open.getDouble(i),
                high = high.getDouble(i),
                low = low.getDouble(i),
                close = close.getDouble(i),
                volume = volume.getDouble(i)
            )
        }
        val checked = ChartMath.validate(raw)
        require(checked.candles.size >= 2) { "Grafik verisi alınamadı veya doğrulanamadı." }
        return ChartDataSeries(
            symbol = symbol,
            candles = checked.candles,
            source = "Yahoo Finance",
            period = period,
            dataTimestamp = checked.candles.last().timestamp,
            isRealtime = false,
            delaySeconds = null,
            currentSessionIncluded = false,
            rejectedCount = checked.rejectedCount,
            duplicateCount = checked.duplicateCount
        )
    }

    private fun parseBackend(json: JSONObject, symbol: String, period: ChartPeriod): ChartDataSeries {
        val arr = json.optJSONArray("candles") ?: throw JSONException("candles alanı bulunamadı")
        val raw = mutableListOf<Candle>()
        for (i in 0 until arr.length()) {
            val x = arr.optJSONObject(i) ?: continue
            val ts = x.optLong("timestamp", 0L)
            val o = x.optDouble("open", Double.NaN)
            val h = x.optDouble("high", Double.NaN)
            val l = x.optDouble("low", Double.NaN)
            val c = x.optDouble("close", Double.NaN)
            val v = x.optDouble("volume", Double.NaN)
            raw += Candle(ts, o, h, l, c, v)
        }
        val checked = ChartMath.validate(raw)
        require(checked.candles.size >= 2) { "Grafik verisi alınamadı veya doğrulanamadı." }
        val delay = if (json.has("delaySeconds") && !json.isNull("delaySeconds")) json.optInt("delaySeconds") else null
        return ChartDataSeries(
            symbol = json.optString("symbol").ifBlank { symbol },
            candles = checked.candles,
            source = json.optString("source").ifBlank { "BorsaTakip Backend" },
            period = period,
            dataTimestamp = json.optLong("dataTimestamp", checked.candles.last().timestamp),
            isRealtime = json.optBoolean("realtime", false),
            delaySeconds = delay,
            currentSessionIncluded = json.optBoolean("currentSessionIncluded", false),
            rejectedCount = checked.rejectedCount,
            duplicateCount = checked.duplicateCount
        )
    }

    private fun getJson(url: String, authenticatedBackend: Boolean): JSONObject {
        var con: HttpURLConnection? = null
        try {
            con = URL(url).openConnection() as HttpURLConnection
            con.requestMethod = "GET"
            con.connectTimeout = 8_000
            con.readTimeout = 12_000
            con.setRequestProperty("Accept", "application/json")
            con.setRequestProperty("User-Agent", "Mozilla/5.0 BorsaTakip/${BuildConfig.VERSION_NAME} Android")
            if (authenticatedBackend && settings.apiKey.isNotBlank()) {
                con.setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
            }
            val code = con.responseCode
            if (code !in 200..299) throw IllegalStateException("Grafik veri isteği HTTP $code ile başarısız oldu.")
            val body = con.inputStream.bufferedReader().use { it.readText() }
            if (body.isBlank()) throw JSONException("Boş grafik yanıtı")
            return JSONObject(body)
        } finally {
            runCatching { con?.disconnect() }
        }
    }
}
