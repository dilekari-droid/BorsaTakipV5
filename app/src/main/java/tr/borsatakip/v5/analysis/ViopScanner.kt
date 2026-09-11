package tr.borsatakip.v5.analysis

import tr.borsatakip.v5.data.BackendProvider
import tr.borsatakip.v5.model.ScanRunStatus
import tr.borsatakip.v5.model.SignalValidity
import tr.borsatakip.v5.model.ViopContract
import tr.borsatakip.v5.model.ViopOpportunity
import tr.borsatakip.v5.model.ViopScanError
import tr.borsatakip.v5.model.ViopScanProgress
import tr.borsatakip.v5.model.ViopScanResult
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToInt

class ViopScanner(private val backend: BackendProvider) {
    companion object {
        const val MIN_HISTORY_BARS = 220
        const val MIN_VOLUME = 1.0
        const val MIN_OPEN_INTEREST = 1L
        const val MAX_DATA_AGE_MS = 60_000L
    }

    suspend fun scan(onProgress: (ViopScanProgress) -> Unit = {}): ViopScanResult {
        val startedAt = System.currentTimeMillis()
        val universeResult = backend.loadViop()
        if (universeResult.isFailure) {
            val err = ViopScanError("-", codeOf(universeResult.exceptionOrNull(), "CONTRACT_ERROR"), universeResult.exceptionOrNull()?.message ?: "VİOP sözleşme evreni alınamadı.")
            return ViopScanResult(emptyList(), listOf(err), ViopScanProgress(failed = 1), startedAt, System.currentTimeMillis(), ScanRunStatus.FAILED)
        }
        val universe = universeResult.getOrDefault(emptyList()).filter { !it.isManual }
        if (universe.isEmpty()) {
            val err = ViopScanError("-", "CONTRACT_ERROR", "Aktif Production VİOP sözleşme evreni boş.")
            return ViopScanResult(emptyList(), listOf(err), ViopScanProgress(), startedAt, System.currentTimeMillis(), ScanRunStatus.FAILED)
        }

        var p = ViopScanProgress(total = universe.size)
        val opportunities = mutableListOf<ViopOpportunity>()
        val errors = mutableListOf<ViopScanError>()
        onProgress(p)

        for (contract in universe) {
            if (contract.validity == SignalValidity.REJECTED) {
                p = p.copy(eliminated = p.eliminated + 1)
                errors += ViopScanError(contract.symbol, "EXPIRED", contract.validityReason)
                onProgress(p)
                continue
            }

            val quoteResult = backend.loadViopQuote(contract.symbol)
            if (quoteResult.isFailure) {
                p = p.copy(failed = p.failed + 1)
                errors += ViopScanError(contract.symbol, codeOf(quoteResult.exceptionOrNull(), "QUOTE_ERROR"), quoteResult.exceptionOrNull()?.message ?: "Quote alınamadı.")
                onProgress(p)
                continue
            }
            val quote = quoteResult.getOrThrow()
            p = p.copy(quoteSuccess = p.quoteSuccess + 1)
            onProgress(p)

            val age = (System.currentTimeMillis() - quote.exchangeTimestamp).coerceAtLeast(0L)
            if (age > MAX_DATA_AGE_MS) {
                p = p.copy(eliminated = p.eliminated + 1)
                errors += ViopScanError(contract.symbol, "STALE_DATA", "Quote veri yaşı ${age} ms; limit $MAX_DATA_AGE_MS ms.")
                onProgress(p)
                continue
            }

            val historyResult = backend.loadViopHistory(contract.symbol)
            if (historyResult.isFailure) {
                val code = codeOf(historyResult.exceptionOrNull(), "HISTORY_ERROR")
                p = if (code == "INSUFFICIENT_HISTORY") p.copy(insufficient = p.insufficient + 1) else p.copy(failed = p.failed + 1)
                errors += ViopScanError(contract.symbol, code, historyResult.exceptionOrNull()?.message ?: "History alınamadı.")
                onProgress(p)
                continue
            }
            val candles = historyResult.getOrThrow()
            p = p.copy(historySuccess = p.historySuccess + 1)
            onProgress(p)

            if (candles.size < MIN_HISTORY_BARS) {
                p = p.copy(insufficient = p.insufficient + 1)
                errors += ViopScanError(contract.symbol, "INSUFFICIENT_HISTORY", "${candles.size} mum; minimum $MIN_HISTORY_BARS.")
                onProgress(p)
                continue
            }

            val volume = quote.volume ?: contract.volume
            val oi = quote.openInterest ?: contract.openInterest
            if ((volume == null || volume < MIN_VOLUME) || (oi == null || oi < MIN_OPEN_INTEREST)) {
                p = p.copy(eliminated = p.eliminated + 1)
                errors += ViopScanError(contract.symbol, "LOW_LIQUIDITY", "Hacim/açık pozisyon eşiği sağlanmadı.")
                onProgress(p)
                continue
            }

            val technical = TechnicalAnalyzer.analyze(candles)
            val opportunity = score(contract, quote, candles.size, technical, age)
            p = p.copy(
                analyzed = p.analyzed + 1,
                longCount = p.longCount + if (opportunity.direction == "LONG") 1 else 0,
                shortCount = p.shortCount + if (opportunity.direction == "SHORT") 1 else 0
            )
            opportunities += opportunity
            onProgress(p)
        }

        val sorted = opportunities.sortedByDescending { it.finalScore }
        val completedAt = System.currentTimeMillis()
        val status = when {
            sorted.isEmpty() && p.failed > 0 -> ScanRunStatus.FAILED
            p.failed > 0 || p.insufficient > 0 || p.eliminated > 0 -> ScanRunStatus.PARTIAL
            else -> ScanRunStatus.COMPLETE
        }
        return ViopScanResult(sorted, errors, p, startedAt, completedAt, status)
    }

