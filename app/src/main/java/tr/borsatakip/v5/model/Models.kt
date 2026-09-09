package tr.borsatakip.v5.model

data class Candle(val timestamp:Long,val open:Double,val high:Double,val low:Double,val close:Double,val volume:Double)

data class Stock(
    val symbol:String,
    val companyName:String?,
    val candles:List<Candle>,
    val source:String,
    val dataTimestamp:Long
)

data class TechnicalSnapshot(
    val ema20:Double?, val ema50:Double?, val ema200:Double?, val rsi14:Double?,
    val macd:Double?, val macdSignal:Double?, val bbUpper:Double?, val bbLower:Double?,
    val atr14:Double?, val vwap:Double?, val volumeRatio:Double?, val support:Double?, val resistance:Double?
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
    val technical:TechnicalSnapshot
)

data class ViopContract(
    val symbol:String,
    val underlying:String,
    val expiry:String,
    val lastPrice:Double,
    val dailyChangePct:Double,
    val tickSize:Double?,
    val multiplier:Double?,
    val openInterest:Long?,
    val volume:Double?,
    val liquidity:String?,
    val rollover:String?,
    val dataTimestamp:Long
)
