package tr.borsatakip.v5.model

import android.os.SystemClock

enum class DataMode { REALTIME, DELAYED, EOD, UNVERIFIED }
enum class SignalValidity { VALID, WATCH, INSUFFICIENT, REJECTED }
enum class ScanRunStatus { STARTED, PARTIAL, COMPLETE, FAILED }

data class Candle(val timestamp:Long,val open:Double,val high:Double,val low:Double,val close:Double,val volume:Double)

data class Stock(
    val symbol:String,
    val companyName:String?,
    val candles:List<Candle>,
    val source:String,
    val dataTimestamp:Long,
    val isRealtime:Boolean = false,
    val delaySeconds:Int? = null,
    val currentSessionIncluded:Boolean = false,
    val receivedAt:Long = System.currentTimeMillis(),
    val receivedElapsedRealtime:Long = SystemClock.elapsedRealtime()
) { val exchangeTimestamp:Long get() = dataTimestamp }

data class TechnicalSnapshot(
    val ema20:Double?, val ema50:Double?, val ema200:Double?, val rsi14:Double?,
    val macd:Double?, val macdSignal:Double?, val bbUpper:Double?, val bbLower:Double?,
    val atr14:Double?, val vwap:Double?, val volumeRatio:Double?, val support:Double?, val resistance:Double?,
    val vwma:Double? = null, val recommendation:Double? = null
)

data class ScanRun(
    val scanRunId:String, val scanStartedAt:Long, val scanCompletedAt:Long? = null,
    val providerId:String, val status:ScanRunStatus, val count:Int = 0, val errorCount:Int = 0
)

data class DataSnapshot(
    val provider:String, val symbol:String, val price:Double,
    val exchangeTimestamp:Long, val receivedAt:Long, val dataMode:DataMode,
    val delaySeconds:Int?, val currentSessionIncluded:Boolean
)

data class OpportunitySnapshot(
    val provider:String, val symbol:String, val price:Double,
    val exchangeTimestamp:Long, val receivedAt:Long, val dataMode:DataMode,
    val dataConfidence:Int, val technical:TechnicalSnapshot, val finalSignalScore:Int,
    val scanRunId:String? = null, val dataSnapshot:DataSnapshot? = null
)

data class Opportunity(
    val symbol:String,
    val companyName:String?,
    val price:Double,
    val dailyChangePct:Double,
    val score:Int,
    val riskScore:Int,
    val direction:String,
    val technicalLabel:String,
    val volumeLabel:String,
    val kapLabel:String,
    val liquidityLabel:String,
    val support:Double?,
    val resistance:Double?,
    val source:String,
    val dataTimestamp:Long,
    val candles:List<Candle>,
    val technical:TechnicalSnapshot,
    val scoreBreakdown:List<String> = emptyList(),
    val dataConfidenceScore:Int = 100,
    val dataConfidenceLabel:String = "Yüksek",
    val finalSignalScore:Int = score,
    val volumeDirectionLabel:String = "Yön verisi yok",
    val isRealtime:Boolean = false,
    val delaySeconds:Int? = null,
    val currentSessionIncluded:Boolean = false,
    val exchangeTimestamp:Long = dataTimestamp,
    val receivedAt:Long = 0L,
    val receivedElapsedRealtime:Long = 0L,
    val scanStartedAt:Long = 0L,
    val scanCompletedAt:Long = 0L,
    val dataMode:DataMode = DataMode.UNVERIFIED,
    val signalValidity:SignalValidity = SignalValidity.WATCH,
    val signalValidityReason:String = "Sinyal geçerliliği doğrulanmadı.",
    val scanRunId:String? = null,
    val snapshot:OpportunitySnapshot? = null
)

data class ViopContract(
    val symbol:String,
    val underlying:String,
    val expiry:String,
    val contractType:String = "Vadeli İşlem",
    val lastPrice:Double? = null,
    val bid:Double? = null,
    val ask:Double? = null,
    val dailyChangePct:Double? = null,
    val tickSize:Double? = null,
    val multiplier:Double? = null,
    val openInterest:Long? = null,
    val volume:Double? = null,
    val liquidity:String? = null,
    val rollover:String? = null,
    val providerId:String = "manual",
    val providerLabel:String = "Manuel",
    val isManual:Boolean = false,
    val currency:String? = null,
    val status:String = "Veri bekleniyor",
    val dataTimestamp:Long = 0L,
    val isRealtime:Boolean = false,
    val delaySeconds:Int? = null,
    val currentSessionIncluded:Boolean = false,
    val receivedAt:Long = 0L,
    val dataMode:DataMode = DataMode.UNVERIFIED,
    val validity:SignalValidity = SignalValidity.WATCH,
    val validityReason:String = "Sözleşme doğrulanmadı."
) { val exchangeTimestamp:Long get() = dataTimestamp }
