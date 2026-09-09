package tr.borsatakip.v5.analysis

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tr.borsatakip.v5.data.MarketDataProvider
import tr.borsatakip.v5.model.Candle
import tr.borsatakip.v5.model.Stock
import tr.borsatakip.v5.scan.BistScanner
import tr.borsatakip.v5.scan.ScanStatus
import java.net.SocketTimeoutException

class TechnicalSafetyTest {

    @Test
    fun emptyCandles_doNotCrash() {
        val t = TechnicalAnalyzer.analyze(emptyList())
        assertNull(t.ema200)
        assertNull(t.rsi14)
    }

    @Test
    fun insufficientCandles_returnNoOpportunity() {
        val stock = stockWith(List(20) { candle(it, 10.0 + it) })
        assertNull(OpportunityEngine.score(stock))
    }

    @Test
    fun nanCandle_isRejected() {
        val candles = MutableList(220) { candle(it, 20.0 + it * 0.01) }
        candles[100] = candles[100].copy(close = Double.NaN)
        assertNull(OpportunityEngine.score(stockWith(candles)))
    }

    @Test
    fun infinityCandle_isRejected() {
        val candles = MutableList(220) { candle(it, 20.0 + it * 0.01) }
        candles[100] = candles[100].copy(volume = Double.POSITIVE_INFINITY)
        assertNull(OpportunityEngine.score(stockWith(candles)))
    }

    @Test
    fun zeroPrice_doesNotDivideByZero() {
        val candles = MutableList(220) { candle(it, 10.0) }
        candles[219] = candles[219].copy(close = 0.0)
        assertNull(OpportunityEngine.score(stockWith(candles)))
    }

    @Test
    fun zeroVolume_vwapIsNull() {
        val candles = List(220) { candle(it, 15.0 + it * 0.01).copy(volume = 0.0) }
        val t = TechnicalAnalyzer.analyze(candles)
        assertNull(t.vwap)
    }

    @Test
    fun flatSeries_rsiRemainsFinite() {
        val candles = List(220) { candle(it, 25.0) }
        val t = TechnicalAnalyzer.analyze(candles)
        assertTrue(t.rsi14 == null || t.rsi14!!.isFinite())
    }

    @Test
    fun oneSymbolMissing_doesNotStopScan() = runBlocking {
        val good = stockWith(List(240) { candle(it, 20.0 + it * 0.05) })
        val provider = object : MarketDataProvider {
            override val id = "partial"
            override val displayName = "partial"
            override suspend fun scan(onProgress: (Int, Int) -> Unit): List<Stock> {
                onProgress(1, 2)
                onProgress(2, 2)
                return listOf(good)
            }
            override suspend fun fetchOne(symbol: String): Stock? = if (symbol == "GOOD") good else null
        }
        val state = BistScanner(provider).scan { }
        assertEquals(ScanStatus.COMPLETED, state.status)
        assertEquals(1, state.skipped)
        assertEquals(1, state.successful)
    }

    @Test
    fun providerTimeout_becomesControlledError() = runBlocking {
        val provider = object : MarketDataProvider {
            override val id = "timeout"
            override val displayName = "timeout"
            override suspend fun scan(onProgress: (Int, Int) -> Unit): List<Stock> {
                throw SocketTimeoutException("timeout")
            }
            override suspend fun fetchOne(symbol: String): Stock? = null
        }
        val state = BistScanner(provider).scan { }
        assertEquals(ScanStatus.ERROR, state.status)
    }

    @Test
    fun networkFailure_becomesControlledError() = runBlocking {
        val provider = object : MarketDataProvider {
            override val id = "network"
            override val displayName = "network"
            override suspend fun scan(onProgress: (Int, Int) -> Unit): List<Stock> {
                throw java.io.IOException("offline")
            }
            override suspend fun fetchOne(symbol: String): Stock? = null
        }
        val state = BistScanner(provider).scan { }
        assertEquals(ScanStatus.ERROR, state.status)
    }

    private fun stockWith(candles: List<Candle>) = Stock(
        symbol = "TEST",
        companyName = "Test",
        candles = candles,
        source = "unit",
        dataTimestamp = System.currentTimeMillis(),
        isRealtime = true,
        delaySeconds = 0,
        currentSessionIncluded = true
    )

    private fun candle(i: Int, close: Double) = Candle(
        timestamp = i.toLong() + 1,
        open = close,
        high = close + 0.5,
        low = close - 0.5,
        close = close,
        volume = 1000.0
    )
}
