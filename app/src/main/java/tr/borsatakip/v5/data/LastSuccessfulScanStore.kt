package tr.borsatakip.v5.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import tr.borsatakip.v5.model.Candle
import tr.borsatakip.v5.model.DataMode
import tr.borsatakip.v5.model.Opportunity
import tr.borsatakip.v5.model.ScanRun
import tr.borsatakip.v5.model.ScanRunStatus
import tr.borsatakip.v5.model.SignalValidity
import tr.borsatakip.v5.model.TechnicalSnapshot
import java.io.File

/**
 * Son başarılı tarama yalnız COMPLETE olduğunda atomik olarak saklanır.
 * PARTIAL/FAILED/CANCELLED taramalar bu kaydı değiştiremez.
 */
class LastSuccessfulScanStore(context: Context) {
    private val file = File(context.filesDir, "last_successful_scan_v2.json")

    suspend fun save(run: ScanRun, items: List<Opportunity>) = withContext(Dispatchers.IO) {
        require(run.status == ScanRunStatus.COMPLETE) { "Yalnız COMPLETE tarama son başarılı tarama olabilir." }
        val root = JSONObject().apply {
            put("scanRunId", run.scanRunId)
            put("scanStartedAt", run.scanStartedAt)
            put("scanCompletedAt", run.scanCompletedAt ?: 0L)
            put("providerId", run.providerId)
            put("status", run.status.name)
            put("count", run.count)
            put("errorCount", run.errorCount)
            put("items", JSONArray().apply { items.forEach { put(toJson(it)) } })
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(root.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(root.toString())
            tmp.delete()
        }
    }

    suspend fun load(): Pair<ScanRun, List<Opportunity>>? = withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext null
        runCatching {
            val root = JSONObject(file.readText())
            val run = ScanRun(
                scanRunId = root.getString("scanRunId"),
                scanStartedAt = root.getLong("scanStartedAt"),
                scanCompletedAt = root.optLong("scanCompletedAt").takeIf { it > 0L },
                providerId = root.optString("providerId"),
                status = ScanRunStatus.valueOf(root.optString("status", ScanRunStatus.COMPLETE.name)),
                count = root.optInt("count"),
                errorCount = root.optInt("errorCount")
            )
            require(run.status == ScanRunStatus.COMPLETE)
            val a = root.optJSONArray("items") ?: JSONArray()
            val items = (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let(::fromJson) }
            run to items
        }.getOrNull()
    }

    private fun toJson(x: Opportunity) = JSONObject().apply {
        put("symbol", x.symbol); put("companyName", x.companyName); put("price", x.price)
        put("dailyChangePct", x.dailyChangePct); put("score", x.score); put("riskScore", x.riskScore)
        put("direction", x.direction); put("technicalLabel", x.technicalLabel); put("volumeLabel", x.volumeLabel)
        put("kapLabel", x.kapLabel); put("liquidityLabel", x.liquidityLabel)
        putNullable("support", x.support); putNullable("resistance", x.resistance)
        put("source", x.source); put("dataTimestamp", x.dataTimestamp)
        put("dataConfidenceScore", x.dataConfidenceScore); put("dataConfidenceLabel", x.dataConfidenceLabel)
        put("finalSignalScore", x.finalSignalScore); put("volumeDirectionLabel", x.volumeDirectionLabel)
        put("isRealtime", x.isRealtime); putNullable("delaySeconds", x.delaySeconds); put("currentSessionIncluded", x.currentSessionIncluded)
        put("exchangeTimestamp", x.exchangeTimestamp); put("receivedAt", x.receivedAt)
        put("scanStartedAt", x.scanStartedAt); put("scanCompletedAt", x.scanCompletedAt)
        put("dataMode", x.dataMode.name); put("signalValidity", x.signalValidity.name)
        put("signalValidityReason", x.signalValidityReason); put("scanRunId", x.scanRunId)
        put("technical", technicalJson(x.technical))
        put("scoreBreakdown", JSONArray(x.scoreBreakdown))
        put("candles", JSONArray().apply { x.candles.takeLast(260).forEach { c ->
            put(JSONObject().apply { put("timestamp",c.timestamp); put("open",c.open); put("high",c.high); put("low",c.low); put("close",c.close); put("volume",c.volume) })
        } })
    }

