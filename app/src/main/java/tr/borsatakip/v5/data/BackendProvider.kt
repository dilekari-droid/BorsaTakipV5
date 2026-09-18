package tr.borsatakip.v5.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import tr.borsatakip.v5.model.DataMode
import tr.borsatakip.v5.model.SignalValidity
import tr.borsatakip.v5.model.ViopContract
import java.net.HttpURLConnection
import java.net.URL
import java.time.YearMonth

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
                        val symbol = x.optString("symbol").trim().uppercase()
                        if (symbol.isBlank()) continue
                        val underlying = x.optString("underlying").trim().uppercase()
                        val expiry = x.optString("expiry").trim()
                        val tick = x.optDouble("tickSize", Double.NaN).takeIf { it.isFinite() && it > 0.0 }
                        val multiplier = x.optDouble("multiplier", Double.NaN).takeIf { it.isFinite() && it > 0.0 }
                        val dataTimestamp = x.optLong("dataTimestamp", 0L)
                        val realtime = x.optBoolean("realtime", false)
                        val delay = if (x.has("delaySeconds") && !x.isNull("delaySeconds")) x.optInt("delaySeconds") else null
                        val currentSession = x.optBoolean("currentSessionIncluded", false)
                        val receivedAt = System.currentTimeMillis()
                        val mode = when {
                            realtime && currentSession && delay != null && delay in 0..RealTimeIntegrityPolicy.MAX_DECLARED_DELAY_SECONDS -> DataMode.REALTIME
                            delay != null && delay > RealTimeIntegrityPolicy.MAX_DECLARED_DELAY_SECONDS -> DataMode.DELAYED
                            else -> DataMode.UNVERIFIED
                        }
                        val expiryOk = runCatching { YearMonth.parse(expiry) >= YearMonth.now() }.getOrDefault(false)
                        val validity = when {
                            !expiryOk -> SignalValidity.REJECTED
                            underlying.isBlank() || tick == null || multiplier == null -> SignalValidity.INSUFFICIENT
                            dataTimestamp <= 0L -> SignalValidity.REJECTED
                            mode != DataMode.REALTIME -> SignalValidity.WATCH
                            else -> SignalValidity.VALID
                        }
                        val reason = when (validity) {
                            SignalValidity.VALID -> "Vade, dayanak, tickSize, multiplier ve veri kökeni doğrulandı."
                            SignalValidity.WATCH -> "Sözleşme parametreleri mevcut ancak gerçek zamanlı veri modu doğrulanmadı."
                            SignalValidity.INSUFFICIENT -> "Dayanak, tickSize veya multiplier zorunlu alanlarından biri eksik."
                            SignalValidity.REJECTED -> if (!expiryOk) "Vade geçersiz veya sona ermiş." else "Piyasa veri zamanı eksik/geçersiz."
                        }
                        add(
                            ViopContract(
                                symbol = symbol,
                                underlying = underlying.ifBlank { "-" },
                                expiry = expiry.ifBlank { "-" },
                                contractType = x.optString("contractType").ifBlank { "Vadeli İşlem" },
                                lastPrice = x.optDouble("lastPrice", Double.NaN).takeIf { it.isFinite() && it > 0.0 },
                                bid = x.optDouble("bid", Double.NaN).takeIf { it.isFinite() && it >= 0.0 },
                                ask = x.optDouble("ask", Double.NaN).takeIf { it.isFinite() && it >= 0.0 },
                                dailyChangePct = x.optDouble("dailyChangePct", Double.NaN).takeIf { it.isFinite() },
                                tickSize = tick,
                                multiplier = multiplier,
                                openInterest = x.optLong("openInterest", -1L).takeIf { it >= 0L },
                                volume = x.optDouble("volume", Double.NaN).takeIf { it.isFinite() && it >= 0.0 },
                                liquidity = x.optString("liquidity").takeIf { it.isNotBlank() },
                                rollover = x.optString("rollover").takeIf { it.isNotBlank() },
                                providerId = "backend",
                                providerLabel = x.optString("source").ifBlank { "Ana Backend" },
                                isManual = false,
                                currency = x.optString("currency").takeIf { it.isNotBlank() },
                                status = when (validity) {
                                    SignalValidity.VALID -> "Doğrulanmış sözleşme"
                                    SignalValidity.WATCH -> "İzleme"
                                    SignalValidity.INSUFFICIENT -> "Yetersiz veri"
                                    SignalValidity.REJECTED -> "Reddedildi"
                                },
                                dataTimestamp = dataTimestamp,
                                isRealtime = realtime,
                                delaySeconds = delay,
                                currentSessionIncluded = currentSession,
                                receivedAt = receivedAt,
                                dataMode = mode,
                                validity = validity,
                                validityReason = reason
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