    private fun score(
        contract: ViopContract,
        quote: tr.borsatakip.v5.model.ViopQuote,
        candleCount: Int,
        t: tr.borsatakip.v5.model.TechnicalSnapshot,
        age: Long
    ): ViopOpportunity {
        val price = quote.price
        var longTech = 0
        var shortTech = 0
        if (t.ema20 != null && t.ema50 != null && t.ema200 != null) {
            if (t.ema20 > t.ema50 && t.ema50 > t.ema200 && price >= t.ema20) longTech += 25
            if (t.ema20 < t.ema50 && t.ema50 < t.ema200 && price <= t.ema20) shortTech += 25
        }
        if (t.rsi14 != null) {
            if (t.rsi14 in 50.0..72.0) longTech += 10
            if (t.rsi14 in 28.0..50.0) shortTech += 10
        }
        if (t.macd != null && t.macdSignal != null) {
            if (t.macd > t.macdSignal) longTech += 10 else if (t.macd < t.macdSignal) shortTech += 10
        }
        val momentum = if (t.ema20 != null && t.ema20 != 0.0) ((price - t.ema20) / abs(t.ema20)) else 0.0
        val momentumLong = when { momentum > 0.03 -> 15; momentum > 0.0 -> 8; else -> 0 }
        val momentumShort = when { momentum < -0.03 -> 15; momentum < 0.0 -> 8; else -> 0 }
        val liquidity = liquidityScore(quote.volume ?: contract.volume, quote.openInterest ?: contract.openInterest)
        val volatility = volatilityScore(t.atr14, price)
        val dataQuality = when {
            age <= 5_000L -> 10
            age <= 30_000L -> 8
            age <= MAX_DATA_AGE_MS -> 5
            else -> 0
        }
        val oiScore = openInterestScore(quote.openInterest ?: contract.openInterest)
        val expiryRisk = expiryRisk(contract.expiry)
        val expiryContribution = ((100 - expiryRisk) * 15 / 100.0).roundToInt()
        val longScore = (longTech + momentumLong + liquidity + volatility + dataQuality + oiScore + expiryContribution).coerceIn(0, 100)
        val shortScore = (shortTech + momentumShort + liquidity + volatility + dataQuality + oiScore + expiryContribution).coerceIn(0, 100)
        val direction = if (longScore >= shortScore) "LONG" else "SHORT"
        val final = maxOf(longScore, shortScore)
        val technicalScore = maxOf(longTech + momentumLong, shortTech + momentumShort).coerceIn(0, 100)
        val risk = ((100 - liquidity).coerceAtLeast(0) * 0.25 + expiryRisk * 0.45 + (10 - dataQuality) * 3.0).roundToInt().coerceIn(0,100)
        val reason = buildList {
            add("$direction skoru $final/100")
            if (t.ema20 != null && t.ema50 != null && t.ema200 != null) add("EMA20/50/200 trendi değerlendirildi")
            if (t.rsi14 != null) add("RSI14=${"%.1f".format(t.rsi14)}")
            if (t.macd != null && t.macdSignal != null) add("MACD yönü değerlendirildi")
            add("Likidite=$liquidity/15")
            add("Vade riski=$expiryRisk/100")
            add("Veri yaşı=${age/1000}s")
        }.joinToString(" • ")
        return ViopOpportunity(
            contract=contract, quote=quote, candles=emptyList(), technical=t,
            technicalScore=technicalScore, riskScore=risk, liquidityScore=liquidity,
            expiryRisk=expiryRisk, longScore=longScore, shortScore=shortScore,
            finalScore=final, direction=direction, signalReason=reason,
            validity=SignalValidity.VALID, dataAgeMs=age, historyCandleCount=candleCount
        )
    }

