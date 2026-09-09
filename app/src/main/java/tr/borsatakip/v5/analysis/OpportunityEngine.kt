package tr.borsatakip.v5.analysis

import tr.borsatakip.v5.model.*
import kotlin.math.abs

object OpportunityEngine {
    fun score(stock:Stock, kapLabel:String="Veri yok"):Opportunity?{
        if(stock.candles.size<220)return null
        val t=TechnicalAnalyzer.analyze(stock.candles); val c=stock.candles; val price=c.last().close; val prev=c[c.lastIndex-1].close
        var long=0.0; var short=0.0
        if(t.ema20!=null && t.ema50!=null && t.ema200!=null){
            if(price>t.ema20 && t.ema20>t.ema50 && t.ema50>t.ema200) long+=28 else if(price<t.ema20 && t.ema20<t.ema50 && t.ema50<t.ema200) short+=28
        }
        t.rsi14?.let { if(it in 50.0..68.0) long+=12; if(it in 32.0..50.0) short+=12; if(it>75) short+=4; if(it<25) long+=4 }
        if(t.macd!=null && t.macdSignal!=null){ if(t.macd>t.macdSignal) long+=14 else short+=14 }
        t.volumeRatio?.let { if(it>=1.5){ long+=10; short+=10 } }
        t.vwap?.let { if(price>it) long+=8 else short+=8 }
        t.resistance?.let { if(price>=it*0.995) long+=8 }
        t.support?.let { if(price<=it*1.005) short+=8 }
        val direction=if(long>=short) "LONG" else "SHORT"; val raw=maxOf(long,short)
        val score=raw.coerceIn(0.0,100.0).toInt()
        val atrPct=t.atr14?.let{it/price*100} ?: 99.0
        val risk=(atrPct*10 + if((t.volumeRatio?:0.0)<0.8) 15 else 0 + if((t.rsi14?:50.0)>75 || (t.rsi14?:50.0)<25) 12 else 0).coerceIn(0.0,100.0).toInt()
        val liq=when { (t.volumeRatio?:0.0)>=1.5 -> "Yüksek"; (t.volumeRatio?:0.0)>=0.8 -> "Orta"; else -> "Düşük" }
        val tech=when{ score>=80->"Güçlü"; score>=65->"Pozitif"; else->"Nötr" }
        val vol=t.volumeRatio?.let{"%.1fx".format(it)} ?: "Veri yok"
        val ch=(price/prev-1)*100
        return Opportunity(stock.symbol,stock.companyName,price,ch,score,risk,direction,tech,vol,kapLabel,liq,t.support,t.resistance,stock.source,stock.dataTimestamp,c,t)
    }
}
