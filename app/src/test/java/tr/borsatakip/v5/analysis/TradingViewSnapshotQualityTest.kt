package tr.borsatakip.v5.analysis

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TradingViewSnapshotQualityTest {

    @Test
    fun staleSnapshot_doesNotProduceSignal() {
        val stale = sample(receivedAt = System.currentTimeMillis() - 16 * 60_000L)
        assertNull(TradingViewSnapshotScorer.score(stale))
    }

    @Test
    fun freshSnapshot_explainsConfidenceCapAndFreshness() {
        val result = TradingViewSnapshotScorer.score(sample(System.currentTimeMillis()))
        assertNotNull(result)
        val breakdown = result!!.scoreBreakdown.joinToString(" | ")
        assertTrue(breakdown.contains("Veri güveni tavanı"))
        assertTrue(breakdown.contains("15 dk üzeri snapshot için sinyal üretilmez"))
        assertTrue(result.finalSignalScore <= 100)
    }

    private fun sample(receivedAt: Long) = TradingViewSnapshotScorer.Snapshot(
        symbol = "TEST",
        companyName = "Test",
        price = 100.0,
        changePct = 2.5,
        volume = 1_000_000.0,
        relativeVolume = 1.6,
        rsi = 60.0,
        macd = 2.0,
        macdSignal = 1.0,
        ema20 = 95.0,
        ema50 = 90.0,
        ema200 = 80.0,
        atr = 2.0,
        vwma = 96.0,
        recommendation = 0.5,
        receivedAt = receivedAt,
        source = "unit"
    )
}
