package tr.borsatakip.v5.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import tr.borsatakip.v5.BuildConfig
import tr.borsatakip.v5.model.Opportunity
import tr.borsatakip.v5.model.ScanRun
import tr.borsatakip.v5.model.ScanRunStatus
import tr.borsatakip.v5.model.SignalValidity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class HistoryLoadState { NO_HISTORY, PARTIAL_HISTORY, COMPLETE_HISTORY, HISTORY_LOAD_ERROR }

data class HistoryOverview(
    val state: HistoryLoadState,
    val totalRecords: Int,
    val latestScanId: String? = null,
    val latestScanStatus: ScanRunStatus? = null
)

data class SignalHistoryFilter(
    val direction: String? = null,
    val minScore: Int? = null,
    val symbol: String? = null,
    val fromTime: Long? = null,
    val toTime: Long? = null
)

/**
 * Sinyal kaydı, sinyal üretildiği anda immutable olarak saklanır.
 * COMPLETE ve PARTIAL taramalardaki başarılı/geçerli sonuçlar kaydedilir;
 * hatalı/atlanan semboller sonuç listesinde olmadığından geçmişe girmez.
 * Forward outcome yalnız signalTime sonrasındaki doğrulanmış piyasa verisiyle yazılır.
 */
class SignalHistoryStore(context: Context) {
    private val file = File(context.filesDir, "signal_history_v1.json")

    suspend fun recordScan(run: ScanRun, items: List<Opportunity>): Int = withContext(Dispatchers.IO) {
        if (!SignalHistoryPolicy.shouldPersist(run.status, items.size)) return@withContext 0
        val root = readRoot()
        val records = root.optJSONArray("records") ?: JSONArray()
        val existing = HashSet<String>()
        for (i in 0 until records.length()) existing += records.optJSONObject(i)?.optString("id").orEmpty()

        val now = System.currentTimeMillis()
        var inserted = 0
        items.asSequence()
            .filter { it.signalValidity == SignalValidity.VALID || it.signalValidity == SignalValidity.WATCH }
            .filter { it.price.isFinite() && it.price > 0.0 }
            .forEach { x ->
                val signalTime = (run.scanCompletedAt ?: now).takeIf { it > 0L } ?: now
                val id = SignalHistoryPolicy.uniqueKey(run.scanRunId, x.symbol, signalTime)
                if (id !in existing) {
                    records.put(JSONObject().apply {
                        put("id", id)
                        put("scanRunId", run.scanRunId)
                        put("scanStatus", run.status.name)
                        put("symbol", x.symbol)
                        put("companyName", x.companyName ?: "")
                        put("direction", x.direction)
                        put("signalPrice", x.price)
                        put("finalSignalScore", x.finalSignalScore)
                        put("technicalScore", x.score)
                        put("riskScore", x.riskScore)
                        put("dataConfidenceScore", x.dataConfidenceScore)
                        put("signalValidity", x.signalValidity.name)
                        put("dataMode", x.dataMode.name)
                        put("source", x.source)
                        put("signalReason", x.signalValidityReason)
                        put("signalTime", signalTime)
                        put("marketDataTime", x.exchangeTimestamp)
                        put("dataAge", if (x.exchangeTimestamp > 0L) (signalTime - x.exchangeTimestamp).coerceAtLeast(0L) else -1L)
                        put("scanVersion", BuildConfig.VERSION_NAME)
                        put("createdAt", now)
                        // Backward compatibility with older records/UI.
                        put("exchangeTimestamp", x.exchangeTimestamp)
                        put("recordedAt", signalTime)
                        put("outcomes", JSONObject())
                    })
                    existing += id
                    inserted++
                }
            }

        root.put("records", trim(records, 3000))
        writeRoot(root)
        inserted
    }

    /** Kept for binary/source compatibility; now delegates to the correct COMPLETE/PARTIAL policy. */
    suspend fun recordCompleteScan(run: ScanRun, items: List<Opportunity>): Int = recordScan(run, items)

