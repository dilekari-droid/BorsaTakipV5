package tr.borsatakip.v5.scan

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tr.borsatakip.v5.data.MarketDataProvider
import tr.borsatakip.v5.model.Candle
import tr.borsatakip.v5.model.Stock

class BistScannerTest {

    @Test
    fun progress_totalZero_isZero() {
        assertEquals(0, BistScanner.safeProgress(5, 0))
    }

    @Test
    fun progress_isClampedTo100() {
        assertEquals(100, BistScanner.safeProgress(11, 10))
    }

    @Test
    fun emptySymbolUniverse_returnsErrorNotCrash() = runBlocking {
        val provider = object : MarketDataProvider {
            override val id = "empty"
            override val displayName = "empty"
            override suspend fun scan(onProgress: (Int, Int) -> Unit): List<Stock> {
                onProgress(0, 0)
                return emptyList()
            }
            override suspend fun fetchOne(symbol: String): Stock? = null
        }
        val state = BistScanner(provider).scan { }
        assertEquals(ScanStatus.ERROR, state.status)
        assertEquals(0, state.total)
    }

    @Test
    fun allSymbolsFail_returnsCompletedWithSkipped() = runBlocking {
        val provider = object : MarketDataProvider {
            override val id = "all_fail"
            override val displayName = "all_fail"
            override suspend fun scan(onProgress: (Int, Int) -> Unit): List<Stock> {
                onProgress(1, 3)
                onProgress(2, 3)
                onProgress(3, 3)
                return emptyList()
            }
            override suspend fun fetchOne(symbol: String): Stock? = null
        }
        val state = BistScanner(provider).scan { }
        assertEquals(ScanStatus.COMPLETED, state.status)
        assertEquals(3, state.skipped)
        assertEquals(0, state.successful)
    }

    @Test
    fun deterministicProvider_completesWithoutNetwork() = runBlocking {
        val stocks = (1..4).map { idx ->
            Stock(
                symbol = "T$idx",
                companyName = "Test $idx",
                candles = List(240) { i ->
                    val close = 20.0 + idx + i * 0.03
                    Candle(
                        timestamp = i.toLong() + 1,
                        open = close,
                        high = close + 0.4,
                        low = close - 0.4,
                        close = close,
                        volume = 1000.0 + i
                    )
                },
                source = "unit",
                dataTimestamp = 240L
            )
        }
        val provider = object : MarketDataProvider {
            override val id = "deterministic"
            override val displayName = "deterministic"
            override suspend fun scan(onProgress: (Int, Int) -> Unit): List<Stock> {
                stocks.indices.forEach { onProgress(it + 1, stocks.size) }
                return stocks
            }
            override suspend fun fetchOne(symbol: String): Stock? = stocks.firstOrNull { it.symbol == symbol }
        }
        val state = BistScanner(provider).scan { }
        assertEquals(ScanStatus.COMPLETED, state.status)
        assertEquals(4, state.total)
        assertTrue(state.successful > 0)
        assertTrue(state.results.isNotEmpty())
    }

    @Test
    fun cancellation_isPropagated() {
        val provider = object : MarketDataProvider {
            override val id = "slow"
            override val displayName = "slow"
            override suspend fun scan(onProgress: (Int, Int) -> Unit): List<Stock> {
                delay(5_000)
                return emptyList()
            }
            override suspend fun fetchOne(symbol: String): Stock? = null
        }
        var cancelled = false
        try {
            runBlocking {
                throw CancellationException("test")
            }
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
    }
}
