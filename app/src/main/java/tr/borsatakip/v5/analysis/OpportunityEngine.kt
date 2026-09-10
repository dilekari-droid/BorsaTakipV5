package tr.borsatakip.v5.analysis

import tr.borsatakip.v5.data.RealTimeIntegrityPolicy
import tr.borsatakip.v5.model.DataMode
import tr.borsatakip.v5.model.DataSnapshot
import tr.borsatakip.v5.model.Opportunity
import tr.borsatakip.v5.model.OpportunitySnapshot
import tr.borsatakip.v5.model.SignalValidity
import tr.borsatakip.v5.model.Stock

object OpportunityEngine {
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
        t.vwap?.takeIf { it.isFinite() && it > 0.0 }?.let { vwap -> if (price > vwap) addLong("VWAP", 5) else addShort("VWAP", 5) }

        val atrPct = t.atr14?.takeIf { it.isFinite() && it >= 0.0 }?.let { (it / price) * 100.0 }?.takeIf { it.isFinite() } ?: 99.0
        when {
            atrPct in 0.5..4.0 -> { addLong("Volatilite", 5); addShort("Volatilite", 5) }
            atrPct in 4.0..6.0 -> { addLong("Volatilite", 2); addShort("Volatilite", 2) }
        }

        val direction = if (longScore >= shortScore) "LONG" else "SHORT"
        // V5.1.26 sözleşmesi korunur: finalSignalScore için yeni ağırlık/formül icat edilmez.
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
        val technicalLabel = when { score >= 80 -> "Güçlü"; score >= 65 -> "Pozitif"; else -> "Nötr" }
        val volumeLabel = volumeRatio?.let { "%.2fx".format(it) } ?: "Veri yok"
        val change = ((price / prev) - 1.0) * 100.0
        if (!change.isFinite()) return null

        val confidence = calculateDataConfidence(price,c.size,volumeRatio,support,resistance,t.vwap,t.ema20,t.ema50,t.ema200,t.rsi14,t.macd,t.macdSignal,kapLabel)
        val confidenceLabel = when { confidence >= 80 -> "Yüksek"; confidence >= 60 -> "Orta"; else -> "Düşük" }

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
            SignalValidity.WATCH -> if (integrity.accepted) "Teknik yapı var; veri güveni teyit için yeterli değil." else integrity.reason
            SignalValidity.INSUFFICIENT -> integrity.reason
            SignalValidity.REJECTED -> integrity.reason
        }

        val breakdown = buildList {
            addAll(chosenParts)
            add("KAP: +0 (${if (kapLabel == "Veri yok") "veri yok" else kapLabel})")
            add("Risk: $risk/100 (fırsat puanından ayrı)")
            add("Veri Güveni: $confidence/100 ($confidenceLabel)")
            add("Toplam: $score/100 • $direction")
        }
        val dataSnapshot = DataSnapshot(stock.source,stock.symbol,price,stock.exchangeTimestamp,stock.receivedAt,dataMode,stock.delaySeconds,stock.currentSessionIncluded)
        val snapshot = OpportunitySnapshot(stock.source,stock.symbol,price,stock.exchangeTimestamp,stock.receivedAt,dataMode,confidence,t,score,dataSnapshot=dataSnapshot)

        return Opportunity(
            symbol=stock.symbol, companyName=stock.companyName, price=price, dailyChangePct=change,
            score=score, riskScore=risk, direction=direction, technicalLabel=technicalLabel, volumeLabel=volumeLabel,
            kapLabel=kapLabel, liquidityLabel=liquidity, support=support, resistance=resistance,
            source=stock.source, dataTimestamp=stock.dataTimestamp, candles=c, technical=t, scoreBreakdown=breakdown,
            dataConfidenceScore=confidence, dataConfidenceLabel=confidenceLabel, finalSignalScore=score,
            isRealtime=stock.isRealtime, delaySeconds=stock.delaySeconds, currentSessionIncluded=stock.currentSessionIncluded,
            exchangeTimestamp=stock.exchangeTimestamp, receivedAt=stock.receivedAt, receivedElapsedRealtime=stock.receivedElapsedRealtime,
            dataMode=dataMode, signalValidity=validity, signalValidityReason=validityReason, snapshot=snapshot
        )
    }

    private fun calculateDataConfidence(price:Double,candleCount:Int,volumeRatio:Double?,support:Double?,resistance:Double?,vwap:Double?,ema20:Double?,ema50:Double?,ema200:Double?,rsi:Double?,macd:Double?,macdSignal:Double?,kapLabel:String):Int {
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
        return value.coerceIn(0,100)
    }
}
