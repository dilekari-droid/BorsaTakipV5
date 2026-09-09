package tr.borsatakip.v5.analysis

import tr.borsatakip.v5.model.Candle
import tr.borsatakip.v5.model.TechnicalSnapshot
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

object TechnicalAnalyzer {
    fun analyze(c: List<Candle>): TechnicalSnapshot {
        val closes=c.map{it.close}; val vols=c.map{it.volume}
        val e20=ema(closes,20); val e50=ema(closes,50); val e200=ema(closes,200)
        val rsi=rsiWilder(closes,14)
        val macdLine = if (closes.size>=26) emaSeries(closes,12).last()-emaSeries(closes,26).last() else null
        val macdSignal = if (closes.size>=35) {
            val fast=emaSeries(closes,12); val slow=emaSeries(closes,26)
            val offset=fast.size-slow.size
            val line=slow.indices.map { fast[it+offset]-slow[it] }
            ema(line,9)
        } else null
        val bb=if(closes.size>=20){ val w=closes.takeLast(20); val m=w.average(); val sd=sqrt(w.sumOf{(it-m).pow(2)}/w.size); Pair(m+2*sd,m-2*sd)} else null
        val atr=atr(c,14)
        val vwap=if(c.isNotEmpty() && c.sumOf{it.volume}>0) c.sumOf{((it.high+it.low+it.close)/3.0)*it.volume}/c.sumOf{it.volume} else null
        val vr=if(vols.size>=21){ val base=vols.dropLast(1).takeLast(20).average(); if(base>0) vols.last()/base else null } else null
        val lows=c.takeLast(20).map{it.low}; val highs=c.takeLast(20).map{it.high}
        return TechnicalSnapshot(e20,e50,e200,rsi,macdLine,macdSignal,bb?.first,bb?.second,atr,vwap,vr,lows.minOrNull(),highs.maxOrNull())
    }

    private fun ema(values:List<Double>, period:Int):Double? = if(values.size<period) null else emaSeries(values,period).last()
    private fun emaSeries(values:List<Double>, period:Int):List<Double>{
        if(values.size<period) return emptyList(); val out=mutableListOf<Double>(); var e=values.take(period).average(); out+=e; val k=2.0/(period+1)
        for(i in period until values.size){ e=values[i]*k+e*(1-k); out+=e }; return out
    }
    private fun rsiWilder(v:List<Double>, p:Int):Double?{
        if(v.size<p+1)return null; val d=(1 until v.size).map{v[it]-v[it-1]}; var g=d.take(p).sumOf{if(it>0)it else 0.0}/p; var l=d.take(p).sumOf{if(it<0)-it else 0.0}/p
        for(i in p until d.size){ val x=d[i]; g=(g*(p-1)+(if(x>0)x else 0.0))/p; l=(l*(p-1)+(if(x<0)-x else 0.0))/p }
        if(l==0.0)return 100.0; val rs=g/l; return 100-(100/(1+rs))
    }
    private fun atr(c:List<Candle>,p:Int):Double?{
        if(c.size<p+1)return null; val tr=(1 until c.size).map{ i -> maxOf(c[i].high-c[i].low, abs(c[i].high-c[i-1].close), abs(c[i].low-c[i-1].close)) }
        var a=tr.take(p).average(); for(i in p until tr.size) a=(a*(p-1)+tr[i])/p; return a
    }
}
