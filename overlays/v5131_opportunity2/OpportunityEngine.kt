package tr.borsatakip.v5.analysis

import tr.borsatakip.v5.data.RealTimeIntegrityPolicy
import tr.borsatakip.v5.model.DataMode
import tr.borsatakip.v5.model.DataSnapshot
import tr.borsatakip.v5.model.Opportunity
import tr.borsatakip.v5.model.OpportunitySnapshot
import tr.borsatakip.v5.model.SignalValidity
import tr.borsatakip.v5.model.Stock
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Fırsat Tarama 2.0
 * Gerçek OHLCV + teknik yapı + hacim + kırılım + risk/ödül + veri güveni üzerinden puanlar.
 * Çoklu zaman dilimi, benchmark/göreli güç ve piyasa rejimi verisi bu Stock sözleşmesinde yoksa
 * puana sahte katkı verilmez; bu eksikler UI'da açıkça belirtilir.
 */
object OpportunityEngine {
    object Weights {
        const val TREND = 22
        const val MOMENTUM = 14
        const val VOLUME = 15
        const val BREAKOUT = 16
        const val PULLBACK_OR_REVERSAL = 10
        const val RISK_REWARD = 13
        const val DATA_QUALITY = 10
    }

    fun score(stock: Stock, kapLabel: String = "Veri yok"): Opportunity? {
        val c = stock.candles
            .filter { x ->
                x.timestamp > 0L &&
                    listOf(x.open, x.high, x.low, x.close, x.volume).all { it.isFinite() } &&
                    x.open > 0.0 && x.high > 0.0 && x.low > 0.0 && x.close > 0.0 &&
                    x.high >= max(x.open, x.close) && x.low <= min(x.open, x.close) && x.volume >= 0.0
            }
            .distinctBy { it.timestamp }
            .sortedBy { it.timestamp }
        if (c.size < 220) return null

        val price = stock.quotePrice?.takeIf { it.isFinite() && it > 0.0 } ?: c.last().close
        val prev = stock.previousClose?.takeIf { it.isFinite() && it > 0.0 }
            ?: if (stock.currentSessionIncluded && stock.lastBarClosed == false && c.size >= 2) c[c.lastIndex - 1].close else c.last().close
        if (!price.isFinite() || price <= 0.0 || !prev.isFinite() || prev <= 0.0) return null

        val t = TechnicalAnalyzer.analyze(c)
        val prevT = TechnicalAnalyzer.analyze(c.dropLast(1))
        val atr = t.atr14?.takeIf { it.isFinite() && it > 0.0 }
        val atrPct = atr?.let { it / price * 100.0 } ?: 99.0
        val volumeRatio = t.volumeRatio?.takeIf { it.isFinite() && it >= 0.0 }
        val rsi = t.rsi14?.takeIf { it.isFinite() }
        val prevRsi = prevT.rsi14?.takeIf { it.isFinite() }
        val macdDiff = if (t.macd?.isFinite() == true && t.macdSignal?.isFinite() == true) t.macd - t.macdSignal else null
        val prevMacdDiff = if (prevT.macd?.isFinite() == true && prevT.macdSignal?.isFinite() == true) prevT.macd - prevT.macdSignal else null

        val emaLong = t.ema20 != null && t.ema50 != null && t.ema200 != null && price > t.ema20 && t.ema20 > t.ema50 && t.ema50 > t.ema200
        val emaShort = t.ema20 != null && t.ema50 != null && t.ema200 != null && price < t.ema20 && t.ema20 < t.ema50 && t.ema50 < t.ema200

        val high20Before = c.dropLast(1).takeLast(20).maxOfOrNull { it.high }
        val low20Before = c.dropLast(1).takeLast(20).minOfOrNull { it.low }
        val breakoutBuffer = max(price * 0.0015, (atr ?: 0.0) * 0.12)
        val breakoutUp = high20Before != null && price > high20Before + breakoutBuffer
        val breakoutDown = low20Before != null && price < low20Before - breakoutBuffer
        val volumeConfirmed = volumeRatio != null && volumeRatio >= 1.5
        val strongVolume = volumeRatio != null && volumeRatio >= 2.0

        val return20 = pct(c[c.lastIndex - 20].close, price)
        val return5 = pct(c[c.lastIndex - 5].close, price)
        val return3 = pct(c[c.lastIndex - 3].close, price)

        val nearEma20 = atr != null && t.ema20 != null && abs(price - t.ema20) <= atr
        val nearSupport = atr != null && t.support != null && abs(price - t.support) <= atr * 0.8
        val nearResistance = atr != null && t.resistance != null && abs(t.resistance - price) <= atr * 0.8
        val rsiTurningUp = rsi != null && prevRsi != null && rsi > prevRsi && rsi < 55.0
        val rsiTurningDown = rsi != null && prevRsi != null && rsi < prevRsi && rsi > 45.0
        val macdTurningUp = macdDiff != null && prevMacdDiff != null && macdDiff > prevMacdDiff
        val macdTurningDown = macdDiff != null && prevMacdDiff != null && macdDiff < prevMacdDiff

        val squeeze = bollingerWidthPct(t.bbUpper, t.bbLower, price)?.let { it < 5.5 } == true && atrPct < 3.5
        val pullbackLong = emaLong && nearEma20 && return5 <= 4.0 && (rsi ?: 50.0) in 40.0..65.0
        val pullbackShort = emaShort && nearEma20 && return5 >= -4.0 && (rsi ?: 50.0) in 35.0..60.0
        val reversalLong = nearSupport && rsiTurningUp && macdTurningUp
        val reversalShort = nearResistance && rsiTurningDown && macdTurningDown

        val longParts = mutableListOf<Pair<String, Int>>()
        val shortParts = mutableListOf<Pair<String, Int>>()
        fun long(label: String, p: Int) { longParts += label to p }
        fun short(label: String, p: Int) { shortParts += label to p }

        if (emaLong) long("EMA trend uyumu", Weights.TREND)
        if (emaShort) short("EMA trend uyumu", Weights.TREND)

        if (return20 >= 2.0 && (macdDiff ?: 0.0) > 0.0) long("Momentum", Weights.MOMENTUM)
        if (return20 <= -2.0 && (macdDiff ?: 0.0) < 0.0) short("Momentum", Weights.MOMENTUM)

        when {
            strongVolume -> { long("Hacim teyidi", Weights.VOLUME); short("Hacim teyidi", Weights.VOLUME) }
            volumeConfirmed -> { long("Hacim teyidi", 11); short("Hacim teyidi", 11) }
            volumeRatio != null && volumeRatio >= 1.15 -> { long("Hacim", 6); short("Hacim", 6) }
        }

        if (breakoutUp) long("Teyitli direnç kırılımı", if (volumeConfirmed) Weights.BREAKOUT else 9)
        if (breakoutDown) short("Teyitli destek kırılımı", if (volumeConfirmed) Weights.BREAKOUT else 9)
        if (pullbackLong || reversalLong) long(if (pullbackLong) "Trend içinde geri çekilme" else "Destekte tepki", Weights.PULLBACK_OR_REVERSAL)
        if (pullbackShort || reversalShort) short(if (pullbackShort) "Trend içinde geri çekilme" else "Dirençte tepki", Weights.PULLBACK_OR_REVERSAL)
        if (squeeze && return3 > 0.0) long("Sıkışma", 6)
        if (squeeze && return3 < 0.0) short("Sıkışma", 6)

        val provisionalLong = longParts.sumOf { it.second }
        val provisionalShort = shortParts.sumOf { it.second }
        val direction = when {
            provisionalLong - provisionalShort >= 5 -> "LONG"
            provisionalShort - provisionalLong >= 5 -> "SHORT"
            else -> "NEUTRAL"
        }

        val support = t.support?.takeIf { it.isFinite() && it > 0.0 }
        val resistance = t.resistance?.takeIf { it.isFinite() && it > 0.0 }
        val levels = computeLevels(direction, price, atr, support, resistance, c)
        val rr = levels?.riskReward
        if (rr != null) {
            when {
                direction == "LONG" && rr >= 2.5 -> long("Risk/ödül", Weights.RISK_REWARD)
                direction == "LONG" && rr >= 2.0 -> long("Risk/ödül", 9)
                direction == "SHORT" && rr >= 2.5 -> short("Risk/ödül", Weights.RISK_REWARD)
                direction == "SHORT" && rr >= 2.0 -> short("Risk/ödül", 9)
            }
        }

        val confidence = calculateDataConfidence(price, c.size, volumeRatio, support, resistance, t.vwap, t.ema20, t.ema50, t.ema200, rsi, t.macd, t.macdSignal, kapLabel)
        if (confidence >= 80) {
            if (direction == "LONG") long("Veri kalitesi", Weights.DATA_QUALITY)
            if (direction == "SHORT") short("Veri kalitesi", Weights.DATA_QUALITY)
        } else if (confidence >= 65) {
            if (direction == "LONG") long("Veri kalitesi", 6)
            if (direction == "SHORT") short("Veri kalitesi", 6)
        }

        val longScore = longParts.sumOf { it.second }.coerceIn(0, 100)
        val shortScore = shortParts.sumOf { it.second }.coerceIn(0, 100)
        val score = max(longScore, shortScore)

        val timing = when {
            atr != null && t.ema20 != null && ((direction == "LONG" && price > t.ema20 + 2.2 * atr) || (direction == "SHORT" && price < t.ema20 - 2.2 * atr)) -> "GEÇ KALINMIŞ"
            abs(return5) >= 10.0 -> "GEÇ KALINMIŞ"
            abs(return5) >= 7.0 -> "SINIRDA"
            else -> "ZAMANINDA"
        }

        val lowVolumePenalty = if ((volumeRatio ?: 0.0) < 0.8) 18.0 else if ((volumeRatio ?: 0.0) < 1.0) 8.0 else 0.0
        val extremeRsiPenalty = if (rsi != null && (rsi > 75.0 || rsi < 25.0)) 14.0 else 0.0
        val latePenalty = if (timing == "GEÇ KALINMIŞ") 18.0 else if (timing == "SINIRDA") 8.0 else 0.0
        val rrPenalty = when { rr == null -> 15.0; rr < 1.5 -> 20.0; rr < 2.0 -> 10.0; else -> 0.0 }
        val rawRisk = atrPct.coerceAtMost(8.0) * 7.0 + lowVolumePenalty + extremeRsiPenalty + latePenalty + rrPenalty
        if (!rawRisk.isFinite()) return null
        val risk = rawRisk.coerceIn(0.0, 100.0).toInt()

        val sourceLower = stock.source.lowercase()
        val dataMode = when {
            stock.isRealtime && stock.currentSessionIncluded && stock.delaySeconds != null && stock.delaySeconds in 0..RealTimeIntegrityPolicy.MAX_DECLARED_DELAY_SECONDS -> DataMode.REALTIME
            sourceLower.contains("yahoo") || sourceLower.contains("gecik") || (stock.delaySeconds ?: 0) > RealTimeIntegrityPolicy.MAX_DECLARED_DELAY_SECONDS -> DataMode.DELAYED
            sourceLower.contains("eod") || sourceLower.contains("gün son") -> DataMode.EOD
            else -> DataMode.UNVERIFIED
        }
        val integrity = RealTimeIntegrityPolicy.validate(stock)
        val lowerReason = integrity.reason.lowercase()
        val validity = when {
            integrity.accepted && confidence >= 60 -> SignalValidity.VALID
            integrity.accepted -> SignalValidity.WATCH
            lowerReason.contains("ohlcv") || lowerReason.contains("en az 220") -> SignalValidity.INSUFFICIENT
            dataMode == DataMode.DELAYED || dataMode == DataMode.EOD || lowerReason.contains("güncel değil") || lowerReason.contains("gerçek zamanlı") -> SignalValidity.WATCH
            else -> SignalValidity.REJECTED
        }
        val validityReason = when (validity) {
            SignalValidity.VALID -> "Fiyat, kaynak, zaman ve zorunlu veri bütünlüğü doğrulandı."
            SignalValidity.WATCH -> if (integrity.accepted) "Teknik yapı var; veri güveni teyit için sınırlı." else integrity.reason
            SignalValidity.INSUFFICIENT -> integrity.reason
            SignalValidity.REJECTED -> integrity.reason
        }

        val klass = when {
            validity == SignalValidity.VALID && score >= 85 && confidence >= 75 && (rr ?: 0.0) >= 2.0 && timing != "GEÇ KALINMIŞ" -> "A SINIFI"
            (validity == SignalValidity.VALID || validity == SignalValidity.WATCH) && score >= 70 && timing != "GEÇ KALINMIŞ" -> "B SINIFI"
            score >= 55 -> "İZLE"
            else -> "RED"
        }

        val setup = when {
            direction == "LONG" && breakoutUp -> if (volumeConfirmed) "KIRILIM" else "KIRILIM • TEYİT BEKLİYOR"
            direction == "SHORT" && breakoutDown -> if (volumeConfirmed) "KIRILIM" else "KIRILIM • TEYİT BEKLİYOR"
            direction == "LONG" && reversalLong -> "DİPTEN DÖNÜŞ"
            direction == "SHORT" && reversalShort -> "DİPTEN DÖNÜŞ"
            pullbackLong || pullbackShort -> "TREND İÇİNDE GERİ ÇEKİLME"
            squeeze -> "SIKIŞMA → PATLAMA ADAYI"
            volumeConfirmed -> "HACİM DESTEKLİ MOMENTUM"
            emaLong || emaShort -> "TREND"
            else -> "TEKNİK İZLEME"
        }

        val reasons = mutableListOf<String>()
        val chosen = if (direction == "LONG") longParts else if (direction == "SHORT") shortParts else emptyList()
        chosen.sortedByDescending { it.second }.take(5).forEach { reasons += "${it.first} (+${it.second})" }
        if (volumeRatio != null) reasons += "Hacim ${"%.2f".format(volumeRatio)}x"
        if (rsi != null) reasons += "RSI ${"%.1f".format(rsi)}"
        rr?.let { reasons += "Risk/Ödül 1:${"%.2f".format(it)}" }

        val riskNotes = mutableListOf<String>()
        if (timing != "ZAMANINDA") riskNotes += "Hareket zamanlaması: $timing"
        if ((volumeRatio ?: 0.0) < 1.0) riskNotes += "Hacim teyidi zayıf"
        if (rr == null || rr < 2.0) riskNotes += "Risk/ödül 2.0 eşiğinin altında"
        if (dataMode != DataMode.REALTIME) riskNotes += "Veri modu: ${dataMode.name}"
        if (stock.interval != "1d") riskNotes += "Tarama periyodu: ${stock.interval}"
        if (rsi != null && (rsi > 75 || rsi < 25)) riskNotes += "RSI uç bölgede"

        val breakdown = buildList {
            add("CLASS=$klass")
            add("SETUP=$setup")
            add("RR=${rr?.let { "%.4f".format(java.util.Locale.US, it) } ?: ""}")
            add("TIMING=$timing")
            reasons.forEach { add("WHY=$it") }
            riskNotes.forEach { add("RISK_NOTE=$it") }
            chosen.forEach { add("${it.first}: +${it.second}") }
            add("Risk: $risk/100 (fırsat puanından ayrı)")
            add("Veri Güveni: $confidence/100")
            add("Toplam: $score/100 • $direction")
        }

        val confidenceLabel = when { confidence >= 80 -> "Yüksek"; confidence >= 60 -> "Orta"; else -> "Düşük" }
        val liquidity = when {
            volumeRatio == null -> "Veri yok"
            volumeRatio >= 1.5 -> "Hacim aktivitesi yüksek"
            volumeRatio >= 0.8 -> "Hacim aktivitesi orta"
            else -> "Hacim aktivitesi düşük"
        }
        val technicalLabel = when { score >= 85 -> "Çok Güçlü"; score >= 70 -> "Güçlü"; score >= 55 -> "Orta"; else -> "Zayıf" }
        val volumeLabel = volumeRatio?.let { "%.2fx".format(it) } ?: "Veri yok"
        val change = pct(prev, price)
        if (!change.isFinite()) return null

        val dataSnapshot = DataSnapshot(stock.source, stock.symbol, price, stock.exchangeTimestamp, stock.receivedAt, dataMode, stock.delaySeconds, stock.currentSessionIncluded)
        val snapshot = OpportunitySnapshot(stock.source, stock.symbol, price, stock.exchangeTimestamp, stock.receivedAt, dataMode, confidence, t, score, dataSnapshot = dataSnapshot)

        return Opportunity(
            symbol = stock.symbol, companyName = stock.companyName, price = price, dailyChangePct = change,
            score = score, riskScore = risk, direction = direction, technicalLabel = technicalLabel, volumeLabel = volumeLabel,
            kapLabel = kapLabel, liquidityLabel = liquidity, support = support, resistance = resistance,
            source = stock.source, dataTimestamp = stock.dataTimestamp, candles = c, technical = t, scoreBreakdown = breakdown,
            dataConfidenceScore = confidence, dataConfidenceLabel = confidenceLabel, finalSignalScore = score,
            isRealtime = stock.isRealtime, delaySeconds = stock.delaySeconds, currentSessionIncluded = stock.currentSessionIncluded,
            exchangeTimestamp = stock.exchangeTimestamp, receivedAt = stock.receivedAt, receivedElapsedRealtime = stock.receivedElapsedRealtime,
            signalGeneratedAt = System.currentTimeMillis(), dataMode = dataMode, signalValidity = validity,
            signalValidityReason = validityReason, snapshot = snapshot
        )
    }

