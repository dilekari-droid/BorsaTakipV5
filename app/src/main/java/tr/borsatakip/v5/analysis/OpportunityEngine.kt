package tr.borsatakip.v5.analysis

import tr.borsatakip.v5.model.Opportunity
import tr.borsatakip.v5.model.Stock

object OpportunityEngine {
    fun score(stock: Stock, kapLabel: String = "Veri yok"): Opportunity? {
        val c = stock.candles.filter { candle ->
            listOf(candle.open, candle.high, candle.low, candle.close, candle.volume).all { it.isFinite() }
        }
        if (c.size < 220) return null

        val price = c.last().close
        val prev = c[c.lastIndex - 1].close
        if (!price.isFinite() || price <= 0.0 || !prev.isFinite() || prev <= 0.0) return null

        val t = TechnicalAnalyzer.analyze(c)
        var longScore = 0.0
        var shortScore = 0.0

        if (t.ema20 != null && t.ema50 != null && t.ema200 != null) {
            if (price > t.ema20 && t.ema20 > t.ema50 && t.ema50 > t.ema200) longScore += 28
            else if (price < t.ema20 && t.ema20 < t.ema50 && t.ema50 < t.ema200) shortScore += 28
        }

        t.rsi14?.takeIf { it.isFinite() }?.let {
            if (it in 50.0..68.0) longScore += 12
            if (it in 32.0..50.0) shortScore += 12
            if (it > 75) shortScore += 4
            if (it < 25) longScore += 4
        }

        if (t.macd?.isFinite() == true && t.macdSignal?.isFinite() == true) {
            if (t.macd > t.macdSignal) longScore += 14 else shortScore += 14
        }

        t.volumeRatio?.takeIf { it.isFinite() }?.let {
            if (it >= 1.5) {
                longScore += 10
                shortScore += 10
            }
        }
        t.vwap?.takeIf { it.isFinite() }?.let { if (price > it) longScore += 8 else shortScore += 8 }
        t.resistance?.takeIf { it.isFinite() }?.let { if (price >= it * 0.995) longScore += 8 }
        t.support?.takeIf { it.isFinite() }?.let { if (price <= it * 1.005) shortScore += 8 }

        val direction = if (longScore >= shortScore) "LONG" else "SHORT"
        val raw = maxOf(longScore, shortScore)
        if (!raw.isFinite()) return null
        val score = raw.coerceIn(0.0, 100.0).toInt()

        val atrPct = t.atr14
            ?.takeIf { it.isFinite() && it >= 0.0 }
            ?.let { (it / price) * 100.0 }
            ?.takeIf { it.isFinite() }
            ?: 99.0

        val lowVolumePenalty = if ((t.volumeRatio ?: 0.0).isFinite() && (t.volumeRatio ?: 0.0) < 0.8) 15.0 else 0.0
        val rsi = t.rsi14 ?: 50.0
        val extremeRsiPenalty = if (rsi.isFinite() && (rsi > 75.0 || rsi < 25.0)) 12.0 else 0.0
        val rawRisk = atrPct * 10.0 + lowVolumePenalty + extremeRsiPenalty
        if (!rawRisk.isFinite()) return null
        val risk = rawRisk.coerceIn(0.0, 100.0).toInt()

        val volumeRatio = t.volumeRatio?.takeIf { it.isFinite() }
        val liquidity = when {
            (volumeRatio ?: 0.0) >= 1.5 -> "Yüksek"
            (volumeRatio ?: 0.0) >= 0.8 -> "Orta"
            else -> "Düşük"
        }
        val technicalLabel = when {
            score >= 80 -> "Güçlü"
            score >= 65 -> "Pozitif"
            else -> "Nötr"
        }
        val volumeLabel = volumeRatio?.let { "%.1fx".format(it) } ?: "Veri yok"
        val change = ((price / prev) - 1.0) * 100.0
        if (!change.isFinite()) return null

        return Opportunity(
            stock.symbol,
            stock.companyName,
            price,
            change,
            score,
            risk,
            direction,
            technicalLabel,
            volumeLabel,
            kapLabel,
            liquidity,
            t.support,
            t.resistance,
            stock.source,
            stock.dataTimestamp,
            c,
            t
        )
    }
}
