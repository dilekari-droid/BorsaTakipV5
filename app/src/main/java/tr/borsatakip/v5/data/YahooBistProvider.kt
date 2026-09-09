package tr.borsatakip.v5.data

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import tr.borsatakip.v5.model.*
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class YahooBistProvider(private val context:Context){
    suspend fun scan(onProgress:(done:Int,total:Int)->Unit):List<Stock> = coroutineScope {
        val symbols=loadUniverse(); val sem=Semaphore(8); var done=0
        symbols.map { s -> async(Dispatchers.IO){
            val result=sem.withPermit { fetch(s) }
            synchronized(this@YahooBistProvider){ done++; onProgress(done,symbols.size) }
            result
        }}.awaitAll().filterNotNull()
    }
    suspend fun fetchOne(symbol:String):Stock?=withContext(Dispatchers.IO){ fetch(symbol.uppercase()) }
    private fun loadUniverse():List<String>{
        return try { context.assets.open("bist_symbols.txt").bufferedReader().readLines().map{it.trim().uppercase()}.filter{it.matches(Regex("[A-Z0-9]{3,7}"))}.distinct().ifEmpty{BistUniverse.fallback} } catch(_:Exception){ BistUniverse.fallback }
    }
    private fun fetch(symbol:String):Stock?{
        val y=URLEncoder.encode("$symbol.IS","UTF-8"); val con=(URL("https://query1.finance.yahoo.com/v8/finance/chart/$y?range=1y&interval=1d&events=history").openConnection() as HttpURLConnection)
        con.connectTimeout=7000; con.readTimeout=7000; con.setRequestProperty("User-Agent","Mozilla/5.0 BorsaTakip/5.0")
        return try{ if(con.responseCode !in 200..299)return null; parse(con.inputStream.bufferedReader().use{it.readText()},symbol) }catch(_:Exception){null}finally{con.disconnect()}
    }
    private fun parse(json:String,fallback:String):Stock?{
        val r=JSONObject(json).optJSONObject("chart")?.optJSONArray("result")?.optJSONObject(0)?:return null
        val ts=r.optJSONArray("timestamp")?:return null; val q=r.optJSONObject("indicators")?.optJSONArray("quote")?.optJSONObject(0)?:return null
        val o=q.optJSONArray("open")?:return null; val h=q.optJSONArray("high")?:return null; val l=q.optJSONArray("low")?:return null; val c=q.optJSONArray("close")?:return null; val v=q.optJSONArray("volume")?:return null
        val candles=mutableListOf<Candle>(); for(i in 0 until ts.length()){ if(o.isNull(i)||h.isNull(i)||l.isNull(i)||c.isNull(i)||v.isNull(i))continue; candles+=Candle(ts.getLong(i)*1000,o.getDouble(i),h.getDouble(i),l.getDouble(i),c.getDouble(i),v.getDouble(i)) }
        if(candles.size<220)return null; val m=r.optJSONObject("meta"); return Stock(fallback,m?.optString("longName")?.takeIf{it.isNotBlank()},candles,"Yahoo Finance • gecikmeli olabilir",candles.last().timestamp)
    }
}
