package tr.borsatakip.v5.ui

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import tr.borsatakip.v5.R
import tr.borsatakip.v5.data.ChartDataRepository
import tr.borsatakip.v5.data.ChartDataSeries
import tr.borsatakip.v5.data.ChartPeriod
import tr.borsatakip.v5.data.SettingsStore
import tr.borsatakip.v5.model.Candle
import tr.borsatakip.v5.model.Opportunity
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

        renderHeaderAndDecisionCards(x)

        periodButtons.forEach { (period, button) ->
            button.contentDescription = "${period.label} OHLCV görünümünü aç"
            button.setOnClickListener { loadPeriod(period) }
        }

        chart.onCandleSelected = { candle ->
            selectedCandle.text = candle?.let { formatCandle(it) }
                ?: "Bir muma dokunarak OHLCV ayrıntısını görüntüleyin. Yatay kaydırma ve yakınlaştırma desteklenir."
        }

        renderLegacyTechnicalSummary()
        renderVolumeAnalysis(x.candles)
        renderDataQuality(x)
        loadPeriod(ChartPeriod.ONE_DAY)
    }

    private fun renderHeaderAndDecisionCards(x: Opportunity) {
        val timeText = if (x.dataTimestamp > 0L) {
            SimpleDateFormat("HH:mm:ss", Locale("tr", "TR")).format(Date(x.dataTimestamp))
        } else "Bilinmiyor"
        val company = x.companyName?.takeIf { it.isNotBlank() } ?: "Şirket adı mevcut değil"
        findViewById<TextView>(R.id.title).text = x.symbol
        findViewById<TextView>(R.id.subtitle).text = buildString {
            append(company)
            append("\nBIST • Son Güncelleme: $timeText")
            append("\n${"%.2f".format(x.price)} • Günlük ${"%+.2f%%".format(x.dailyChangePct)}")
        }

        val direction = x.direction.uppercase(Locale.ROOT)
        val directionColor = when (direction) {
            "LONG" -> ContextCompat.getColor(this, R.color.green)
            "SHORT" -> ContextCompat.getColor(this, R.color.red)
            else -> ContextCompat.getColor(this, R.color.yellow)
        }
        findViewById<TextView>(R.id.signalLabel).apply {
            text = "ANA SİNYAL • $direction"
            setTextColor(directionColor)
        }
        findViewById<TextView>(R.id.signalScores).text = buildString {
            append("Teknik Uyum Skoru  ${x.finalSignalScore}/100\n")
            append("Teknik Skor        ${x.score}/100\n")
            append("Risk               ${x.riskScore}/100\n")
            append("Veri Güveni        ${x.dataConfidenceScore}/100 (${x.dataConfidenceLabel})")
        }

        findViewById<TextView>(R.id.generalOverview).text = buildString {
            append("TREND      ${trendFromEma(x)}\n")
            append("MOMENTUM   ${momentumLabel(x)}\n")
            append("HACİM      ${volumeState(x)}\n")
            append("RİSK       ${riskLabel(x.riskScore)}")
        }

        findViewById<TextView>(R.id.signalReasons).text = signalReasons(x)
        findViewById<TextView>(R.id.supportResistance).text = buildString {
            append("DESTEK         ${fmt(x.support)}\n")
            append("ANA DESTEK     Veri yok\n")
            append("MEVCUT FİYAT   ${fmt(x.price)}\n")
            append("DİRENÇ         ${fmt(x.resistance)}\n")
            append("ANA DİRENÇ     Veri yok")
        }
    }

    private fun signalReasons(x: Opportunity): String {
        val t = x.technical
        val items = mutableListOf<String>()
        if (t.ema20 != null && t.ema50 != null && t.ema20.isFinite() && t.ema50.isFinite()) {
            items += if (t.ema20 > t.ema50) {
                "• EMA20 > EMA50\n  Kısa vadeli trend pozitif"
            } else if (t.ema20 < t.ema50) {
                "• EMA20 < EMA50\n  Kısa vadeli trend negatif"
            } else {
                "• EMA20 = EMA50\n  Trend ayrışması yok"
            }
        }
        t.rsi14?.takeIf { it.isFinite() }?.let { rsi ->
            val text = when {
                rsi >= 70.0 -> "Aşırı alım bölgesine yakın/üzerinde"
                rsi >= 55.0 -> "Pozitif momentum"
                rsi <= 30.0 -> "Aşırı satım bölgesine yakın/altında"
                rsi <= 45.0 -> "Zayıf momentum"
                else -> "Nötr momentum"
            }
            items += "• RSI14 ${"%.1f".format(rsi)}\n  $text"
        }
        if (t.macd?.isFinite() == true && t.macdSignal?.isFinite() == true) {
            items += if (t.macd > t.macdSignal) {
                "• MACD > Sinyal\n  Momentum pozitif"
            } else {
                "• MACD < Sinyal\n  Momentum negatif"
            }
        }
        t.volumeRatio?.takeIf { it.isFinite() && it >= 0.0 }?.let { ratio ->
            items += "• Hacim oranı ${"%.2f".format(ratio)}x\n  ${volumeState(x)}"
        }
        x.resistance?.takeIf { it.isFinite() && it > 0.0 }?.let { resistance ->
            val dist = ((resistance / x.price) - 1.0) * 100.0
            if (dist.isFinite() && dist in 0.0..3.0) items += "• Direnç bölgesine yakın\n  Kısa vadeli risk mevcut"
        }
        if (items.isEmpty()) return "Veri yetersiz"
        return items.joinToString("\n\n")
    }

    private fun trendFromEma(x: Opportunity): String {
        val t = x.technical
        val e20 = t.ema20
        val e50 = t.ema50
        val e200 = t.ema200
        if (e20 == null || e50 == null || e200 == null || !e20.isFinite() || !e50.isFinite() || !e200.isFinite()) return "Veri yetersiz"
        return when {
            x.price > e20 && e20 > e50 && e50 > e200 -> "Pozitif"
            x.price < e20 && e20 < e50 && e50 < e200 -> "Negatif"
            else -> "Nötr / Karma"
        }
    }

    private fun momentumLabel(x: Opportunity): String {
        val rsi = x.technical.rsi14 ?: return "Veri yetersiz"
        if (!rsi.isFinite()) return "Veri yetersiz"
        return when {
            rsi >= 60.0 -> "Güçlü"
            rsi >= 45.0 -> "Orta"
            else -> "Zayıf"
        }
    }

    private fun volumeState(x: Opportunity): String {
        val ratio = x.technical.volumeRatio ?: return "Veri yetersiz"
        if (!ratio.isFinite()) return "Veri yetersiz"
        return when {
            ratio >= 1.5 -> "Yüksek"
            ratio >= 0.8 -> "Normal"
            else -> "Düşük"
        }
    }

    private fun riskLabel(risk: Int): String = when {
        risk <= 30 -> "Düşük"
        risk <= 60 -> "Orta"
        else -> "Yüksek"
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
                renderVolumeAnalysis(emptyList())
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
            renderVolumeAnalysis(series.candles)
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
        details.text = buildString {
            append("RSI14     ${fmt(chart.currentRsi14())}\n")
            append("MACD      ${fmt(chart.currentMacd())} / Sinyal ${fmt(chart.currentMacdSignal())}\n")
            append("EMA20     ${fmt(chart.currentEma20())}\n")
            append("EMA50     ${fmt(chart.currentEma50())}\n")
            append("EMA200    ${fmt(chart.currentEma200())}\n")
            append("Bollinger ${if (x.technical.bbUpper != null && x.technical.bbLower != null) "Üst ${fmt(x.technical.bbUpper)} • Alt ${fmt(x.technical.bbLower)}" else "Veri yok"}\n")
            append("ATR14     ${fmt(x.technical.atr14)}\n")
            append("VWAP      ${fmt(x.technical.vwap)}")
            val lrc = chart.currentLrcStatus()
            if (lrc != null) {
                append("\n\nLRC${lrc.length}  ${trendLabel(lrc.trend)}")
                if (lrc.pearsonVisible) append(" • Pearson R ${"%.3f".format(Locale.US, lrc.pearsonR)}")
                append("\n±2σ kanal genişliği ${fmt(lrc.channelWidth2Sigma)}")
                lrc.breakout?.let { append("\nUYARI: Fiyat $it • ${lrc.momentumConfirmation}") }
            } else {
                append("\n\nLRC: Veri yetersiz veya kapalı")
            }
            if (candles.size < 35) append("\nMACD için yeterli veri olmayabilir.")
            if (candles.size < 200) append("\nEMA200 için yeterli veri yok.")
        }
    }

    private fun renderVolumeAnalysis(candles: List<Candle>) {
        val x = AppSession.selected ?: return
        val positive = candles.filter { it.volume.isFinite() && it.volume > 0.0 }
        val lastVolume = positive.lastOrNull()?.volume
        val avgWindow = positive.takeLast(20)
        val avgVolume = avgWindow.takeIf { it.isNotEmpty() }?.map { it.volume }?.average()
        val changePct = if (lastVolume != null && avgVolume != null && avgVolume > 0.0) {
            ((lastVolume / avgVolume) - 1.0) * 100.0
        } else null
        findViewById<TextView>(R.id.volumeAnalysis).text = buildString {
            append("Son hacim       ${lastVolume?.let { "%.0f".format(it) } ?: "Veri yok"}\n")
            append("20 mum ort.     ${avgVolume?.takeIf { it.isFinite() }?.let { "%.0f".format(it) } ?: "Veri yok"}\n")
            append("Hacim değişimi  ${changePct?.takeIf { it.isFinite() }?.let { "%+.1f%%".format(it) } ?: "Veri yok"}\n")
            append("Hacim oranı     ${x.technical.volumeRatio?.takeIf { it.isFinite() }?.let { "%.2fx".format(it) } ?: "Veri yok"}\n")
            append("Hacim yönü      ${x.volumeDirectionLabel.ifBlank { "Yön verisi yok" }}")
        }
    }

    private fun renderDataQuality(x: Opportunity) {
        val ageMs = if (x.dataTimestamp > 0L) (System.currentTimeMillis() - x.dataTimestamp).coerceAtLeast(0L) else null
        val ageText = ageMs?.let {
            when {
                it < 60_000L -> "${it / 1000L} sn"
                it < 3_600_000L -> "${it / 60_000L} dk"
                else -> "${it / 3_600_000L} sa"
            }
        } ?: "Bilinmiyor"
        val delayText = x.delaySeconds?.let { "$it sn" } ?: "Bilinmiyor"
        val dataMode = if (x.isRealtime && x.currentSessionIncluded) "CANLI/DOĞRULANMIŞ" else "YEDEK / GECİKMELİ"
        findViewById<TextView>(R.id.dataQuality).text = buildString {
            append("Mevcut Veri       Fiyat, teknik göstergeler${if (x.candles.isNotEmpty()) ", OHLCV" else ""}\n")
            append("Veri Kaynağı      ${x.source}\n")
            append("Veri Modu         $dataMode\n")
            append("Son Güncelleme    $ageText önce\n")
            append("Bildirilen Gecikme $delayText\n")
            append("Veri Güven Skoru  ${x.dataConfidenceScore}/100 (${x.dataConfidenceLabel})")
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
            currentSeries?.let {
                renderChartIndicatorSummary(it)
                renderVolumeAnalysis(it.candles)
            }
        }
    }

    private fun renderLegacyTechnicalSummary() {
        details.text = legacyTechnicalText()
    }

    private fun legacyTechnicalText(): String {
        val x = AppSession.selected ?: return ""
        val t = x.technical
        return buildString {
            append("Trend: EMA20 ${fmt(t.ema20)} • EMA50 ${fmt(t.ema50)} • EMA200 ${fmt(t.ema200)}\n")
            append("Momentum: RSI14 ${fmt(t.rsi14)} • MACD ${fmt(t.macd)} • Sinyal ${fmt(t.macdSignal)}\n")
            append("ATR14 ${fmt(t.atr14)} • VWAP ${fmt(t.vwap)} • VWMA ${fmt(t.vwma)}\n")
            append("Destek ${fmt(t.support)} • Direnç ${fmt(t.resistance)}")
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
