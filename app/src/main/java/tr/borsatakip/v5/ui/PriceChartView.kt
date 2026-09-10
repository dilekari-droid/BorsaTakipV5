package tr.borsatakip.v5.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import tr.borsatakip.v5.model.Candle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Yalnız verilen gerçek Candle listesini çizer. Veri üretmez, interpolate etmez ve eksik dönemi
 * uydurmaz. EMA/RSI/MACD aynı candle dizisinden hesaplanır.
 */
class PriceChartView(c: Context, a: AttributeSet? = null) : View(c, a) {
    enum class Range(val label: String, val sessions: Int) {
        DAY("1G", 1), WEEK("1H", 5), MONTH("1A", 22), THREE_MONTHS("3A", 66), YEAR("1Y", 252)
    }

    var candles: List<Candle> = emptyList()
        set(value) {
            field = value.filter { x ->
                x.timestamp > 0L && listOf(x.open, x.high, x.low, x.close, x.volume).all { it.isFinite() } &&
                    x.high >= x.low && x.volume >= 0.0
            }.sortedBy { it.timestamp }
            invalidate()
        }

    var range: Range = Range.THREE_MONTHS
        private set

    var statusText: String = ""
        private set

    fun setRange(newRange: Range) {
        range = newRange
        invalidate()
    }

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.LTGRAY; textSize = 26f }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(55, 180, 190, 205); strokeWidth = 1f }
    private val up = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0, 200, 83); strokeWidth = 2f }
    private val down = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 82, 82); strokeWidth = 2f }
    private val ema20Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 193, 7); strokeWidth = 2f; style = Paint.Style.STROKE }
    private val ema50Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(3, 169, 244); strokeWidth = 2f; style = Paint.Style.STROKE }
    private val ema200Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(186, 104, 200); strokeWidth = 2f; style = Paint.Style.STROKE }
    private val rsiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 235, 59); strokeWidth = 2f; style = Paint.Style.STROKE }
    private val macdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0, 229, 255); strokeWidth = 2f; style = Paint.Style.STROKE }
    private val signalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 152, 0); strokeWidth = 2f; style = Paint.Style.STROKE }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val all = candles
        if (all.isEmpty()) {
            statusText = "Yeterli OHLCV verisi bulunamadı."
            canvas.drawText(statusText, paddingLeft + 12f, height / 2f, text)
            return
        }
        if (range.sessions > 1 && all.size < range.sessions) {
            statusText = "${range.label} için yeterli gerçek OHLCV verisi yok (${all.size}/${range.sessions})."
            canvas.drawText(statusText, paddingLeft + 12f, height / 2f, text)
            return
        }
        statusText = ""

        val visibleCount = min(range.sessions, all.size).coerceAtLeast(1)
        val start = all.size - visibleCount
        val visible = all.subList(start, all.size)
        val left = paddingLeft.toFloat() + 8f
        val right = width.toFloat() - paddingRight - 8f
        val top = paddingTop.toFloat() + 8f
        val bottom = height.toFloat() - paddingBottom - 8f
        if (right <= left || bottom <= top) return

        val h = bottom - top
        val priceTop = top
        val priceBottom = top + h * 0.55f
        val volumeTop = priceBottom + h * 0.03f
        val volumeBottom = volumeTop + h * 0.12f
        val rsiTop = volumeBottom + h * 0.03f
        val rsiBottom = rsiTop + h * 0.12f
        val macdTop = rsiBottom + h * 0.03f
        val macdBottom = bottom

        listOf(priceBottom, volumeBottom, rsiBottom).forEach { y -> canvas.drawLine(left, y, right, y, grid) }
        canvas.drawText("FİYAT + EMA20/50/200", left, priceTop + 24f, text)
        canvas.drawText("HACİM", left, volumeTop + 22f, text)
        canvas.drawText("RSI14", left, rsiTop + 22f, text)
        canvas.drawText("MACD / SIGNAL", left, macdTop + 22f, text)

        drawPrice(canvas, all, visible, start, left, right, priceTop + 30f, priceBottom)
        drawVolume(canvas, visible, left, right, volumeTop + 26f, volumeBottom)
        drawRsi(canvas, all, start, visibleCount, left, right, rsiTop + 26f, rsiBottom)
        drawMacd(canvas, all, start, visibleCount, left, right, macdTop + 26f, macdBottom)
    }

    private fun drawPrice(canvas: Canvas, all: List<Candle>, visible: List<Candle>, start: Int, left: Float, right: Float, top: Float, bottom: Float) {
        val ema20 = emaSeriesAligned(all.map { it.close }, 20)
        val ema50 = emaSeriesAligned(all.map { it.close }, 50)
        val ema200 = emaSeriesAligned(all.map { it.close }, 200)
        val indicatorValues = buildList<Double> {
            visible.forEach { add(it.low); add(it.high) }
            ema20.drop(start).filterNotNull().forEach(::add)
            ema50.drop(start).filterNotNull().forEach(::add)
            ema200.drop(start).filterNotNull().forEach(::add)
        }
        val minV = indicatorValues.minOrNull() ?: return
        val maxV = indicatorValues.maxOrNull() ?: return
        val span = (maxV - minV).takeIf { it > 0.0 } ?: 1.0
        fun y(v: Double) = bottom - (((v - minV) / span).toFloat() * (bottom - top))
        val step = if (visible.size <= 1) (right - left) else (right - left) / (visible.size - 1)
        val bodyWidth = max(3f, min(14f, step * 0.55f))
        visible.forEachIndexed { i, k ->
            val x = if (visible.size <= 1) (left + right) / 2f else left + step * i
            val p = if (k.close >= k.open) up else down
            canvas.drawLine(x, y(k.low), x, y(k.high), p)
            p.style = Paint.Style.FILL
            val y1 = y(k.open); val y2 = y(k.close)
            canvas.drawRect(x - bodyWidth/2f, min(y1,y2), x + bodyWidth/2f, max(y1,y2).coerceAtLeast(min(y1,y2)+2f), p)
            p.style = Paint.Style.STROKE
        }
        drawAlignedLine(canvas, ema20, start, visible.size, left, right, ::y, ema20Paint)
        drawAlignedLine(canvas, ema50, start, visible.size, left, right, ::y, ema50Paint)
        drawAlignedLine(canvas, ema200, start, visible.size, left, right, ::y, ema200Paint)

        // Sinyal üretim noktası mevcut snapshot'ın son mumu olarak işaretlenir; yeni veri üretilmez.
        val markerX = if (visible.size <= 1) (left + right)/2f else right
        val markerY = y(visible.last().close)
        val marker = Path().apply { moveTo(markerX, markerY - 12f); lineTo(markerX - 9f, markerY + 8f); lineTo(markerX + 9f, markerY + 8f); close() }
        signalPaint.style = Paint.Style.FILL
        canvas.drawPath(marker, signalPaint)
        signalPaint.style = Paint.Style.STROKE
    }

    private fun drawVolume(canvas: Canvas, visible: List<Candle>, left: Float, right: Float, top: Float, bottom: Float) {
        val maxV = visible.maxOfOrNull { it.volume }?.takeIf { it > 0.0 } ?: return
        val step = if (visible.size <= 1) right-left else (right-left)/(visible.size-1)
        val w = max(2f, min(10f, step*0.5f))
        visible.forEachIndexed { i, c ->
            val x = if (visible.size <= 1) (left+right)/2f else left + step*i
            val y = bottom - ((c.volume/maxV).toFloat()*(bottom-top))
            val p = if (c.close >= c.open) up else down
            p.style = Paint.Style.FILL
            canvas.drawRect(x-w/2f,y,x+w/2f,bottom,p)
            p.style = Paint.Style.STROKE
        }
    }

    private fun drawRsi(canvas: Canvas, all: List<Candle>, start: Int, count: Int, left: Float, right: Float, top: Float, bottom: Float) {
        val rsi = rsiSeriesAligned(all.map { it.close },14)
        fun y(v: Double) = bottom - ((v.coerceIn(0.0,100.0)/100.0).toFloat()*(bottom-top))
        canvas.drawLine(left,y(30.0),right,y(30.0),grid)
        canvas.drawLine(left,y(70.0),right,y(70.0),grid)
        drawAlignedLine(canvas,rsi,start,count,left,right,::y,rsiPaint)
    }

    private fun drawMacd(canvas: Canvas, all: List<Candle>, start: Int, count: Int, left: Float, right: Float, top: Float, bottom: Float) {
        val closes = all.map { it.close }
        val fast = emaSeriesAligned(closes,12)
        val slow = emaSeriesAligned(closes,26)
        val line = List(closes.size) { i -> if (fast[i] != null && slow[i] != null) fast[i]!! - slow[i]!! else null }
        val signal = emaNullableAligned(line,9)
        val vals = (line.drop(start).take(count) + signal.drop(start).take(count)).filterNotNull()
        if (vals.isEmpty()) return
        val minV = min(0.0, vals.minOrNull() ?: 0.0); val maxV = max(0.0, vals.maxOrNull() ?: 0.0)
        val span = (maxV-minV).takeIf { it > 0.0 } ?: 1.0
        fun y(v:Double) = bottom - (((v-minV)/span).toFloat()*(bottom-top))
        canvas.drawLine(left,y(0.0),right,y(0.0),grid)
        drawAlignedLine(canvas,line,start,count,left,right,::y,macdPaint)
        drawAlignedLine(canvas,signal,start,count,left,right,::y,signalPaint)
    }

    private fun drawAlignedLine(canvas:Canvas, values:List<Double?>, start:Int, count:Int, left:Float, right:Float, y:(Double)->Float, paint:Paint) {
        val path = Path(); var started=false
        val step = if (count <= 1) right-left else (right-left)/(count-1)
        for (i in 0 until count) {
            val v = values.getOrNull(start+i) ?: continue
            val x = if (count <= 1) (left+right)/2f else left + step*i
            if (!started) { path.moveTo(x,y(v)); started=true } else path.lineTo(x,y(v))
        }
        if (started) canvas.drawPath(path,paint)
    }

    private fun emaSeriesAligned(values:List<Double>, period:Int):List<Double?> {
        val out = MutableList<Double?>(values.size){null}
        if (period<=0 || values.size<period) return out
        var e = values.take(period).average(); if (!e.isFinite()) return out
        out[period-1]=e; val k=2.0/(period+1)
        for(i in period until values.size){ val x=values[i]; if(!x.isFinite()) break; e=x*k+e*(1-k); if(!e.isFinite()) break; out[i]=e }
        return out
    }

    private fun rsiSeriesAligned(values:List<Double>, p:Int):List<Double?> {
        val out=MutableList<Double?>(values.size){null}; if(p<=0||values.size<p+1)return out
        var gain=0.0; var loss=0.0
        for(i in 1..p){ val d=values[i]-values[i-1]; if(d>0)gain+=d else loss-=d }
        gain/=p; loss/=p
        fun value():Double = if(loss==0.0)100.0 else 100.0-(100.0/(1.0+gain/loss))
        out[p]=value()
        for(i in p+1 until values.size){ val d=values[i]-values[i-1]; gain=(gain*(p-1)+if(d>0)d else 0.0)/p; loss=(loss*(p-1)+if(d<0)-d else 0.0)/p; val r=value(); if(r.isFinite())out[i]=r }
        return out
    }

    private fun emaNullableAligned(values:List<Double?>, period:Int):List<Double?> {
        val out=MutableList<Double?>(values.size){null}; val first=values.indexOfFirst{it!=null}; if(first<0)return out
        val dense=values.drop(first).takeWhile{it!=null}.map{it!!}; if(dense.size<period)return out
        val aligned=emaSeriesAligned(dense,period); aligned.forEachIndexed{i,v-> if(v!=null && first+i<out.size) out[first+i]=v}; return out
    }
}