    private fun fromJson(o: JSONObject): Opportunity {
        val t = o.optJSONObject("technical") ?: JSONObject()
        val technical = TechnicalSnapshot(
            d(t,"ema20"),d(t,"ema50"),d(t,"ema200"),d(t,"rsi14"),d(t,"macd"),d(t,"macdSignal"),
            d(t,"bbUpper"),d(t,"bbLower"),d(t,"atr14"),d(t,"vwap"),d(t,"volumeRatio"),d(t,"support"),d(t,"resistance"),d(t,"vwma"),d(t,"recommendation")
        )
        val ca = o.optJSONArray("candles") ?: JSONArray()
        val candles = (0 until ca.length()).mapNotNull { i -> ca.optJSONObject(i) }.mapNotNull { c ->
            val values = listOf(c.optDouble("open",Double.NaN),c.optDouble("high",Double.NaN),c.optDouble("low",Double.NaN),c.optDouble("close",Double.NaN),c.optDouble("volume",Double.NaN))
            if (c.optLong("timestamp") <= 0L || values.any { !it.isFinite() }) null else Candle(c.getLong("timestamp"),values[0],values[1],values[2],values[3],values[4])
        }
        val breakdownArray = o.optJSONArray("scoreBreakdown") ?: JSONArray()
        val breakdown = (0 until breakdownArray.length()).map { breakdownArray.optString(it) }
        return Opportunity(
            symbol=o.getString("symbol"), companyName=o.optString("companyName").takeIf { it.isNotBlank() && it != "null" },
            price=o.getDouble("price"), dailyChangePct=o.optDouble("dailyChangePct"), score=o.optInt("score"), riskScore=o.optInt("riskScore"),
            direction=o.optString("direction"), technicalLabel=o.optString("technicalLabel"), volumeLabel=o.optString("volumeLabel"),
            kapLabel=o.optString("kapLabel"), liquidityLabel=o.optString("liquidityLabel"), support=d(o,"support"), resistance=d(o,"resistance"),
            source=o.optString("source"), dataTimestamp=o.optLong("dataTimestamp"), candles=candles, technical=technical,
            scoreBreakdown=breakdown, dataConfidenceScore=o.optInt("dataConfidenceScore"), dataConfidenceLabel=o.optString("dataConfidenceLabel"),
            finalSignalScore=o.optInt("finalSignalScore"), volumeDirectionLabel=o.optString("volumeDirectionLabel"), isRealtime=o.optBoolean("isRealtime"),
            delaySeconds=i(o,"delaySeconds"), currentSessionIncluded=o.optBoolean("currentSessionIncluded"), exchangeTimestamp=o.optLong("exchangeTimestamp"),
            receivedAt=o.optLong("receivedAt"), scanStartedAt=o.optLong("scanStartedAt"), scanCompletedAt=o.optLong("scanCompletedAt"),
            dataMode=runCatching { DataMode.valueOf(o.optString("dataMode")) }.getOrDefault(DataMode.UNVERIFIED),
            signalValidity=runCatching { SignalValidity.valueOf(o.optString("signalValidity")) }.getOrDefault(SignalValidity.WATCH),
            signalValidityReason=o.optString("signalValidityReason"), scanRunId=o.optString("scanRunId").takeIf { it.isNotBlank() }
        )
    }

    private fun technicalJson(t: TechnicalSnapshot) = JSONObject().apply {
        putNullable("ema20",t.ema20); putNullable("ema50",t.ema50); putNullable("ema200",t.ema200); putNullable("rsi14",t.rsi14)
        putNullable("macd",t.macd); putNullable("macdSignal",t.macdSignal); putNullable("bbUpper",t.bbUpper); putNullable("bbLower",t.bbLower)
        putNullable("atr14",t.atr14); putNullable("vwap",t.vwap); putNullable("volumeRatio",t.volumeRatio); putNullable("support",t.support)
        putNullable("resistance",t.resistance); putNullable("vwma",t.vwma); putNullable("recommendation",t.recommendation)
    }
    private fun JSONObject.putNullable(k:String,v:Any?) { if (v == null) put(k,JSONObject.NULL) else put(k,v) }
    private fun d(o:JSONObject,k:String):Double? = if (o.has(k) && !o.isNull(k)) o.optDouble(k,Double.NaN).takeIf { it.isFinite() } else null
    private fun i(o:JSONObject,k:String):Int? = if (o.has(k) && !o.isNull(k)) o.optInt(k) else null
}
