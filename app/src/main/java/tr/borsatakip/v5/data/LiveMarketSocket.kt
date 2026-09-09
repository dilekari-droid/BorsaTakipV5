package tr.borsatakip.v5.data

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class LiveMarketSocket(context: Context) {
    private val settings = SettingsStore(context)
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()
    private var socket: WebSocket? = null

    data class Tick(
        val symbol: String,
        val price: Double,
        val changePct: Double?,
        val volume: Double?,
        val timestamp: Long,
        val market: String,
        val source: String?
    )

    interface Listener {
        fun onConnected()
        fun onTick(tick: Tick)
        fun onDisconnected(reason: String)
        fun onError(message: String)
    }

    fun connect(symbols: List<String>, listener: Listener) {
        close()
        val base = settings.baseUrl.trim().removeSuffix("/")
        if (!base.startsWith("https://")) {
            listener.onError("Canlı veri için HTTPS backend adresi gerekli.")
            return
        }
        val wsUrl = "wss://" + base.removePrefix("https://") + "/v1/live"
        val requestBuilder = Request.Builder().url(wsUrl)
        if (settings.apiKey.isNotBlank()) requestBuilder.header("Authorization", "Bearer ${settings.apiKey}")
        socket = client.newWebSocket(requestBuilder.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                listener.onConnected()
                val payload = JSONObject()
                    .put("action", "subscribe")
                    .put("symbols", symbols.distinct())
                webSocket.send(payload.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val x = JSONObject(text)
                    if (x.optString("type") != "tick") return
                    val price = x.optDouble("price", Double.NaN)
                    val symbol = x.optString("symbol")
                    if (symbol.isBlank() || price.isNaN()) return
                    listener.onTick(
                        Tick(
                            symbol = symbol,
                            price = price,
                            changePct = x.optDouble("changePct", Double.NaN).takeIf { !it.isNaN() },
                            volume = x.optDouble("volume", Double.NaN).takeIf { !it.isNaN() },
                            timestamp = x.optLong("timestamp", System.currentTimeMillis()),
                            market = x.optString("market").ifBlank { "BIST" },
                            source = x.optString("source").takeIf { it.isNotBlank() }
                        )
                    )
                } catch (e: Exception) {
                    listener.onError(e.message ?: "Canlı veri mesajı çözümlenemedi.")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                listener.onDisconnected(reason.ifBlank { "Bağlantı kapatılıyor" })
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                listener.onError(t.message ?: "Canlı veri bağlantısı kurulamadı.")
            }
        })
    }

    fun close() {
        socket?.close(1000, "client_close")
        socket = null
    }
}
