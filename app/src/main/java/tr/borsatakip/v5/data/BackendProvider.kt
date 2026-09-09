package tr.borsatakip.v5.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import tr.borsatakip.v5.model.ViopContract
import java.net.HttpURLConnection
import java.net.URL

class BackendProvider(context:Context){ private val s=SettingsStore(context)
    suspend fun loadViop():Result<List<ViopContract>> = withContext(Dispatchers.IO){ runCatching {
        require(s.baseUrl.isNotBlank()){ "VİOP için veri sağlayıcı adresi Ayarlar bölümünde tanımlanmalıdır." }
        val con=(URL(s.baseUrl+"/v1/viop/contracts").openConnection() as HttpURLConnection); con.connectTimeout=8000; con.readTimeout=8000; if(s.apiKey.isNotBlank())con.setRequestProperty("Authorization","Bearer ${s.apiKey}")
        try{ require(con.responseCode in 200..299){"VİOP veri sağlayıcısı HTTP ${con.responseCode} döndürdü."}; val a=JSONObject(con.inputStream.bufferedReader().use{it.readText()}).optJSONArray("items")?:return@runCatching emptyList(); (0 until a.length()).map{ i -> val x=a.getJSONObject(i); ViopContract(x.getString("symbol"),x.optString("underlying"),x.optString("expiry"),x.getDouble("lastPrice"),x.optDouble("dailyChangePct",0.0),x.optDouble("tickSize").takeIf{!it.isNaN()},x.optDouble("multiplier").takeIf{!it.isNaN()},x.optLong("openInterest").takeIf{it>0},x.optDouble("volume").takeIf{!it.isNaN()},x.optString("liquidity").takeIf{it.isNotBlank()},x.optString("rollover").takeIf{it.isNotBlank()},x.optLong("dataTimestamp",System.currentTimeMillis())) }
        } finally { con.disconnect() }
    }} }
}
