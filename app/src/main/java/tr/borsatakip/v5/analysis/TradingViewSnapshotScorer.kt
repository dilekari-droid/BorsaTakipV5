package tr.borsatakip.v5.analysis

import tr.borsatakip.v5.model.Opportunity
import tr.borsatakip.v5.model.TechnicalSnapshot

/**
 * TradingView Scanner'ın tek seferlik gerçek piyasa snapshot'ını fırsat sonucuna dönüştürür.
 * Bu sınıf OHLC geçmişi uydurmaz; yalnız gerçekten dönen indikatör alanlarını puanlar.
 */
object TradingViewSnapshotScorer {

    data class Snapshot(
        val symbol: String,
        val companyName: String?,
        val price: Double,
        val changePct: Double,
        val volume: Double?,
        val relativeVolume: Double?,
        val rsi: Double?,
        val macd: Double?,
        val macdSignal: Double?,
        val ema20: Double?,
        val ema50: Double?,
        val ema200: Double?,
        val atr: Double?,
        val vwma: Double?,
        val recommendation: Double?,
        val receivedAt: Long,
        val source: String
    )

    fun score(x: Snapshot): Opportunity? {
        if (!x.price.isFinite() || x.price <= 0.0 || !x.changePct.isFinite()) return null

        var longScore = 0
        var shortScore = 0
        val longParts = mutableListOf<String>()
        val shortParts = mutableListOf<String>()

        fun addLong(label: String, pts: Int) { longScore += pts; longParts += "$label: +$pts" }
        fun addShort(label: String, pts: Int) { shortScore += pts; shortParts += "$label: +$pts" }

        val e20 = x.ema20?.takeIf { it.isFinite() && it > 0.0 }
        val e50 = x.ema50?.takeIf { it.isFinite() && it > 0.0 }
        val e200 = x.ema200?.takeIf { it.isFinite() && it > 0.0 }
        if (e20 != null && e50 != null && e200 != null) {
            when {
                x.price > e20 && e20 > e50 && e50 > e200 -> addLong("Trend/EMA", 25)
                x.price < e20 && e20 < e50 && e50 < e200 -> addShort("Trend/EMA", 25)
            }
        }

        x.rsi?.takeIf { it.isFinite() }?.let { rsi ->
            when {
                rsi in 52.0..68.0 -> addLong("RSI", 15)
                rsi in 32.0..48.0 -> addShort("RSI", 15)
                rsi in 48.0..52.0 -> { addLong("RSI", 6); addShort("RSI", 6) }
                rsi < 30.0 -> addLong("RSI dönüş bölgesi", 5)
                rsi > 70.0 -> addShort("RSI dönüş bölgesi", 5)
            }
        }

        if (x.macd?.isFinite() == true && x.macdSignal?.isFinite() == true) {
            if (x.macd > x.macdSignal) addLong("MACD", 15) else addShort("MACD", 15)
        }

        val relVol = x.relativeVolume?.takeIf { it.isFinite() && it >= 0.0 }
        when {
            relVol == null -> Unit
            relVol >= 1.5 -> { addLong("Göreli Hacim", 15); addShort("Göreli Hacim", 15) }
            relVol >= 1.2 -> { addLong("Göreli Hacim", 10); addShort("Göreli Hacim", 10) }
            relVol >= 1.0 -> { addLong("Göreli Hacim", 5); addShort("Göreli Hacim", 5) }
        }

        when {
            x.changePct >= 2.0 -> addLong("Günlük Momentum", 10)
            x.changePct <= -2.0 -> addShort("Günlük Momentum", 10)
        }

        x.recommendation?.takeIf { it.isFinite() }?.let { rec ->
            when {
                rec >= 0.35 -> addLong("TV Teknik Öneri", 10)
                rec <= -0.35 -> addShort("TV Teknik Öneri", 10)
            }
        }

        x.vwma?.takeIf { it.isFinite() && it > 0.0 }?.let { vwma ->
            if (x.price > vwma) addLong("VWMA", 5) else addShort("VWMA", 5)
        }

        val atrPct = x.atr
            ?.takeIf { it.isFinite() && it >= 0.0 }
            ?.let { (it / x.price) * 100.0 }
            ?.takeIf { it.isFinite() }
        if (atrPct != null) {
            when {
                atrPct in 0.5..4.0 -> { addLong("Volatilite", 5); addShort("Volatilite", 5) }
                atrPct in 4.0..6.0 -> { addLong("Volatilite", 2); addShort("Volatilite", 2) }
            }
        }

        val direction = if (longScore >= shortScore) "LONG" else "SHORT"
        val score = maxOf(longScore, shortScore).coerceIn(0, 100)
        val chosenParts = if (direction == "LONG") longParts else shortParts

        val lowVolumePenalty = if (relVol != null && relVol < 0.8) 15.0 else 0.0
        val rsi = x.rsi ?: 50.0
        val extremeRsiPenalty = if (rsi.isFinite() && (rsi > 75.0 || rsi < 25.0)) 12.0 else 0.0
        val atrPenalty = (atrPct ?: 5.0) * 10.0
        val risk = (atrPenalty + lowVolumePenalty + extremeRsiPenalty).coerceIn(0.0, 100.0).toInt()

        val liquidity = when {
            relVol == null -> "Veri yok"
            relVol >= 1.5 -> "Hacim aktivitesi yüksek"
            relVol >= 0.8 -> "Hacim aktivitesi orta"
            else -> "Hacim aktivitesi düşük"
        }
        val technicalLabel = when {
            score >= 80 -> "Güçlü"
            score >= 65 -> "Pozitif"
            else -> "Nötr"
        }
        val volumeLabel = relVol?.let { "%.2fx".format(it) } ?: "Veri yok"

        val technical = TechnicalSnapshot(
            ema20 = e20,
            ema50 = e50,
            ema200 = e200,
            rsi14 = x.rsi?.takeIf { it.isFinite() },
            macd = x.macd?.takeIf { it.isFinite() },
            macdSignal = x.macdSignal?.takeIf { it.isFinite() },
            bbUpper = null,
            bbLower = null,
            atr14 = x.atr?.takeIf { it.isFinite() },
            vwap = null,
            volumeRatio = relVol,
            support = null,
            resistance = null,
            vwma = x.vwma?.takeIf { it.isFinite() },
            recommendation = x.recommendation?.takeIf { it.isFinite() }
        )

        val breakdown = buildList {
            addAll(chosenParts)
            add("KAP: +0 (veri yok)")
            if (technical.vwap == null) add("VWAP: +0 (TradingView Scanner alanı kullanılmadı)")
            add("Risk: $risk/100 (fırsat puanından ayrı)")
            add("Toplam: $score/100 • $direction")
        }

        return Opportunity(
            symbol = x.symbol,
            companyName = x.companyName,
            price = x.price,
            dailyChangePct = x.changePct,
            score = score,
            riskScore = risk,
            direction = direction,
            technicalLabel = technicalLabel,
            volumeLabel = volumeLabel,
            kapLabel = "Veri yok",
            liquidityLabel = liquidity,
            support = null,
            resistance = null,
            source = x.source,
            dataTimestamp = x.receivedAt,
            candles = emptyList(),
            technical = technical,
            scoreBreakdown = breakdown
        )
    }
}
