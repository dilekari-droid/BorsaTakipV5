package tr.borsatakip.v5.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.content.ContextCompat
import tr.borsatakip.v5.R
import tr.borsatakip.v5.model.Candle
import tr.borsatakip.v5.ui.chart.ChartMath
import tr.borsatakip.v5.ui.chart.LinearRegressionChannelCalculator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class TechnicalOhlcvChartView(context: Context, attrs: android.util.AttributeSet? = null) : View(context, attrs) {
    var candles: List<Candle> = emptyList(); private set
    var onCandleSelected: ((Candle?) -> Unit)? = null

    private var ema20 = emptyList<Double?>(); private var ema50 = emptyList<Double?>(); private var ema200 = emptyList<Double?>()
    private var rsi14 = emptyList<Double?>()
    private var macd = ChartMath.MacdSeries(emptyList(), emptyList(), emptyList())

    private var lrcEnabled = true; private var lrcLength = 100
    private var lrcSigma1 = false; private var lrcSigma2 = true; private var lrcSigma3 = false
    private var lrcTrendColor = true; private var lrcPearson = true; private var lrcFill = false; private var lrcBreakoutWarning = true
    private var lrcSnapshots: List<LinearRegressionChannelCalculator.Result?> = emptyList()

    private var zoom = 1f; private var endOffset = 0; private var selectedIndex: Int? = null

    private fun color(id: Int) = ContextCompat.getColor(context, id)
    private fun paint(id: Int, width: Float = 1.4f) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = color(id); style = Paint.Style.STROKE; strokeWidth = dp(width) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = color(R.color.text_secondary); textSize = sp(9f) }
    private val grid = paint(R.color.stroke, 1f)
    private val up = paint(R.color.green); private val down = paint(R.color.red)
    private val e20p = paint(R.color.yellow); private val e50p = paint(R.color.blue); private val e200p = paint(R.color.purple)
    private val rsiP = paint(R.color.yellow); private val macdP = paint(R.color.blue); private val sigP = paint(R.color.orange)
    private val lrcMid = paint(R.color.blue, 1.6f); private val lrcBand = paint(R.color.blue, 1.4f)
    private val lrcMinor = paint(R.color.blue, 1f).apply { pathEffect = DashPathEffect(floatArrayOf(dp(5f), dp(4f)), 0f) }
    private val lrcFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val cross = paint(R.color.text_muted, 1f)

    data class LrcUiStatus(
        val length: Int,
        val trend: LinearRegressionChannelCalculator.Trend,
        val slope: Double,
        val pearsonR: Double,
        val sigma: Double,
        val distanceToMid: Double,
        val channelWidth2Sigma: Double,
        val zone: String,
        val breakout: String?,
        val momentumConfirmation: String,
        val pearsonVisible: Boolean
    )

    fun setLrcOptions(enabled: Boolean, length: Int, sigma1: Boolean, sigma2: Boolean, sigma3: Boolean,
        trendColor: Boolean, pearson: Boolean, fill: Boolean, breakoutWarning: Boolean) {
        val n = length.coerceIn(20, 500)
        val recalc = lrcEnabled != enabled || lrcLength != n
        lrcEnabled = enabled; lrcLength = n; lrcSigma1 = sigma1; lrcSigma2 = sigma2; lrcSigma3 = sigma3
        lrcTrendColor = trendColor; lrcPearson = pearson; lrcFill = fill; lrcBreakoutWarning = breakoutWarning
        if (recalc) recalcLrc()
        invalidate()
    }

    fun setCandles(input: List<Candle>) {
        candles = ChartMath.validate(input).candles
        val close = candles.map { it.close }
        ema20 = ChartMath.ema(close, 20); ema50 = ChartMath.ema(close, 50); ema200 = ChartMath.ema(close, 200)
        rsi14 = ChartMath.rsi(close, 14); macd = ChartMath.macd(close); recalcLrc()
        zoom = 1f; endOffset = 0; selectedIndex = null; onCandleSelected?.invoke(null); invalidate()
    }

    private fun recalcLrc() { lrcSnapshots = if (lrcEnabled) LinearRegressionChannelCalculator.rolling(candles.map { it.close }, lrcLength) else emptyList() }
    fun currentEma20() = ema20.lastOrNull { it != null }; fun currentEma50() = ema50.lastOrNull { it != null }; fun currentEma200() = ema200.lastOrNull { it != null }
    fun currentRsi14() = rsi14.lastOrNull { it != null }; fun currentMacd() = macd.macd.lastOrNull { it != null }; fun currentMacdSignal() = macd.signal.lastOrNull { it != null }

    fun currentLrcStatus(): LrcUiStatus? {
        if (!lrcEnabled || candles.isEmpty()) return null
        val i = candles.lastIndex; val s = lrcSnapshots.getOrNull(i) ?: return null; val close = s.lastClose
        val breakout = when { close > s.upperAt(i, 2.0) -> "+2σ ÜSTÜ"; close < s.lowerAt(i, 2.0) -> "-2σ ALTI"; else -> null }
        val zone = when { close > s.upperAt(i, 2.0) -> "+2σ üstü"; close > s.upperAt(i, 1.0) -> "+1σ ile +2σ"; close < s.lowerAt(i, 2.0) -> "-2σ altı"; close < s.lowerAt(i, 1.0) -> "-1σ ile -2σ"; else -> "±1σ içinde" }
        val r = rsi14.getOrNull(i); val m = macd.macd.getOrNull(i); val sg = macd.signal.getOrNull(i)
        val confirmation = when (breakout) {
            "+2σ ÜSTÜ" -> if (r != null && m != null && sg != null && r > 50 && m > sg) "RSI+MACD aynı yön" else "RSI+MACD doğrulaması yok"
            "-2σ ALTI" -> if (r != null && m != null && sg != null && r < 50 && m < sg) "RSI+MACD aynı yön" else "RSI+MACD doğrulaması yok"
            else -> "Kanal içinde"
        }
        return LrcUiStatus(s.length, s.trend, s.slope, s.pearsonR, s.sigma, s.distanceToMid, s.channelWidth2Sigma, zone,
            if (lrcBreakoutWarning) breakout else null, confirmation, lrcPearson)
    }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val before = visibleRange() ?: return false; val rect = priceRect()
            val f = ((d.focusX - rect.left) / rect.width()).coerceIn(0f, 1f)
            val anchor = (before.first + f * (before.count() - 1)).roundToInt().coerceIn(before.first, before.last)
            zoom = (zoom * d.scaleFactor).coerceIn(1f, 8f)
            val count = visibleCount().coerceAtLeast(2)
            val wantedLast = (anchor + (1f - f) * (count - 1)).roundToInt().coerceIn(count - 1, candles.lastIndex.coerceAtLeast(count - 1))
            endOffset = (candles.lastIndex - wantedLast).coerceIn(0, maxOffset()); invalidate(); return true
        }
    })
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (abs(dx) <= abs(dy)) return false
            parent?.requestDisallowInterceptTouchEvent(true)
            val per = max(1f, priceRect().width() / visibleCount().coerceAtLeast(2))
            endOffset = (endOffset + (dx / per).roundToInt()).coerceIn(0, maxOffset()); selectedIndex = null; onCandleSelected?.invoke(null); invalidate(); return true
        }
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val r = visibleRange() ?: return false; val rect = priceRect(); if (!rect.contains(e.x, e.y)) return false
            val n = ((e.x - rect.left) / rect.width()).coerceIn(0f, .9999f)
            selectedIndex = (r.first + n * r.count()).toInt().coerceIn(r.first, r.last); onCandleSelected?.invoke(candles[selectedIndex!!]); invalidate(); return true
        }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e); gestureDetector.onTouchEvent(e)
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) parent?.requestDisallowInterceptTouchEvent(false)
        return true
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c); val r = visibleRange() ?: return drawMessage(c, "Grafik verisi alınamadı.")
        drawPrice(c, r); drawVolume(c, r); drawRsi(c, r); drawMacd(c, r); drawSelection(c, r)
    }

    private fun drawPrice(c: Canvas, r: IntRange) {
        val rect = priceRect(); val scale = priceScale(r); val span = scale.second - scale.first; val step = rect.width() / r.count()
        fun x(i: Int) = rect.left + (i - r.first + .5f) * step
        fun y(v: Double) = rect.bottom - ((v - scale.first) / span).toFloat() * rect.height()
        repeat(6) { k -> val v = scale.first + span * k / 5.0; val yy = y(v); c.drawLine(rect.left, yy, rect.right, yy, grid); c.drawText(fmt(v), rect.right + dp(4f), yy, text) }
        drawLrc(c, r, rect, scale.first, scale.second)
        r.forEach { i -> val q = candles[i]; val p = if (q.close >= q.open) up else down; val xx = x(i); c.drawLine(xx, y(q.high), xx, y(q.low), p); p.style = Paint.Style.FILL; c.drawRect(xx - step*.28f, y(max(q.open,q.close)), xx + step*.28f, max(y(min(q.open,q.close)), y(max(q.open,q.close))+dp(1f)), p); p.style = Paint.Style.STROKE }
        drawSeries(c,r,ema20,rect,scale,e20p); drawSeries(c,r,ema50,rect,scale,e50p); drawSeries(c,r,ema200,rect,scale,e200p)
        c.drawText("FİYAT + EMA20/50/200", rect.left, rect.top-dp(8f), text)
        lrcSnapshots.getOrNull(r.last)?.let { s ->
            if (lrcEnabled) {
                val arrow = when (s.trend) {
                    LinearRegressionChannelCalculator.Trend.UP -> "↑"
                    LinearRegressionChannelCalculator.Trend.DOWN -> "↓"
                    else -> "→"
                }
                val pearsonText = if (lrcPearson) "  R %.2f".format(Locale.US, s.pearsonR) else ""
                text.color = lrcColor(s)
                c.drawText("LRC${s.length} $arrow$pearsonText", rect.left, rect.top + dp(12f), text)
                text.color = color(R.color.text_secondary)
            }
        }
        drawTimeAxis(c,r,rect)
    }

    private fun drawLrc(c: Canvas, r: IntRange, rect: RectF, lo: Double, hi: Double) {
        if (!lrcEnabled) return; val s = lrcSnapshots.getOrNull(r.last) ?: return; val a=max(r.first,s.startIndex); val b=min(r.last,s.endIndex); if(a>=b)return
        val step = rect.width() / r.count()
        fun x(i: Int): Float = rect.left + (i - r.first + .5f) * step
        fun y(v: Double): Float = rect.bottom - ((v - lo) / (hi - lo)).toFloat() * rect.height()
        val col = lrcColor(s)
        listOf(lrcMid,lrcBand,lrcMinor).forEach{it.color=col}; lrcFillPaint.color=col; lrcFillPaint.alpha=26
        if(lrcFill&&lrcSigma2){val p=Path();p.moveTo(x(a),y(s.upperAt(a,2.0)));p.lineTo(x(b),y(s.upperAt(b,2.0)));p.lineTo(x(b),y(s.lowerAt(b,2.0)));p.lineTo(x(a),y(s.lowerAt(a,2.0)));p.close();c.drawPath(p,lrcFillPaint)}
        fun band(k:Double,p:Paint){c.drawLine(x(a),y(s.upperAt(a,k)),x(b),y(s.upperAt(b,k)),p);c.drawLine(x(a),y(s.lowerAt(a,k)),x(b),y(s.lowerAt(b,k)),p)}
        if(lrcSigma3)band(3.0,lrcMinor);if(lrcSigma1)band(1.0,lrcMinor);if(lrcSigma2)band(2.0,lrcBand);c.drawLine(x(a),y(s.regressionAt(a)),x(b),y(s.regressionAt(b)),lrcMid)
    }

    private fun priceScale(r:IntRange):Pair<Double,Double>{val v=mutableListOf<Double>();r.forEach{i->v+=candles[i].low;v+=candles[i].high;ema20.getOrNull(i)?.let(v::add);ema50.getOrNull(i)?.let(v::add);ema200.getOrNull(i)?.let(v::add)};lrcSnapshots.getOrNull(r.last)?.takeIf{lrcEnabled}?.let{s->val a=max(r.first,s.startIndex);val b=min(r.last,s.endIndex);listOf(0.0,1.0,2.0,3.0).forEach{k->if(k==0.0||k==1.0&&lrcSigma1||k==2.0&&lrcSigma2||k==3.0&&lrcSigma3){v+=s.regressionAt(a)+s.sigma*k;v+=s.regressionAt(b)+s.sigma*k;v+=s.regressionAt(a)-s.sigma*k;v+=s.regressionAt(b)-s.sigma*k}}};val lo=v.minOrNull()?:0.0;val hi=v.maxOrNull()?:1.0;val pad=max((hi-lo)*.06,abs(hi)*.002);return max(0.0,lo-pad) to hi+pad}
    private fun drawSeries(c:Canvas,r:IntRange,v:List<Double?>,rect:RectF,scale:Pair<Double,Double>,p:Paint){val span=scale.second-scale.first;val step=rect.width()/r.count();val path=Path();var on=false;r.forEach{i->val q=v.getOrNull(i)?:return@forEach;val xx=rect.left+(i-r.first+.5f)*step;val yy=rect.bottom-((q-scale.first)/span).toFloat()*rect.height();if(!on){path.moveTo(xx,yy);on=true}else path.lineTo(xx,yy)};if(on)c.drawPath(path,p)}
    private fun drawTimeAxis(c:Canvas,r:IntRange,rect:RectF){val span=candles[r.last].timestamp-candles[r.first].timestamp;val f=SimpleDateFormat(if(span<2*DAY)"HH:mm" else if(span<45*DAY)"dd MMM" else "MMM yy",Locale("tr","TR"));repeat(4){k->val frac=k/3f;val i=(r.first+frac*(r.count()-1)).roundToInt();val s=f.format(Date(candles[i].timestamp));c.drawText(s,(rect.left+frac*rect.width()-text.measureText(s)/2).coerceIn(rect.left,rect.right-text.measureText(s)),rect.bottom+dp(13f),text)}}
    private fun drawVolume(c:Canvas,r:IntRange){val rect=volumeRect();val m=r.maxOf{candles[it].volume}.takeIf{it>0}?:return;val step=rect.width()/r.count();r.forEach{i->val q=candles[i];val h=(q.volume/m).toFloat()*rect.height();val p=if(q.close>=q.open)up else down;p.style=Paint.Style.FILL;c.drawRect(rect.left+(i-r.first+.2f)*step,rect.bottom-h,rect.left+(i-r.first+.8f)*step,rect.bottom,p);p.style=Paint.Style.STROKE};c.drawText("HACİM",rect.left,rect.top-dp(5f),text)}
    private fun drawRsi(c:Canvas,r:IntRange){val rect=rsiRect();listOf(70.0,50.0,30.0).forEach{v->val yy=rect.bottom-(v/100).toFloat()*rect.height();c.drawLine(rect.left,yy,rect.right,yy,grid);c.drawText(v.toInt().toString(),rect.right+dp(4f),yy,text)};val step=rect.width()/r.count();val p=Path();var on=false;r.forEach{i->val v=rsi14.getOrNull(i)?:return@forEach;val xx=rect.left+(i-r.first+.5f)*step;val yy=rect.bottom-(v/100).toFloat()*rect.height();if(!on){p.moveTo(xx,yy);on=true}else p.lineTo(xx,yy)};if(on)c.drawPath(p,rsiP);c.drawText("RSI14",rect.left,rect.top-dp(4f),text)}
    private fun drawMacd(c:Canvas,r:IntRange){val rect=macdRect();val vals=mutableListOf<Double>();r.forEach{i->macd.macd.getOrNull(i)?.let(vals::add);macd.signal.getOrNull(i)?.let(vals::add);macd.histogram.getOrNull(i)?.let(vals::add)};if(vals.isEmpty())return;val ma=vals.maxOf{abs(it)}.takeIf{it>0}?:1.0;val mid=rect.centerY();val step=rect.width()/r.count();c.drawLine(rect.left,mid,rect.right,mid,grid);r.forEach{i->macd.histogram.getOrNull(i)?.let{h->val hh=(h/ma).toFloat()*rect.height()*.45f;val p=if(h>=0)up else down;p.style=Paint.Style.FILL;c.drawRect(rect.left+(i-r.first+.25f)*step,min(mid,mid-hh),rect.left+(i-r.first+.75f)*step,max(mid,mid-hh),p);p.style=Paint.Style.STROKE}};drawCentered(c,r,macd.macd,rect,ma,macdP);drawCentered(c,r,macd.signal,rect,ma,sigP);c.drawText("MACD / SIGNAL",rect.left,rect.top-dp(4f),text)}
    private fun drawCentered(c:Canvas,r:IntRange,v:List<Double?>,rect:RectF,ma:Double,p:Paint){val step=rect.width()/r.count();val path=Path();var on=false;r.forEach{i->val q=v.getOrNull(i)?:return@forEach;val xx=rect.left+(i-r.first+.5f)*step;val yy=rect.centerY()-(q/ma).toFloat()*rect.height()*.45f;if(!on){path.moveTo(xx,yy);on=true}else path.lineTo(xx,yy)};if(on)c.drawPath(path,p)}
    private fun drawSelection(c:Canvas,r:IntRange){val i=selectedIndex?:return;if(i !in r)return;val rect=priceRect();val scale=priceScale(r);val step=rect.width()/r.count();val xx=rect.left+(i-r.first+.5f)*step;val yy=rect.bottom-((candles[i].close-scale.first)/(scale.second-scale.first)).toFloat()*rect.height();c.drawLine(xx,rect.top,xx,macdRect().bottom,cross);c.drawLine(rect.left,yy,rect.right,yy,cross);c.drawText(SimpleDateFormat("dd MMM HH:mm",Locale("tr","TR")).format(Date(candles[i].timestamp)),xx,rect.bottom+dp(25f),text)}

    private fun lrcColor(s:LinearRegressionChannelCalculator.Result)=if(!lrcTrendColor)color(R.color.blue) else when(s.trend){LinearRegressionChannelCalculator.Trend.UP->color(R.color.green);LinearRegressionChannelCalculator.Trend.DOWN->color(R.color.red);else->color(R.color.blue)}
    private fun drawMessage(c:Canvas,s:String){c.drawText(s,dp(16f),height/2f,text)}
    private fun visibleCount():Int{if(candles.isEmpty())return 0;val base=min(candles.size,90);return(base/zoom).toInt().coerceIn(min(8,candles.size),candles.size)}
    private fun maxOffset()=(candles.size-visibleCount()).coerceAtLeast(0)
    private fun visibleRange():IntRange?{if(candles.size<2)return null;val n=visibleCount().coerceAtLeast(2);val last=(candles.lastIndex-endOffset).coerceAtLeast(n-1);return(last-n+1).coerceAtLeast(0)..last}
    private fun priceRect()=RectF(dp(8f),dp(34f),width-dp(56f),height*.47f);private fun volumeRect()=RectF(dp(8f),height*.55f,width-dp(56f),height*.66f);private fun rsiRect()=RectF(dp(8f),height*.72f,width-dp(56f),height*.82f);private fun macdRect()=RectF(dp(8f),height*.88f,width-dp(56f),height-dp(8f))
    private fun fmt(v:Double)=when{abs(v)>=1000->"%.0f".format(Locale("tr","TR"),v);abs(v)>=100->"%.1f".format(Locale("tr","TR"),v);else->"%.2f".format(Locale("tr","TR"),v)}
    private fun dp(v:Float)=v*resources.displayMetrics.density;private fun sp(v:Float)=v*resources.displayMetrics.scaledDensity
    companion object{private const val DAY=86_400_000L}
}
