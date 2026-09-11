package tr.borsatakip.v5.analysis

import tr.borsatakip.v5.model.LrcTechnicalSnapshot
import tr.borsatakip.v5.model.Opportunity
import tr.borsatakip.v5.model.Stock
import tr.borsatakip.v5.ui.chart.LinearRegressionChannelCalculator
import kotlin.math.abs

object OpportunityEngine {
    private const val LRC_LENGTH = 100

    fun score(stock: Stock, kapLabel: String = "Veri yok"): Opportunity? {
        val c = stock.candles.filter { candle ->
            listOf(candle.open, candle.high, candle.low, candle.close, candle.volume).all { it.isFinite() }
        }
        if (c.size < 220) return null

        val price = c.last().close
        val prev = c[c.lastIndex - 1].close
        val price20 = c[c.lastIndex - 20].close
        if (!price.isFinite() || price <= 0.0 || !prev.isFinite() || prev <= 0.0 || price20 <= 0.0) return null

        val t = TechnicalAnalyzer.analyze(c)
        val lrcResult = LinearRegressionChannelCalculator.calculate(c.map { it.close }, LRC_LENGTH)
        val lrc = lrcResult?.let { result ->
            val idx = result.endIndex
            val mid = result.regressionAt(idx)
            val u1 = result.upperAt(idx, 1.0)
            val l1 = result.lowerAt(idx, 1.0)
            val u2 = result.upperAt(idx, 2.0)
            val l2 = result.lowerAt(idx, 2.0)
            val u3 = result.upperAt(idx, 3.0)
            val l3 = result.lowerAt(idx, 3.0)
            val width = u2 - l2
            val widthPct = if (mid.isFinite() && mid != 0.0) (width / abs(mid)) * 100.0 else null
            LrcTechnicalSnapshot(
                period = LRC_LENGTH,
                slope = result.slope,
                trend = when (result.trend) {
                    LinearRegressionChannelCalculator.Trend.UP -> "YÜKSELEN"
                    LinearRegressionChannelCalculator.Trend.DOWN -> "DÜŞEN"
                    LinearRegressionChannelCalculator.Trend.FLAT -> "YATAY"
                },
                pearsonR = result.pearsonR,
                upper1 = u1,
                lower1 = l1,
                upper2 = u2,
                lower2 = l2,
                upper3 = u3,
                lower3 = l3,
                channelPosition = channelPosition(price, mid, u1, l1, u2, l2),
                channelWidth = width,
                channelWidthPct = widthPct?.takeIf { it.isFinite() },
                distanceToMidline = result.distanceToMid
            )
        }

        var longScore = 0
        var shortScore = 0
        val longParts = mutableListOf<String>()
        val shortParts = mutableListOf<String>()

        fun addLong(label: String, pts: Int) { longScore += pts; longParts += "$label: +$pts" }
        fun addShort(label: String, pts: Int) { shortScore += pts; shortParts += "$label: +$pts" }

        if (t.ema20 != null && t.ema50 != null && t.ema200 != null) {
            when {
                price > t.ema20 && t.ema20 > t.ema50 && t.ema50 > t.ema200 -> addLong("Trend/EMA", 25)
                price < t.ema20 && t.ema20 < t.ema50 && t.ema50 < t.ema200 -> addShort("Trend/EMA", 25)
            }
        }

        t.rsi14?.takeIf { it.isFinite() }?.let { rsi ->
            when {
                rsi in 52.0..68.0 -> addLong("RSI", 15)
                rsi in 32.0..48.0 -> addShort("RSI", 15)
                rsi in 48.0..52.0 -> { addLong("RSI", 6); addShort("RSI", 6) }
                rsi < 30.0 -> addLong("RSI dönüş bölgesi", 5)
                rsi > 70.0 -> addShort("RSI dönüş bölgesi", 5)
            }
        }

        if (t.macd?.isFinite() == true && t.macdSignal?.isFinite() == true) {
            if (t.macd > t.macdSignal) addLong("MACD", 15) else addShort("MACD", 15)
        }

        // LRC tek başına AL/SAT üretmez. Yalnız mevcut puanlamaya sınırlı trend uyumu katkısı verir.
        lrc?.let { channel ->
            when (channel.trend) {
                "YÜKSELEN" -> {
                    addLong("LRC100 trend", 6)
                    if (channel.pearsonR >= 0.70) addLong("LRC Pearson", 4)
                }
                "DÜŞEN" -> {
                    addShort("LRC100 trend", 6)
                    if (channel.pearsonR <= -0.70) addShort("LRC Pearson", 4)
                }
            }
        }

        val volumeRatio = t.volumeRatio?.takeIf { it.isFinite() && it >= 0.0 }
        when {
            volumeRatio == null -> Unit
            volumeRatio >= 1.5 -> { addLong("Hacim", 15); addShort("Hacim", 15) }
            volumeRatio >= 1.2 -> { addLong("Hacim", 10); addShort("Hacim", 10) }
            volumeRatio >= 1.0 -> { addLong("Hacim", 5); addShort("Hacim", 5) }
        }

        val momentum20 = ((price / price20) - 1.0) * 100.0
        if (momentum20.isFinite()) {
            when {
                momentum20 >= 3.0 -> addLong("20G Momentum", 10)
                momentum20 <= -3.0 -> addShort("20G Momentum", 10)
            }
        }

        val resistance = t.resistance?.takeIf { it.isFinite() && it > 0.0 }
        val support = t.support?.takeIf { it.isFinite() && it > 0.0 }
        resistance?.let { if (price >= it * 0.995) addLong("Direnç/Breakout", 10) }
        support?.let { if (price <= it * 1.005) addShort("Destek/Breakdown", 10) }

        t.vwap?.takeIf { it.isFinite() && it > 0.0 }?.let { vwap ->
            if (price > vwap) addLong("VWAP", 5) else addShort("VWAP", 5)
        }

        val atrPct = t.atr14
            ?.takeIf { it.isFinite() && it >= 0.0 }
            ?.let { (it / price) * 100.0 }
            ?.takeIf { it.isFinite() }
            ?: 99.0

        when {
            atrPct in 0.5..4.0 -> { addLong("Volatilite", 5); addShort("Volatilite", 5) }
            atrPct in 4.0..6.0 -> { addLong("Volatilite", 2); addShort("Volatilite", 2) }
        }

        val kapScore = 0
        val direction = if (longScore >= shortScore) "LONG" else "SHORT"
        val score = maxOf(longScore, shortScore).coerceIn(0, 100)
        val chosenParts = if (direction == "LONG") longParts else shortParts

        val lowVolumePenalty = if ((volumeRatio ?: 0.0) < 0.8) 15.0 else 0.0
        val rsi = t.rsi14 ?: 50.0
        val extremeRsiPenalty = if (rsi.isFinite() && (rsi > 75.0 || rsi < 25.0)) 12.0 else 0.0
        val rawRisk = atrPct * 10.0 + lowVolumePenalty + extremeRsiPenalty
        if (!rawRisk.isFinite()) return null
        val risk = rawRisk.coerceIn(0.0, 100.0).toInt()

        val liquidity = when {
            volumeRatio == null -> "Veri yok"
            volumeRatio >= 1.5 -> "Hacim aktivitesi yüksek"
            volumeRatio >= 0.8 -> "Hacim aktivitesi orta"
            else -> "Hacim aktivitesi düşük"
        }
        val technicalLabel = when {
            score >= 80 -> "Güçlü"
            score >= 65 -> "Pozitif"
            else -> "Nötr"
        }
        val volumeLabel = volumeRatio?.let { "%.2fx".format(it) } ?: "Veri yok"
        val change = ((price / prev) - 1.0) * 100.0
        if (!change.isFinite()) return null

        val breakdown = buildList {
            addAll(chosenParts)
            lrc?.let {
                add("LRC100: ${it.trend} • R ${"%.2f".format(it.pearsonR)} • ${it.channelPosition}")
                if (price > it.upper2) add("LRC: Üst kanal dışında (+2σ üzeri) • tek başına SAT değildir")
                if (price < it.lower2) add("LRC: Alt kanal dışında (-2σ altı) • tek başına AL değildir")
            }
            add("KAP: +$kapScore (${if (kapLabel == "Veri yok") "veri yok" else kapLabel})")
            add("Risk: $risk/100 (fırsat puanından ayrı)")
            add("Toplam: $score/100 • $direction")
        }

        return Opportunity(
            symbol = stock.symbol,
            companyName = stock.companyName,
            price = price,
            dailyChangePct = change,
            score = score,
            riskScore = risk,
            direction = direction,
            technicalLabel = technicalLabel,
            volumeLabel = volumeLabel,
            kapLabel = kapLabel,
            liquidityLabel = liquidity,
            support = support,
            resistance = resistance,
            source = stock.source,
            dataTimestamp = stock.dataTimestamp,
            candles = c,
            technical = t,
            lrc = lrc,
            scoreBreakdown = breakdown,
            isRealtime = stock.isRealtime,
            delaySeconds = stock.delaySeconds,
            currentSessionIncluded = stock.currentSessionIncluded
        )
    }

    private fun channelPosition(price: Double, mid: Double, u1: Double, l1: Double, u2: Double, l2: Double): String = when {
        price < l2 -> "ALT -2σ ALTINDA"
        price < l1 -> "-2σ / -1σ"
        price < mid -> "-1σ / ORTA"
        price <= u1 -> "ORTA / +1σ"
        price <= u2 -> "+1σ / +2σ"
        else -> "+2σ ÜZERİNDE"
    }
}