    private data class Levels(val stop: Double, val target1: Double, val target2: Double, val riskReward: Double)

    private fun computeLevels(direction: String, price: Double, atr: Double?, support: Double?, resistance: Double?, c: List<tr.borsatakip.v5.model.Candle>): Levels? {
        val a = atr ?: return null
        if (a <= 0.0) return null
        val swingLow = c.takeLast(20).minOfOrNull { it.low }
        val swingHigh = c.takeLast(20).maxOfOrNull { it.high }
        return when (direction) {
            "LONG" -> {
                val structural = listOfNotNull(support, swingLow).filter { it < price }.maxOrNull()
                val stop = maxOf(price - 1.25 * a, structural ?: Double.NEGATIVE_INFINITY).takeIf { it.isFinite() && it < price } ?: (price - 1.25 * a)
                val target1 = resistance?.takeIf { it > price + 0.5 * a } ?: (price + 1.5 * a)
                val target2 = maxOf(target1, price + 2.5 * a)
                val risk = price - stop
                if (risk <= 0.0) null else Levels(stop, target1, target2, (target2 - price) / risk)
            }
            "SHORT" -> {
                val structural = listOfNotNull(resistance, swingHigh).filter { it > price }.minOrNull()
                val stop = minOf(price + 1.25 * a, structural ?: Double.POSITIVE_INFINITY).takeIf { it.isFinite() && it > price } ?: (price + 1.25 * a)
                val target1 = support?.takeIf { it < price - 0.5 * a } ?: (price - 1.5 * a)
                val target2 = minOf(target1, price - 2.5 * a)
                val risk = stop - price
                if (risk <= 0.0) null else Levels(stop, target1, target2, (price - target2) / risk)
            }
            else -> null
        }
    }

