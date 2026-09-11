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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

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
    private val strongText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_primary)
        textSize = sp(10f)
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
    private val lastPricePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.blue)
        strokeWidth = dp(1f)
    }
    private val tooltipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.surface_2)
        style = Paint.Style.FILL
    }
    private val tooltipBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.stroke)
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val oldRange = visibleRange()
            val oldRect = priceRect()
            val focusFraction = if (oldRect.width() > 0f) {
                ((detector.focusX - oldRect.left) / oldRect.width()).coerceIn(0f, 1f)
            } else 0.5f
            val anchorIndex = oldRange?.let {
                (it.first + focusFraction * (it.count() - 1)).roundToInt().coerceIn(it.first, it.last)
            } ?: candles.lastIndex.coerceAtLeast(0)

            zoom = (zoom * detector.scaleFactor).coerceIn(1f, 8f)
            val newCount = visibleCount().coerceAtLeast(2)
            val desiredLast = (anchorIndex + ((1f - focusFraction) * (newCount - 1))).roundToInt()
                .coerceIn(newCount - 1, candles.lastIndex.coerceAtLeast(newCount - 1))
            endOffset = (candles.lastIndex - desiredLast).coerceIn(0, maxOffset())
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
                val perCandle = max(1f, priceRect().width() / count)
                endOffset = (endOffset + (distanceX / perCandle).roundToInt()).coerceIn(0, maxOffset())
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

        drawSectionLabels(canvas, range)
        drawPrice(canvas, range)
        drawTimeAxis(canvas, range)
        drawVolume(canvas, range)
        drawRsi(canvas, range)
        drawMacd(canvas, range)
        drawSelection(canvas, range)
    }

    private fun drawSectionLabels(canvas: Canvas, range: IntRange) {
        text.textSize = sp(10f)
        text.color = ContextCompat.getColor(context, R.color.text_secondary)
        canvas.drawText("FİYAT + EMA20/50/200", dp(10f), priceRect().top - dp(8f), text)
        canvas.drawText("HACİM", dp(10f), volumeRect().top - dp(6f), text)
        canvas.drawText("RSI14", dp(10f), rsiRect().top - dp(6f), text)
        canvas.drawText("MACD / SIGNAL", dp(10f), macdRect().top - dp(6f), text)

        val last = range.last
        val e20 = ema20.getOrNull(last)
        val e50 = ema50.getOrNull(last)
        val e200 = ema200.getOrNull(last)
        strongText.textSize = sp(8.5f)
        var x = priceRect().left
        listOf(
            "E20 ${fmt(e20)}" to ema20Paint.color,
            "E50 ${fmt(e50)}" to ema50Paint.color,
            "E200 ${fmt(e200)}" to ema200Paint.color
        ).forEach { (label, color) ->
            strongText.color = color
            canvas.drawText(label, x, priceRect().top + dp(11f), strongText)
            x += strongText.measureText(label) + dp(10f)
        }
    }

    private fun drawGrid(canvas: Canvas, rect: RectF, lines: Int) {
        for (i in 0..lines) {
            val y = rect.top + rect.height() * i / lines
            canvas.drawLine(rect.left, y, rect.right, y, grid)
        }
    }

    private data class PriceScale(val min: Double, val max: Double, val step: Double)

    private fun priceScale(range: IntRange): PriceScale {
        val values = mutableListOf<Double>()
        range.forEach { i ->
            values += candles[i].low
            values += candles[i].high
            ema20.getOrNull(i)?.let(values::add)
            ema50.getOrNull(i)?.let(values::add)
            ema200.getOrNull(i)?.let(values::add)
        }
        val rawMin = values.minOrNull() ?: 0.0
        val rawMax = values.maxOrNull() ?: 1.0
        val rawRange = (rawMax - rawMin).takeIf { it > 0.0 } ?: max(abs(rawMax) * 0.02, 1.0)
        val padding = rawRange * 0.06
        val paddedMin = max(0.0, rawMin - padding)
        val paddedMax = rawMax + padding
        val step = niceStep((paddedMax - paddedMin) / 5.0)
        val niceMin = floor(paddedMin / step) * step
        val niceMax = ceil(paddedMax / step) * step
        return PriceScale(niceMin, if (niceMax > niceMin) niceMax else niceMin + step, step)
    }

    private fun niceStep(value: Double): Double {
        if (!value.isFinite() || value <= 0.0) return 1.0
        val exp = floor(log10(value))
        val base = 10.0.pow(exp)
        val fraction = value / base
        val niceFraction = when {
            fraction <= 1.0 -> 1.0
            fraction <= 2.0 -> 2.0
            fraction <= 5.0 -> 5.0
            else -> 10.0
        }
        return niceFraction * base
    }

    private fun drawPrice(canvas: Canvas, range: IntRange) {
        val rect = priceRect()
        val scale = priceScale(range)
        val span = (scale.max - scale.min).takeIf { it > 0 } ?: 1.0
        val stepX = rect.width() / range.count().toFloat()
        val bodyW = (stepX * 0.58f).coerceAtLeast(dp(1.5f))

        fun y(v: Double): Float = rect.bottom - (((v - scale.min) / span).toFloat() * rect.height())
        fun x(index: Int): Float = rect.left + (index - range.first + 0.5f) * stepX

        var tick = scale.min
        var guard = 0
        while (tick <= scale.max + scale.step * 0.25 && guard++ < 12) {
            val py = y(tick)
            canvas.drawLine(rect.left, py, rect.right, py, grid)
            text.textSize = sp(8.5f)
            text.color = ContextCompat.getColor(context, R.color.text_secondary)
            canvas.drawText(priceLabel(tick), rect.right + dp(4f), py + dp(3f), text)
            tick += scale.step
        }

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

        drawSeries(canvas, range, ema20, rect, scale.min, scale.max, ema20Paint)
        drawSeries(canvas, range, ema50, rect, scale.min, scale.max, ema50Paint)
        drawSeries(canvas, range, ema200, rect, scale.min, scale.max, ema200Paint)

        val lastClose = candles[range.last].close
        if (lastClose in scale.min..scale.max) {
            val lastY = y(lastClose)
            canvas.drawLine(rect.left, lastY, rect.right, lastY, lastPricePaint)
            strongText.textSize = sp(8.5f)
            strongText.color = ContextCompat.getColor(context, R.color.blue)
            canvas.drawText(priceLabel(lastClose), rect.right + dp(4f), lastY - dp(3f), strongText)
        }
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

    private fun drawTimeAxis(canvas: Canvas, range: IntRange) {
        val rect = priceRect()
        val totalSpan = candles[range.last].timestamp - candles[range.first].timestamp
        val formatter = when {
            totalSpan <= 2L * DAY_MS -> SimpleDateFormat("HH:mm", Locale("tr", "TR"))
            totalSpan <= 45L * DAY_MS -> SimpleDateFormat("dd MMM", Locale("tr", "TR"))
            else -> SimpleDateFormat("MMM yy", Locale("tr", "TR"))
        }
        text.textSize = sp(8f)
        text.color = ContextCompat.getColor(context, R.color.text_secondary)
        val ticks = 4
        for (i in 0 until ticks) {
            val fraction = i.toFloat() / (ticks - 1)
            val index = (range.first + fraction * (range.count() - 1)).roundToInt().coerceIn(range.first, range.last)
            val x = rect.left + fraction * rect.width()
            val label = formatter.format(Date(candles[index].timestamp))
            val w = text.measureText(label)
            val tx = (x - w / 2f).coerceIn(rect.left, rect.right - w)
            canvas.drawText(label, tx, rect.bottom + dp(13f), text)
        }
    }

    private fun drawVolume(canvas: Canvas, range: IntRange) {
        val rect = volumeRect()
        drawGrid(canvas, rect, 2)
        val maxVolume = range.maxOf { candles[it].volume }.takeIf { it > 0.0 } ?: run {
            text.textSize = sp(9f)
            canvas.drawText("Hacim verisi mevcut değil", rect.left, rect.centerY(), text)
            return
        }
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
            text.textSize = sp(8f)
            text.color = ContextCompat.getColor(context, R.color.text_muted)
            canvas.drawText(level.toInt().toString(), rect.right + dp(4f), y + dp(3f), text)
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
        rsi14.getOrNull(range.last)?.let {
            strongText.textSize = sp(8.5f)
            strongText.color = rsiPaint.color
            canvas.drawText("RSI ${String.format("%.1f", it)}", rect.left, rect.top + dp(10f), strongText)
        }
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
        if (values.isEmpty()) {
            text.textSize = sp(9f)
            canvas.drawText("MACD için yeterli veri yok", rect.left, rect.centerY(), text)
            return
        }
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

        val m = macd.macd.getOrNull(range.last)
        val s = macd.signal.getOrNull(range.last)
        if (m != null || s != null) {
            strongText.textSize = sp(8.5f)
            strongText.color = macdPaint.color
            canvas.drawText("M ${fmt(m)}", rect.left, rect.top + dp(10f), strongText)
            strongText.color = signalPaint.color
            canvas.drawText("S ${fmt(s)}", rect.left + dp(70f), rect.top + dp(10f), strongText)
        }
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
        val scale = priceScale(range)
        val c = candles[selected]
        val step = rect.width() / range.count().toFloat()
        val x = rect.left + (selected - range.first + 0.5f) * step
        val span = scale.max - scale.min
        val y = rect.bottom - (((c.close - scale.min) / span).toFloat() * rect.height())
        canvas.drawLine(x, priceRect().top, x, macdRect().bottom, crosshair)
        canvas.drawLine(rect.left, y, rect.right, y, crosshair)

        val date = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("tr", "TR")).format(Date(c.timestamp))
        val lines = listOf(
            date,
            "A ${priceLabel(c.open)}  Y ${priceLabel(c.high)}",
            "D ${priceLabel(c.low)}  K ${priceLabel(c.close)}",
            "Hacim ${volumeLabel(c.volume)}"
        )
        strongText.textSize = sp(8.5f)
        strongText.color = ContextCompat.getColor(context, R.color.text_primary)
        val boxW = lines.maxOf { strongText.measureText(it) } + dp(16f)
        val boxH = dp(58f)
        val left = if (x < rect.centerX()) rect.right - boxW - dp(4f) else rect.left + dp(4f)
        val top = rect.top + dp(18f)
        val box = RectF(left, top, left + boxW, top + boxH)
        canvas.drawRoundRect(box, dp(7f), dp(7f), tooltipPaint)
        canvas.drawRoundRect(box, dp(7f), dp(7f), tooltipBorder)
        lines.forEachIndexed { i, line ->
            canvas.drawText(line, box.left + dp(8f), box.top + dp(13f) + i * dp(12f), strongText)
        }

        text.textSize = sp(8f)
        val dateShort = SimpleDateFormat("dd MMM HH:mm", Locale("tr", "TR")).format(Date(c.timestamp))
        val dateW = text.measureText(dateShort)
        val tx = (x - dateW / 2f).coerceIn(rect.left, rect.right - dateW)
        canvas.drawText(dateShort, tx, rect.bottom + dp(25f), text)
        canvas.drawText(priceLabel(c.close), rect.right + dp(4f), y + dp(3f), text)
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

    private fun priceRect() = RectF(dp(8f), dp(34f), width - dp(56f), height * 0.47f)
    private fun volumeRect() = RectF(dp(8f), height * 0.55f, width - dp(56f), height * 0.66f)
    private fun rsiRect() = RectF(dp(8f), height * 0.72f, width - dp(56f), height * 0.82f)
    private fun macdRect() = RectF(dp(8f), height * 0.88f, width - dp(56f), height - dp(8f))

    private fun linePaint(colorRes: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, colorRes)
        style = Paint.Style.STROKE
        strokeWidth = dp(1.4f)
    }

    private fun fmt(v: Double?): String = v?.takeIf { it.isFinite() }?.let { priceLabel(it) } ?: "—"

    private fun priceLabel(v: Double): String = when {
        abs(v) >= 1000.0 -> String.format(Locale("tr", "TR"), "%.0f", v)
        abs(v) >= 100.0 -> String.format(Locale("tr", "TR"), "%.1f", v)
        else -> String.format(Locale("tr", "TR"), "%.2f", v)
    }

    private fun volumeLabel(v: Double): String = when {
        v >= 1_000_000_000 -> String.format(Locale("tr", "TR"), "%.2f Mr", v / 1_000_000_000.0)
        v >= 1_000_000 -> String.format(Locale("tr", "TR"), "%.2f Mn", v / 1_000_000.0)
        v >= 1_000 -> String.format(Locale("tr", "TR"), "%.1f B", v / 1_000.0)
        else -> String.format(Locale("tr", "TR"), "%.0f", v)
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun sp(v: Float) = v * resources.displayMetrics.scaledDensity

    companion object {
        private const val DAY_MS = 86_400_000L
    }
}
