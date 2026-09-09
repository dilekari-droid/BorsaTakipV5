package tr.borsatakip.v5.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class BackendHealthClient(context: Context) {
    private val settings = SettingsStore(context)

    data class Health(
        val ok: Boolean,
        val provider: String,
        val latencyMs: Long,
        val serverTime: Long?,
        val message: String
    )

    suspend fun check(): Health = withContext(Dispatchers.IO) {
        val base = settings.baseUrl.trim().removeSuffix("/")
        if (base.isBlank()) return@withContext Health(false, "tanımsız", 0, null, "Servis adresi tanımlı değil.")
        if (!base.startsWith("https://")) return@withContext Health(false, "tanımsız", 0, null, "HTTPS servis adresi gerekli.")

        val start = System.currentTimeMillis()
        val con = URL(base + "/v1/health").openConnection() as HttpURLConnection
        con.requestMethod = "GET"
        con.connectTimeout = 5000
        con.readTimeout = 5000
        con.setRequestProperty("Accept", "application/json")
        if (settings.apiKey.isNotBlank()) con.setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
        try {
            val code = con.responseCode
            val latency = System.currentTimeMillis() - start
            if (code !in 200..299) return@withContext Health(false, "bilinmiyor", latency, null, "HTTP $code")
            val body = con.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            Health(
                ok = json.optBoolean("ok", true),
                provider = json.optString("provider").ifBlank { "mobil backend" },
                latencyMs = latency,
                serverTime = json.optLong("serverTime", 0L).takeIf { it > 0 },
                message = json.optString("message").ifBlank { "Bağlantı başarılı" }
            )
        } catch (e: Exception) {
            Health(false, "bilinmiyor", System.currentTimeMillis() - start, null, e.message ?: "Bağlantı kurulamadı")
        } finally {
            con.disconnect()
        }
    }
}
