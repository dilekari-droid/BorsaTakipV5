package tr.borsatakip.v5.ui.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tr.borsatakip.v5.model.Candle

class ChartMathTest {
    @Test
    fun validate_rejectsInvalidAndDeduplicatesTimestamp() {
        val raw = listOf(
            Candle(1L, 10.0, 12.0, 9.0, 11.0, 100.0),
            Candle(1L, 11.0, 13.0, 10.0, 12.0, 110.0),
            Candle(2L, 10.0, 9.0, 11.0, 10.5, 100.0),
            Candle(3L, 12.0, 13.0, 11.0, 12.5, 120.0)
        )
        val result = ChartMath.validate(raw)
        assertEquals(2, result.candles.size)
        assertEquals(1, result.rejectedCount)
        assertEquals(1, result.duplicateCount)
        assertEquals(12.0, result.candles.first().open, 0.0001)
    }

    @Test
    fun ema_requiresEnoughRealValues() {
        val short = ChartMath.ema(listOf(1.0, 2.0, 3.0), 5)
        assertTrue(short.all { it == null })
        val values = (1..30).map { it.toDouble() }
        val ema20 = ChartMath.ema(values, 20)
        assertNull(ema20[18])
        assertNotNull(ema20[19])
        assertTrue(ema20.last()!! > ema20[19]!!)
    }

    @Test
    fun rsi_risingSeriesApproaches100() {
        val values = (1..40).map { it.toDouble() }
        val rsi = ChartMath.rsi(values, 14)
        assertEquals(100.0, rsi.last()!!, 0.0001)
    }

    @Test
    fun macd_hasSignalOnlyAfterEnoughData() {
        val values = (1..80).map { 100.0 + it * 0.5 }
        val macd = ChartMath.macd(values)
        assertTrue(macd.macd.take(25).all { it == null })
        assertNotNull(macd.macd.last())
        assertNotNull(macd.signal.last())
        assertNotNull(macd.histogram.last())
    }
}
