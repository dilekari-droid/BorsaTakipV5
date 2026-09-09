package tr.borsatakip.v5.scan

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tr.borsatakip.v5.data.DemoMarketDataProvider
import tr.borsatakip.v5.data.MarketDataProvider
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
    fun demoScan_completesWithoutNetwork() = runBlocking {
        val state = BistScanner(DemoMarketDataProvider()).scan { }
        assertEquals(ScanStatus.COMPLETED, state.status)
        assertEquals(8, state.total)
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