    private fun pct(from: Double, to: Double): Double = if (from > 0.0) ((to / from) - 1.0) * 100.0 else Double.NaN

    private fun bollingerWidthPct(upper: Double?, lower: Double?, price: Double): Double? =
        if (upper != null && lower != null && upper.isFinite() && lower.isFinite() && price > 0.0) ((upper - lower) / price) * 100.0 else null

    private fun calculateDataConfidence(
        price: Double, candleCount: Int, volumeRatio: Double?, support: Double?, resistance: Double?, vwap: Double?,
        ema20: Double?, ema50: Double?, ema200: Double?, rsi: Double?, macd: Double?, macdSignal: Double?, kapLabel: String
    ): Int {
        var value = 0
        if (price.isFinite() && price > 0.0) value += 20
        if (candleCount >= 220) value += 25
        if (volumeRatio?.isFinite() == true) value += 15
        if (support?.isFinite() == true && resistance?.isFinite() == true) value += 10
        if (vwap?.isFinite() == true) value += 10
        if (ema20?.isFinite() == true && ema50?.isFinite() == true && ema200?.isFinite() == true) value += 10
        if (rsi?.isFinite() == true) value += 5
        if (macd?.isFinite() == true && macdSignal?.isFinite() == true) value += 3
        if (!kapLabel.equals("Veri yok", true) && kapLabel.isNotBlank()) value += 2
        return value.coerceIn(0, 100)
    }
}