    suspend fun overview(): HistoryOverview = withContext(Dispatchers.IO) {
        try {
            if (!file.isFile) return@withContext HistoryOverview(HistoryLoadState.NO_HISTORY, 0)
            val root = JSONObject(file.readText())
            val records = root.optJSONArray("records") ?: JSONArray()
            if (records.length() == 0) return@withContext HistoryOverview(HistoryLoadState.NO_HISTORY, 0)
            val latest = records.optJSONObject(records.length() - 1)
            val latestStatus = runCatching {
                ScanRunStatus.valueOf(latest?.optString("scanStatus").orEmpty())
            }.getOrNull()
            val state = when (latestStatus) {
                ScanRunStatus.PARTIAL -> HistoryLoadState.PARTIAL_HISTORY
                ScanRunStatus.COMPLETE -> HistoryLoadState.COMPLETE_HISTORY
                else -> HistoryLoadState.COMPLETE_HISTORY // legacy records had no scanStatus and came only from COMPLETE scans
            }
            HistoryOverview(state, records.length(), latest?.optString("scanRunId")?.takeIf { it.isNotBlank() }, latestStatus)
        } catch (_: Throwable) {
            HistoryOverview(HistoryLoadState.HISTORY_LOAD_ERROR, 0)
        }
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
            val signalTime = r.optLong("signalTime", r.optLong("recordedAt", 0L))
            if (signalTime <= 0L) continue
            val outcomes = r.optJSONObject("outcomes") ?: JSONObject().also { r.put("outcomes",it) }
            val due = horizons.entries.firstOrNull { (name,ms) -> !outcomes.has(name) && now-signalTime >= ms } ?: continue
            val stock = runCatching { provider.fetchOne(r.optString("symbol")) }.getOrNull() ?: continue
            val verdict = RealTimeIntegrityPolicy.validate(stock)
            if (!verdict.accepted) continue
            // Strict no-look-ahead rule: the market observation itself must be after the signal.
            if (stock.exchangeTimestamp <= signalTime) continue
            val price = (stock.quotePrice ?: stock.candles.lastOrNull()?.close)?.takeIf { it.isFinite() && it > 0.0 } ?: continue
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

    suspend fun summaryLines(limit:Int = 200, filter: SignalHistoryFilter = SignalHistoryFilter()):List<String> = withContext(Dispatchers.IO) {
        val a=readRoot().optJSONArray("records") ?: return@withContext emptyList()
        val df=SimpleDateFormat("dd.MM.yyyy HH:mm:ss",Locale("tr","TR"))
        buildList {
            for(i in a.length()-1 downTo 0) {
                if(size>=limit) break
                val r=a.optJSONObject(i) ?: continue
                val direction=r.optString("direction")
                val score=r.optInt("finalSignalScore")
                val symbol=r.optString("symbol")
                val signalTime=r.optLong("signalTime", r.optLong("recordedAt",0L))
                if (filter.direction != null && !direction.equals(filter.direction, true)) continue
                if (filter.minScore != null && score < filter.minScore) continue
                if (!filter.symbol.isNullOrBlank() && !symbol.contains(filter.symbol, true)) continue
                if (filter.fromTime != null && signalTime < filter.fromTime) continue
                if (filter.toTime != null && signalTime > filter.toTime) continue

                val outcomes=r.optJSONObject("outcomes") ?: JSONObject()
                val outcomeText=buildList {
                    for(name in listOf("15m","30m","1h","4h","1d")) {
                        val o=outcomes.optJSONObject(name) ?: continue
                        val pct=o.optDouble("directionalReturnPct",Double.NaN)
                        val observed=o.optLong("observedAt",0L)
                        if(pct.isFinite()) add("$name ${"%+.2f".format(pct)}% @ ${if(observed>0)df.format(Date(observed)) else "?"}")
                    }
                }.joinToString(" • ").ifBlank { "Forward outcome henüz ölçülmedi" }
                val scanStatus=r.optString("scanStatus").ifBlank { "COMPLETE (legacy)" }
                add("$symbol $direction • Nihai $score/100 • Teknik ${r.optInt("technicalScore")}/100 • Risk ${r.optInt("riskScore")}/100\nTarama: $scanStatus • Sinyal: ${if(signalTime>0)df.format(Date(signalTime)) else "?"} • Fiyat ${"%.2f".format(r.optDouble("signalPrice"))}\nVeri modu ${r.optString("dataMode")} • Kaynak ${r.optString("source")} • Veri zamanı ${r.optLong("marketDataTime",r.optLong("exchangeTimestamp",0L))}\n$outcomeText")
            }
        }
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
