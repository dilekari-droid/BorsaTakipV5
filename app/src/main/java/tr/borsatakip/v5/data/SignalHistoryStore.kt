package tr.borsatakip.v5.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import tr.borsatakip.v5.model.Opportunity
import tr.borsatakip.v5.model.ScanRun
import tr.borsatakip.v5.model.ScanRunStatus
import tr.borsatakip.v5.model.SignalValidity
import java.io.File

/**
 * Sinyal üretildiği andaki kayıt değiştirilemez; sonraki fiyat gözlemleri ayrı outcome alanlarıdır.
 * WorkManager kesin zamanda çalışmadığı için her outcome gerçek observedAt ile saklanır.
 */
class SignalHistoryStore(context: Context) {
    private val file = File(context.filesDir, "signal_history_v1.json")

    suspend fun recordCompleteScan(run: ScanRun, items: List<Opportunity>) = withContext(Dispatchers.IO) {
        if (run.status != ScanRunStatus.COMPLETE) return@withContext
        val root = readRoot()
        val records = root.optJSONArray("records") ?: JSONArray()
        val existing = HashSet<String>()
        for (i in 0 until records.length()) existing += records.optJSONObject(i)?.optString("id").orEmpty()
        items.filter { it.signalValidity == SignalValidity.VALID || it.signalValidity == SignalValidity.WATCH }
            .forEach { x ->
                val id = "${run.scanRunId}:${x.symbol}:${x.direction}"
                if (id !in existing) {
                    records.put(JSONObject().apply {
                        put("id", id)
                        put("scanRunId", run.scanRunId)
                        put("symbol", x.symbol)
                        put("direction", x.direction)
                        put("signalPrice", x.price)
                        put("finalSignalScore", x.finalSignalScore)
                        put("riskScore", x.riskScore)
                        put("dataConfidenceScore", x.dataConfidenceScore)
                        put("signalValidity", x.signalValidity.name)
                        put("source", x.source)
                        put("exchangeTimestamp", x.exchangeTimestamp)
                        put("recordedAt", System.currentTimeMillis())
                        put("outcomes", JSONObject())
                    })
                }
            }
        root.put("records", trim(records, 1000))
        writeRoot(root)
    }

    suspend fun updateDueOutcomes(provider: MarketDataProvider, maxSignals: Int = 20) {
        val root = withContext(Dispatchers.IO) { readRoot() }
        val records = root.optJSONArray("records") ?: return
        val now = System.currentTimeMillis()
        val horizons = linkedMapOf("15m" to 15*60_000L, "30m" to 30*60_000L, "1h" to 60*60_000L, "4h" to 4*60*60_000L, "1d" to 24*60*60_000L)
        var processed = 0
        var changed = false
        for (i in records.length()-1 downTo 0) {
            if (processed >= maxSignals) break
            val r = records.optJSONObject(i) ?: continue
            val recordedAt = r.optLong("recordedAt",0L)
            if (recordedAt <= 0L) continue
            val outcomes = r.optJSONObject("outcomes") ?: JSONObject().also { r.put("outcomes",it) }
            val due = horizons.entries.firstOrNull { (name,ms) -> !outcomes.has(name) && now-recordedAt >= ms } ?: continue
            val stock = runCatching { provider.fetchOne(r.optString("symbol")) }.getOrNull() ?: continue
            val verdict = RealTimeIntegrityPolicy.validate(stock)
            if (!verdict.accepted) continue
            val price = stock.candles.lastOrNull()?.close?.takeIf { it.isFinite() && it > 0.0 } ?: continue
            val signalPrice = r.optDouble("signalPrice",Double.NaN)
            if (!signalPrice.isFinite() || signalPrice <= 0.0) continue
            val raw = (price/signalPrice-1.0)*100.0
            val returnPct = if (r.optString("direction").equals("SHORT",true)) -raw else raw
            outcomes.put(due.key, JSONObject().apply {
                put("targetHorizonMs",due.value)
                put("observedAt",System.currentTimeMillis())
                put("price",price)
                put("directionalReturnPct",returnPct)
                put("source",stock.source)
                put("exchangeTimestamp",stock.exchangeTimestamp)
            })
            processed++
            changed = true
        }
        if (changed) withContext(Dispatchers.IO) { writeRoot(root) }
    }

    private fun readRoot():JSONObject = runCatching { if(file.isFile) JSONObject(file.readText()) else JSONObject() }.getOrDefault(JSONObject())
    private fun writeRoot(root:JSONObject) {
        val tmp=File(file.parentFile,file.name+".tmp")
        tmp.writeText(root.toString())
        if(!tmp.renameTo(file)){ file.writeText(root.toString()); tmp.delete() }
    }
    private fun trim(a:JSONArray,max:Int):JSONArray {
        val start=(a.length()-max).coerceAtLeast(0); val out=JSONArray(); for(i in start until a.length()) out.put(a.get(i)); return out
    }
}
