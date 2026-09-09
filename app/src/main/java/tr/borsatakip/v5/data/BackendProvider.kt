package tr.borsatakip.v5.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import tr.borsatakip.v5.model.ViopContract
import java.net.HttpURLConnection
import java.net.URL

class BackendProvider(context: Context) {
    private val s = SettingsStore(context)

    suspend fun loadViop(): Result<List<ViopContract>> = withContext(Dispatchers.IO) {
        runCatching {
            require(s.baseUrl.startsWith("https://")) { "VİOP için HTTPS veri sağlayıcı adresi Ayarlar bölümünde tanımlanmalıdır." }
            val con = URL(s.baseUrl.trimEnd('/') + "/v1/viop/contracts").openConnection() as HttpURLConnection
            con.connectTimeout = 8000
            con.readTimeout = 8000
            con.requestMethod = "GET"
            con.setRequestProperty("Accept", "application/json")
            if (s.apiKey.isNotBlank()) con.setRequestProperty("Authorization", "Bearer ${s.apiKey}")
            try {
                require(con.responseCode in 200..299) { "VİOP veri sağlayıcısı HTTP ${con.responseCode} döndürdü." }
                val array = JSONObject(con.inputStream.bufferedReader().use { it.readText() }).optJSONArray("items")
                    ?: return@runCatching emptyList()
                buildList {
                    for (i in 0 until array.length()) {
                        val x = array.optJSONObject(i) ?: continue
                        val symbol = x.optString("symbol").trim()
                        if (symbol.isBlank()) continue
                        add(
                            ViopContract(
                                symbol = symbol,
                                underlying = x.optString("underlying").ifBlank { "-" },
                                expiry = x.optString("expiry").ifBlank { "-" },
                                contractType = x.optString("contractType").ifBlank { "Vadeli İşlem" },
                                lastPrice = x.optDouble("lastPrice", Double.NaN).takeIf { it.isFinite() },
                                bid = x.optDouble("bid", Double.NaN).takeIf { it.isFinite() },
                                ask = x.optDouble("ask", Double.NaN).takeIf { it.isFinite() },
                                dailyChangePct = x.optDouble("dailyChangePct", Double.NaN).takeIf { it.isFinite() },
                                tickSize = x.optDouble("tickSize", Double.NaN).takeIf { it.isFinite() },
                                multiplier = x.optDouble("multiplier", Double.NaN).takeIf { it.isFinite() },
                                openInterest = x.optLong("openInterest", -1L).takeIf { it >= 0L },
                                volume = x.optDouble("volume", Double.NaN).takeIf { it.isFinite() },
                                liquidity = x.optString("liquidity").takeIf { it.isNotBlank() },
                                rollover = x.optString("rollover").takeIf { it.isNotBlank() },
                                providerId = "backend",
                                providerLabel = x.optString("source").ifBlank { "Ana Backend" },
                                isManual = false,
                                currency = x.optString("currency").takeIf { it.isNotBlank() },
                                status = "Canlı veri",
                                dataTimestamp = x.optLong("dataTimestamp", System.currentTimeMillis())
                            )
                        )
                    }
                }
            } finally {
                con.disconnect()
            }
        }
    }
}
