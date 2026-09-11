package tr.borsatakip.v5.scan

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tr.borsatakip.v5.data.MarketDataProvider
import tr.borsatakip.v5.data.ProviderScanReport
import tr.borsatakip.v5.data.ProviderSymbolResult
import tr.borsatakip.v5.data.ProviderSymbolStatus
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
    fun allSymbolsFail_returnsCompletedWithTerminalFailures() = runBlocking {
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
        assertEquals(3, state.total)
        assertEquals(3, state.terminalResults.size)
        assertEquals(3, state.skipped)
        assertEquals(0, state.successful)
    }

    @Test
    fun deterministicProvider_everySymbolGetsTerminalState() = runBlocking {
        val now = System.currentTimeMillis()
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
                dataTimestamp = now,
                isRealtime = true,
                delaySeconds = 0,
                currentSessionIncluded = true
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
        assertEquals(4, state.terminalResults.size)
        assertTrue(state.terminalResults.all { it.status == SymbolTerminalStatus.SIGNAL || it.status == SymbolTerminalStatus.NO_SIGNAL })
    }

    @Test
    fun detailedProvider_preservesTimeoutAndHttpError() = runBlocking {
        val provider = object : MarketDataProvider {
            override val id = "terminal"
            override val displayName = "terminal"
            override suspend fun scan(onProgress: (Int, Int) -> Unit): List<Stock> = emptyList()
            override suspend fun scanDetailed(onProgress: (ProviderSymbolResult, Int, Int) -> Unit): ProviderScanReport {
                val items = listOf(
                    ProviderSymbolResult("AAA", ProviderSymbolStatus.TIMEOUT, attempt = 3, errorMessage = "timeout"),
                    ProviderSymbolResult("BBB", ProviderSymbolStatus.HTTP_ERROR, attempt = 1, httpCode = 500, errorMessage = "HTTP 500")
                )
                items.forEachIndexed { index, item -> onProgress(item, index + 1, items.size) }
                return ProviderScanReport(items.size, items)
            }
            override suspend fun fetchOne(symbol: String): Stock? = null
        }
        val state = BistScanner(provider).scan { }
        assertEquals(ScanStatus.COMPLETED, state.status)
        assertEquals(2, state.terminalResults.size)
        assertEquals(1, state.timeout)
        assertEquals(1, state.httpErrors)
        assertEquals(2, state.skipped)
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
