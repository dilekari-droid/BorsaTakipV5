package tr.borsatakip.v5.model

data class Candle(val timestamp:Long,val open:Double,val high:Double,val low:Double,val close:Double,val volume:Double)

data class Stock(
    val symbol:String,
    val companyName:String?,
    val candles:List<Candle>,
    val source:String,
    val dataTimestamp:Long,
    val isRealtime:Boolean = false,
    val delaySeconds:Int? = null,
    val currentSessionIncluded:Boolean = false
)

data class TechnicalSnapshot(
    val ema20:Double?, val ema50:Double?, val ema200:Double?, val rsi14:Double?,
    val macd:Double?, val macdSignal:Double?, val bbUpper:Double?, val bbLower:Double?,
    val atr14:Double?, val vwap:Double?, val volumeRatio:Double?, val support:Double?, val resistance:Double?,
    val vwma:Double? = null,
    val recommendation:Double? = null
)

data class LrcTechnicalSnapshot(
    val period:Int = 100,
    val slope:Double,
    val normalizedSlopePct:Double?,
    val trend:String,
    val pearsonR:Double,
    val upper1:Double,
    val lower1:Double,
    val upper2:Double,
    val lower2:Double,
    val upper3:Double,
    val lower3:Double,
    val channelPosition:String,
    val channelWidth:Double,
    val channelWidthPct:Double?,
    val distanceToMidline:Double
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
    val lrc:LrcTechnicalSnapshot? = null,
    val scoreBreakdown:List<String> = emptyList(),
    val dataConfidenceScore:Int = 0,
    val dataConfidenceLabel:String = "Bilinmiyor",
    val analysisMode:String = "ARAŞTIRMA",
    val signalEligibleRealtime:Boolean = false,
    val finalSignalScore:Int = score,
    val volumeDirectionLabel:String = "Yön verisi yok",
    val isRealtime:Boolean = false,
    val delaySeconds:Int? = null,
    val currentSessionIncluded:Boolean = false
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
    val dataTimestamp:Long = 0L
)
