package tr.borsatakip.v5.analysis

import org.junit.Assert.assertEquals
import org.junit.Test
import tr.borsatakip.v5.model.DecisionState
import tr.borsatakip.v5.model.TechnicalSnapshot

class TrendUiPolicyTest {
    private fun technical(
        ema20: Double? = 110.0,
        ema50: Double? = 100.0,
        ema200: Double? = 90.0,
        rsi: Double? = 58.0,
        macd: Double? = 2.0,
        signal: Double? = 1.0
    ) = TechnicalSnapshot(
        ema20 = ema20,
        ema50 = ema50,
        ema200 = ema200,
        rsi14 = rsi,
        macd = macd,
        macdSignal = signal,
        bbUpper = null,
        bbLower = null,
        atr14 = null,
        vwap = null,
        volumeRatio = null,
        support = null,
        resistance = null
    )

    @Test fun strongBullishUsesDarkGreenState() {
        val x = TrendUiPolicy.resolve(120.0, technical(), DecisionState.VERIFIED_OPPORTUNITY)
        assertEquals(TrendUiState.STRONG_BULLISH, x.state)
        assertEquals("GÜÇLÜ YÜKSELİŞ", x.label)
        assertEquals(11, x.red)
        assertEquals(110, x.green)
        assertEquals(79, x.blue)
    }

    @Test fun bullishDoesNotRequireDailyPriceChange() {
        val x = TrendUiPolicy.resolve(
            115.0,
            technical(ema200 = 120.0, rsi = 49.0, macd = 0.5, signal = 1.0),
            DecisionState.WATCH
        )
        assertEquals(TrendUiState.BULLISH, x.state)
    }

    @Test fun strongBearishUsesDarkRedState() {
        val x = TrendUiPolicy.resolve(
            80.0,
            technical(ema20 = 90.0, ema50 = 100.0, ema200 = 110.0, rsi = 42.0, macd = -2.0, signal = -1.0),
            DecisionState.VERIFIED_OPPORTUNITY
        )
        assertEquals(TrendUiState.STRONG_BEARISH, x.state)
        assertEquals(153, x.red)
        assertEquals(27, x.green)
        assertEquals(27, x.blue)
    }

    @Test fun bearishStateIsRed() {
        val x = TrendUiPolicy.resolve(
            92.0,
            technical(ema20 = 95.0, ema50 = 100.0, ema200 = 80.0, rsi = 55.0, macd = 1.0, signal = 0.0),
            DecisionState.WATCH
        )
        assertEquals(TrendUiState.BEARISH, x.state)
        assertEquals(220, x.red)
    }

    @Test fun mixedEmaStructureIsNeutral() {
        val x = TrendUiPolicy.resolve(102.0, technical(ema20 = 100.0, ema50 = 105.0), DecisionState.WATCH)
        assertEquals(TrendUiState.NEUTRAL, x.state)
    }

    @Test fun missingRequiredEmaIsInsufficientData() {
        val x = TrendUiPolicy.resolve(100.0, technical(ema20 = null), DecisionState.WATCH)
        assertEquals(TrendUiState.INSUFFICIENT_DATA, x.state)
    }

    @Test fun rejectedDecisionOverridesTrend() {
        val x = TrendUiPolicy.resolve(120.0, technical(), DecisionState.REJECTED)
        assertEquals(TrendUiState.REJECTED, x.state)
    }
}
