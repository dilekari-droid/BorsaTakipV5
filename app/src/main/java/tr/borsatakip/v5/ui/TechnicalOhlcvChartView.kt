package tr.borsatakip.v5.ui

import android.content.Context
import android.graphics.Canvas
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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class TechnicalOhlcvChartView(context: Context, attrs: android.util.AttributeSet? = null) : View(context, attrs) {
    var candles: List<Candle> = emptyList()
        private set

    var onCandleSelected: ((Candle?) -> Unit)? = null

    private var ema20: List<Double?> = emptyList()
    private var ema50: List<Double?> = emptyList()
    private var ema200: List<Double?> = emptyList()
    private var rsi14: List<Double?> = emptyList()
    private var macd: ChartMath.MacdSeries = ChartMath.MacdSeries(emptyList(), emptyList(), emptyList())

    private var zoom = 1f
    private var endOffset = 0
    private var selectedIndex: Int? = null

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_secondary)
        textSize = sp(11f)
    }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.stroke)
        strokeWidth = dp(1f)
    }
    private val up = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.green)
        strokeWidth = dp(1.4f)
    }
    private val down = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.red)
        strokeWidth = dp(1.4f)
    }
    private val ema20Paint = linePaint(R.color.yellow)
    private val ema50Paint = linePaint(R.color.blue)
    private val ema200Paint = linePaint(R.color.purple)
    private val rsiPaint = linePaint(R.color.yellow)
    private val macdPaint = linePaint(R.color.blue)
    private val signalPaint = linePaint(R.color.orange)
    private val crosshair = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_muted)
        strokeWidth = dp(1f)
    }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoom = (zoom * detector.scaleFactor).coerceIn(1f, 8f)
            endOffset = endOffset.coerceIn(0, maxOffset())
            invalidate()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (abs(distanceX) > abs(distanceY)) {
                parent?.requestDisallowInterceptTouchEvent(true)
                val count = visibleCount().coerceAtLeast(2)
                val perCandle = max(1f, width.toFloat() / count)
                endOffset = (endOffset + (distanceX / perCandle).toInt()).coerceIn(0, maxOffset())
                selectedIndex = null
                onCandleSelected?.invoke(null)
                invalidate()
                return true
            }
            return false
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val range = visibleRange() ?: return false
            val panel = priceRect()
            if (!panel.contains(e.x, e.y)) return false
            val local = ((e.x - panel.left) / panel.width()).coerceIn(0f, 0.9999f)
            val index = range.first + (local * range.count()).toInt()
            selectedIndex = index.coerceIn(range.first, range.last)
            onCandleSelected?.invoke(candles[selectedIndex!!])
            invalidate()
            return true
        }
    })

    fun setCandles(input: List<Candle>) {
        val checked = ChartMath.validate(input)
        candles = checked.candles
        val closes = candles.map { it.close }
        ema20 = ChartMath.ema(closes, 20)
        ema50 = ChartMath.ema(closes, 50)
        ema200 = ChartMath.ema(closes, 200)
        rsi14 = ChartMath.rsi(closes, 14)
        macd = ChartMath.macd(closes)
        zoom = 1f
        endOffset = 0
        selectedIndex = null
        onCandleSelected?.invoke(null)
        invalidate()
    }

    fun currentEma20(): Double? = ema20.lastOrNull { it != null }
    fun currentEma50(): Double? = ema50.lastOrNull { it != null }
    fun currentEma200(): Double? = ema200.lastOrNull { it != null }
    fun currentRsi14(): Double? = rsi14.lastOrNull { it != null }
    fun currentMacd(): Double? = macd.macd.lastOrNull { it != null }
    fun currentMacdSignal(): Double? = macd.signal.lastOrNull { it != null }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val scaled = scaleDetector.onTouchEvent(event)
        val gestured = gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return scaled || gestured || true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val range = visibleRange()
        if (range == null || range.count() < 2) {
            text.textSize = sp(13f)
            canvas.drawText("Grafik verisi alınamadı.", dp(16f), height / 2f, text)
            return
        }

        drawSectionLabels(canvas)
        drawGrid(canvas, priceRect(), 4)
        drawPrice(canvas, range)
        drawVolume(canvas, range)
        drawRsi(canvas, range)
        drawMacd(canvas, range)
        drawSelection(canvas, range)
    }

    private fun drawSectionLabels(canvas: Canvas) {
        text.textSize = sp(10f)
        text.color = ContextCompat.getColor(context, R.color.text_secondary)
        canvas.drawText("FİYAT + EMA20/50/200", dp(10f), priceRect().top - dp(5f), text)
        canvas.drawText("HACİM", dp(10f), volumeRect().top - dp(5f), text)
        canvas.drawText("RSI14", dp(10f), rsiRect().top - dp(5f), text)
        canvas.drawText("MACD / SIGNAL", dp(10f), macdRect().top - dp(5f), text)
    }

    private fun drawGrid(canvas: Canvas, rect: RectF, lines: Int) {
        for (i in 0..lines) {
            val y = rect.top + rect.height() * i / lines
            canvas.drawLine(rect.left, y, rect.right, y, grid)
        }
    }

    private fun drawPrice(canvas: Canvas, range: IntRange) {
        val rect = priceRect()
        val slice = range.map { candles[it] }
        val minPrice = slice.minOf { it.low }
        val maxPrice = slice.maxOf { it.high }
        val span = (maxPrice - minPrice).takeIf { it > 0 } ?: 1.0
        val step = rect.width() / range.count().toFloat()
        val bodyW = (step * 0.58f).coerceAtLeast(dp(1.5f))

        fun y(v: Double): Float = rect.bottom - (((v - minPrice) / span).toFloat() * rect.height())
        fun x(index: Int): Float = rect.left + (index - range.first + 0.5f) * step

        range.forEach { index ->
            val c = candles[index]
            val paint = if (c.close >= c.open) up else down
            val cx = x(index)
            canvas.drawLine(cx, y(c.high), cx, y(c.low), paint)
            val top = y(max(c.open, c.close))
            val bottom = y(min(c.open, c.close))
            val body = RectF(cx - bodyW / 2f, top, cx + bodyW / 2f, max(bottom, top + dp(1f)))
            paint.style = Paint.Style.FILL
            canvas.drawRect(body, paint)
        }

        drawSeries(canvas, range, ema20, rect, minPrice, maxPrice, ema20Paint)
        drawSeries(canvas, range, ema50, rect, minPrice, maxPrice, ema50Paint)
        drawSeries(canvas, range, ema200, rect, minPrice, maxPrice, ema200Paint)

        text.textSize = sp(9f)
        text.color = ContextCompat.getColor(context, R.color.text_secondary)
        canvas.drawText(String.format("%.2f", maxPrice), rect.left + dp(3f), rect.top + dp(11f), text)
        canvas.drawText(String.format("%.2f", minPrice), rect.left + dp(3f), rect.bottom - dp(3f), text)
    }

    private fun drawSeries(
        canvas: Canvas,
        range: IntRange,
        values: List<Double?>,
        rect: RectF,
        minValue: Double,
        maxValue: Double,
        paint: Paint
    ) {
        if (values.isEmpty()) return
        val span = (maxValue - minValue).takeIf { it > 0 } ?: 1.0
        val step = rect.width() / range.count().toFloat()
        val path = Path()
        var started = false
        range.forEach { index ->
            val value = values.getOrNull(index) ?: return@forEach
            val x = rect.left + (index - range.first + 0.5f) * step
            val y = rect.bottom - (((value - minValue) / span).toFloat() * rect.height())
            if (!started) {
                path.moveTo(x, y)
                started = true
            } else path.lineTo(x, y)
        }
        if (started) canvas.drawPath(path, paint)
    }

    private fun drawVolume(canvas: Canvas, range: IntRange) {
        val rect = volumeRect()
        drawGrid(canvas, rect, 2)
        val maxVolume = range.maxOf { candles[it].volume }.takeIf { it > 0.0 } ?: return
        val step = rect.width() / range.count().toFloat()
        range.forEach { index ->
            val c = candles[index]
            val h = (c.volume / maxVolume).toFloat() * rect.height()
            val x = rect.left + (index - range.first + 0.2f) * step
            val paint = if (c.close >= c.open) up else down
            canvas.drawRect(x, rect.bottom - h, x + step * 0.6f, rect.bottom, paint)
        }
    }

    private fun drawRsi(canvas: Canvas, range: IntRange) {
        val rect = rsiRect()
        listOf(70.0, 50.0, 30.0).forEach { level ->
            val y = rect.bottom - (level / 100.0).toFloat() * rect.height()
            canvas.drawLine(rect.left, y, rect.right, y, grid)
        }
        val step = rect.width() / range.count().toFloat()
        val path = Path()
        var started = false
        range.forEach { index ->
            val value = rsi14.getOrNull(index) ?: return@forEach
            val x = rect.left + (index - range.first + 0.5f) * step
            val y = rect.bottom - (value / 100.0).toFloat() * rect.height()
            if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
        }
        if (started) canvas.drawPath(path, rsiPaint)
    }

    private fun drawMacd(canvas: Canvas, range: IntRange) {
        val rect = macdRect()
        val values = buildList {
            range.forEach { i ->
                macd.macd.getOrNull(i)?.let(::add)
                macd.signal.getOrNull(i)?.let(::add)
                macd.histogram.getOrNull(i)?.let(::add)
            }
        }
        if (values.isEmpty()) return
        val maxAbs = values.maxOf { abs(it) }.takeIf { it > 0.0 } ?: 1.0
        val mid = rect.centerY()
        canvas.drawLine(rect.left, mid, rect.right, mid, grid)
        val step = rect.width() / range.count().toFloat()

        range.forEach { index ->
            val h = macd.histogram.getOrNull(index) ?: return@forEach
            val scaled = (h / maxAbs).toFloat() * rect.height() * 0.45f
            val x = rect.left + (index - range.first + 0.25f) * step
            val paint = if (h >= 0) up else down
            canvas.drawRect(x, min(mid, mid - scaled), x + step * 0.5f, max(mid, mid - scaled), paint)
        }

        drawCenteredSeries(canvas, range, macd.macd, rect, maxAbs, macdPaint)
        drawCenteredSeries(canvas, range, macd.signal, rect, maxAbs, signalPaint)
    }

    private fun drawCenteredSeries(canvas: Canvas, range: IntRange, values: List<Double?>, rect: RectF, maxAbs: Double, paint: Paint) {
        val step = rect.width() / range.count().toFloat()
        val path = Path()
        var started = false
        range.forEach { index ->
            val v = values.getOrNull(index) ?: return@forEach
            val x = rect.left + (index - range.first + 0.5f) * step
            val y = rect.centerY() - (v / maxAbs).toFloat() * rect.height() * 0.45f
            if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
        }
        if (started) canvas.drawPath(path, paint)
    }

    private fun drawSelection(canvas: Canvas, range: IntRange) {
        val selected = selectedIndex ?: return
        if (selected !in range) return
        val rect = priceRect()
        val step = rect.width() / range.count().toFloat()
        val x = rect.left + (selected - range.first + 0.5f) * step
        canvas.drawLine(x, priceRect().top, x, macdRect().bottom, crosshair)
    }

    private fun visibleCount(): Int {
        if (candles.isEmpty()) return 0
        val base = min(candles.size, 90)
        return (base / zoom).toInt().coerceIn(min(8, candles.size), candles.size)
    }

    private fun maxOffset(): Int = (candles.size - visibleCount()).coerceAtLeast(0)

    private fun visibleRange(): IntRange? {
        if (candles.size < 2) return null
        val count = visibleCount().coerceAtLeast(2)
        val last = (candles.lastIndex - endOffset).coerceAtLeast(count - 1)
        val first = (last - count + 1).coerceAtLeast(0)
        return first..last
    }

    private fun priceRect() = RectF(dp(8f), dp(28f), width - dp(8f), height * 0.49f)
    private fun volumeRect() = RectF(dp(8f), height * 0.55f, width - dp(8f), height * 0.66f)
    private fun rsiRect() = RectF(dp(8f), height * 0.72f, width - dp(8f), height * 0.82f)
    private fun macdRect() = RectF(dp(8f), height * 0.88f, width - dp(8f), height - dp(8f))

    private fun linePaint(colorRes: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, colorRes)
        style = Paint.Style.STROKE
        strokeWidth = dp(1.4f)
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun sp(v: Float) = v * resources.displayMetrics.scaledDensity
}