    private fun liquidityScore(volume: Double?, oi: Long?): Int {
        val v = volume ?: 0.0
        val o = oi ?: 0L
        val vs = when { v >= 100_000 -> 8; v >= 10_000 -> 6; v >= 1_000 -> 4; v > 0 -> 2; else -> 0 }
        val os = when { o >= 50_000 -> 7; o >= 10_000 -> 5; o >= 1_000 -> 3; o > 0 -> 1; else -> 0 }
        return (vs + os).coerceIn(0, 15)
    }

    private fun openInterestScore(oi: Long?): Int = when (oi ?: 0L) {
        in 100_000L..Long.MAX_VALUE -> 10
        in 25_000L until 100_000L -> 8
        in 5_000L until 25_000L -> 5
        in 1L until 5_000L -> 2
        else -> 0
    }

    private fun volatilityScore(atr: Double?, price: Double): Int {
        if (atr == null || atr <= 0 || price <= 0) return 0
        val pct = atr / price
        return when {
            pct in 0.005..0.04 -> 10
            pct in 0.002..0.08 -> 6
            else -> 2
        }
    }

    private fun expiryRisk(expiry: String): Int = runCatching {
        val ym = YearMonth.parse(expiry)
        val days = ChronoUnit.DAYS.between(LocalDate.now(), ym.atEndOfMonth()).toInt()
        when {
            days < 0 -> 100
            days <= 5 -> 90
            days <= 10 -> 75
            days <= 20 -> 55
            days <= 40 -> 35
            else -> 20
        }
    }.getOrDefault(100)

    private fun codeOf(t: Throwable?, fallback: String): String {
        val text = t?.message.orEmpty().uppercase()
        return listOf("AUTH_ERROR","CONTRACT_ERROR","QUOTE_ERROR","HISTORY_ERROR","INSUFFICIENT_HISTORY","STALE_DATA","LOW_LIQUIDITY","EXPIRED","RATE_LIMIT","SERVER_ERROR").firstOrNull { text.contains(it) } ?: fallback
    }
}
