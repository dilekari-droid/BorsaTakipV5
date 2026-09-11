package tr.borsatakip.v5.ui

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import tr.borsatakip.v5.R
import tr.borsatakip.v5.data.ChartDataRepository
import tr.borsatakip.v5.data.ChartDataSeries
import tr.borsatakip.v5.data.ChartPeriod
import tr.borsatakip.v5.data.SettingsStore
import tr.borsatakip.v5.model.Candle
import tr.borsatakip.v5.ui.chart.ChartMath
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StockDetailActivity : BaseActivity() {
    private var chartJob: Job? = null
    private lateinit var chart: TechnicalOhlcvChartView
    private lateinit var chartState: TextView
    private lateinit var chartMeta: TextView
    private lateinit var selectedCandle: TextView
    private lateinit var details: TextView
    private lateinit var repository: ChartDataRepository
    private lateinit var periodButtons: Map<ChartPeriod, Button>
    private var currentSeries: ChartDataSeries? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stock_detail)
        setupBottomNav()
        val x = AppSession.selected ?: return

        repository = ChartDataRepository(this)
        chart = findViewById(R.id.ohlcvChart)
        chartState = findViewById(R.id.chartState)
        chartMeta = findViewById(R.id.chartMeta)
        selectedCandle = findViewById(R.id.selectedCandle)
        details = findViewById(R.id.details)
        applyLrcSettings()
        periodButtons = mapOf(
            ChartPeriod.THREE_MINUTES to findViewById(R.id.period3min),
            ChartPeriod.FIVE_MINUTES to findViewById(R.id.period5min),
            ChartPeriod.FIFTEEN_MINUTES to findViewById(R.id.period15min),
            ChartPeriod.ONE_HOUR to findViewById(R.id.period1hour),
            ChartPeriod.ONE_DAY to findViewById(R.id.period1day),
            ChartPeriod.ALL_TIME to findViewById(R.id.periodAll)
        )

        findViewById<TextView>(R.id.title).text = x.symbol
        findViewById<TextView>(R.id.subtitle).text =
            "%.2f • Günlük %+.2f • ${x.direction} • Nihai ${x.finalSignalScore}/100 • Risk ${x.riskScore}/100"
                .format(x.price, x.dailyChangePct)

        periodButtons.forEach { (period, button) ->
            button.contentDescription = "${period.label} OHLCV görünümünü aç"
            button.setOnClickListener { loadPeriod(period) }
        }

        chart.onCandleSelected = { candle ->
            selectedCandle.text = candle?.let { formatCandle(it) }
                ?: "Bir muma dokunarak OHLCV ayrıntısını görüntüleyin. Yatay kaydırma ve yakınlaştırma desteklenir."
        }

        renderLegacyTechnicalSummary()
        loadPeriod(ChartPeriod.ONE_DAY)
    }

    private fun loadPeriod(period: ChartPeriod) {
        val x = AppSession.selected ?: return
        chartJob?.cancel()
        markSelectedPeriod(period)
        chartState.text = "${period.label} gerçek OHLCV verisi alınıyor..."
        chartMeta.text = "Kaynak doğrulanıyor. Sahte grafik üretilmez."

        chartJob = lifecycleScope.launch {
            val result = repository.load(x.symbol, period)
            val series = result.getOrNull() ?: if (period == ChartPeriod.ONE_DAY && x.candles.isNotEmpty()) {
                val checked = ChartMath.validate(x.candles)
                ChartDataSeries(
                    symbol = x.symbol,
                    candles = checked.candles,
                    source = x.source,
                    period = period,
                    dataTimestamp = x.dataTimestamp,
                    isRealtime = x.isRealtime,
                    delaySeconds = x.delaySeconds,
                    currentSessionIncluded = x.currentSessionIncluded,
                    rejectedCount = checked.rejectedCount,
                    duplicateCount = checked.duplicateCount
                )
            } else null

            if (series == null || series.candles.size < 2) {
                currentSeries = null
                chart.setCandles(emptyList())
                chartState.text = result.exceptionOrNull()?.message ?: "Grafik verisi alınamadı."
                chartMeta.text = "Görünüm: ${period.label} • Grafik verisi alınamadı. Sahte mum veya gösterge üretilmedi."
                renderChartIndicatorSummary(null)
                return@launch
            }

            currentSeries = series
            applyLrcSettings()
            chart.setCandles(series.candles)
            val status = dataStatus(series)
            chartState.text = buildString {
                append("${series.candles.size} gerçek mum doğrulandı")
                if (period.aggregateMinutes != null) append(" • ${period.aggregateMinutes} dakikalık mumlar gerçek 1 dakikalık OHLCV'den birleştirildi")
                if (series.rejectedCount > 0) append(" • ${series.rejectedCount} geçersiz kayıt çizilmedi")
                if (series.duplicateCount > 0) append(" • ${series.duplicateCount} tekrar kayıt birleştirildi")
            }
            chartMeta.text = buildString {
                append("Görünüm: ${period.label} • Kaynak: ${series.source} • Durum: $status\n")
                append("Grafik verisi ve EMA/RSI/MACD/Hacim/LRC aynı doğrulanmış OHLCV dizisinden hesaplanır.")
                chart.currentLrcStatus()?.let { lrc ->
                    append("\nLRC${lrc.length}: ${trendLabel(lrc.trend)}")
                    if (lrc.pearsonVisible) append(" • R ${"%.2f".format(Locale.US, lrc.pearsonR)}")
                    lrc.breakout?.let { append(" • UYARI: $it") }
                }
            }
            renderChartIndicatorSummary(series)
        }
    }

    private fun markSelectedPeriod(selected: ChartPeriod) {
        periodButtons.forEach { (period, button) ->
            button.alpha = if (period == selected) 1f else 0.62f
            button.isSelected = period == selected
        }
    }

    private fun dataStatus(series: ChartDataSeries): String {
        val realtime = series.isRealtime && series.currentSessionIncluded &&
            series.delaySeconds != null && series.delaySeconds in 0..5
        return if (realtime) "CANLI" else "YEDEK / GECİKMELİ – CANLI DEĞİL"
    }

    private fun renderChartIndicatorSummary(series: ChartDataSeries?) {
        val x = AppSession.selected ?: return
        if (series == null) {
            details.text = "Teknik göstergeleri hesaplamak için yeterli grafik verisi yok.\n\n" + legacyTechnicalText()
            return
        }

        val candles = series.candles
        val volumeAvailable = candles.any { it.volume > 0.0 }
        details.text = buildString {
            append("SEÇİLİ GÖRÜNÜM TEKNİKLERİ (${series.period.label})\n")
            append("EMA20   ${fmt(chart.currentEma20())}\n")
            append("EMA50   ${fmt(chart.currentEma50())}\n")
            append("EMA200  ${fmt(chart.currentEma200())}\n")
            append("RSI14   ${fmt(chart.currentRsi14())}\n")
            append("MACD    ${fmt(chart.currentMacd())}\n")
            append("Sinyal  ${fmt(chart.currentMacdSignal())}\n")
            append("Hacim   ${if (volumeAvailable) "OHLCV kaynağından" else "Hacim verisi mevcut değil"}\n")
            val lrc = chart.currentLrcStatus()
            if (lrc != null) {
                append("\nLRC${lrc.length}  ${trendLabel(lrc.trend)}\n")
                append("Eğim    ${"%.6f".format(Locale.US, lrc.slope)}\n")
                if (lrc.pearsonVisible) append("Pearson R  ${"%.3f".format(Locale.US, lrc.pearsonR)}\n")
                append("σ       ${fmt(lrc.sigma)} • ±2σ kanal genişliği ${fmt(lrc.channelWidth2Sigma)}\n")
                append("Orta çizgi uzaklığı ${fmt(lrc.distanceToMid)}\n")
                append("Fiyat bölgesi ${lrc.zone}\n")
                lrc.breakout?.let {
                    append("UYARI: Fiyat $it • ${lrc.momentumConfirmation}\n")
                    append("Not: LRC kanal dışı hareket tek başına AL/SAT sinyali değildir.\n")
                }
            } else {
                append("\nLRC: veri sayısı ayarlanan periyot için yetersiz veya LRC kapalı.\n")
            }
            if (candles.size < 35) append("MACD için yeterli veri olmayabilir.\n")
            if (candles.size < 200) append("EMA200 için yeterli veri yok.\n")
            append("\nTARAMA SİNYAL ÖZETİ\n")
            append("${x.direction} • Nihai ${x.finalSignalScore}/100 • Risk ${x.riskScore}/100\n\n")
            append(legacyTechnicalText())
        }
    }

    private fun applyLrcSettings() {
        if (!::chart.isInitialized) return
        val s = SettingsStore(this)
        chart.setLrcOptions(
            enabled = s.lrcEnabled,
            length = s.lrcLength,
            sigma1 = s.lrcSigma1Enabled,
            sigma2 = s.lrcSigma2Enabled,
            sigma3 = s.lrcSigma3Enabled,
            trendColor = s.lrcTrendColorEnabled,
            pearson = s.lrcPearsonEnabled,
            fill = s.lrcFillEnabled,
            breakoutWarning = s.lrcBreakoutWarningEnabled
        )
    }

    private fun trendLabel(trend: tr.borsatakip.v5.ui.chart.LinearRegressionChannelCalculator.Trend): String = when (trend) {
        tr.borsatakip.v5.ui.chart.LinearRegressionChannelCalculator.Trend.UP -> "↑ Yükselen"
        tr.borsatakip.v5.ui.chart.LinearRegressionChannelCalculator.Trend.DOWN -> "↓ Düşen"
        tr.borsatakip.v5.ui.chart.LinearRegressionChannelCalculator.Trend.FLAT -> "→ Yatay"
    }

    override fun onResume() {
        super.onResume()
        if (::chart.isInitialized) {
            applyLrcSettings()
            currentSeries?.let { renderChartIndicatorSummary(it) }
        }
    }

    private fun renderLegacyTechnicalSummary() {
        details.text = legacyTechnicalText()
    }

    private fun legacyTechnicalText(): String {
        val x = AppSession.selected ?: return ""
        val t = x.technical
        return buildString {
            append("TARAMA TEKNİK ANLIK GÖRÜNÜMÜ\n")
            append("Trend: EMA20 ${fmt(t.ema20)} • EMA50 ${fmt(t.ema50)} • EMA200 ${fmt(t.ema200)}\n")
            append("Momentum: RSI14 ${fmt(t.rsi14)} • MACD ${fmt(t.macd)} • Sinyal ${fmt(t.macdSignal)}\n")
            append("ATR14 ${fmt(t.atr14)} • VWMA20 ${fmt(t.vwma)}\n")
            append("Destek ${fmt(t.support)} • Direnç ${fmt(t.resistance)}\n")
            append("Veri Güveni ${x.dataConfidenceScore}/100\n")
            append("Kaynak: ${x.source}\n")
            append("Veri zamanı: ${SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("tr", "TR")).format(Date(x.dataTimestamp))}")
        }
    }

    private fun formatCandle(c: Candle): String {
        val df = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("tr", "TR"))
        return buildString {
            append("${df.format(Date(c.timestamp))}\n")
            append("Açılış: ${fmt(c.open)} • Yüksek: ${fmt(c.high)}\n")
            append("Düşük: ${fmt(c.low)} • Kapanış: ${fmt(c.close)}\n")
            append("Hacim: ${if (c.volume > 0.0) "%.0f".format(c.volume) else "Veri yok"}")
        }
    }

    private fun fmt(v: Double?) = v?.takeIf { it.isFinite() }?.let { "%.2f".format(it) } ?: "Veri yok"

    override fun onDestroy() {
        chartJob?.cancel()
        chartJob = null
        super.onDestroy()
    }
}
