package tr.borsatakip.v5.scan

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tr.borsatakip.v5.data.MarketDataProvider
import tr.borsatakip.v5.model.Candle
import tr.borsatakip.v5.model.ScanRunStatus
import tr.borsatakip.v5.model.Stock

class BistScannerTest {
    private class CapturingRecorder : HistoryRecorder {
        val states = mutableListOf<ScanState>()
        override suspend fun record(state: ScanState): HistoryRecordResult {
            states += state
            return HistoryRecordResult(inserted = state.results.size)
        }
    }

    @Test fun progress_totalZero_isZero() { assertEquals(0, BistScanner.safeProgress(5,0)) }
    @Test fun progress_isClampedTo100() { assertEquals(100, BistScanner.safeProgress(11,10)) }

    @Test fun emptySymbolUniverse_returnsErrorNotCrash() = runBlocking {
        val provider = object:MarketDataProvider {
            override val id="empty"; override val displayName="empty"
            override suspend fun scan(onProgress:(Int,Int)->Unit):List<Stock>{ onProgress(0,0); return emptyList() }
            override suspend fun fetchOne(symbol:String):Stock?=null
        }
        val recorder = CapturingRecorder()
        val state=BistScanner(provider, recorder).scan{}
        assertEquals(ScanStatus.ERROR,state.status)
        assertEquals(ScanRunStatus.FAILED,state.scanRun?.status)
        assertTrue(recorder.states.isEmpty())
    }

    @Test fun allSymbolsFail_isFailedAndHasZeroSuccessfulAnalysis() = runBlocking {
        val provider=object:MarketDataProvider{
            override val id="all_fail"; override val displayName="all_fail"
            override suspend fun scan(onProgress:(Int,Int)->Unit):List<Stock>{ (1..3).forEach{onProgress(it,3)}; return emptyList() }
            override suspend fun fetchOne(symbol:String):Stock?=null
        }
        val recorder = CapturingRecorder()
        val state=BistScanner(provider, recorder).scan{}
        assertEquals(ScanStatus.COMPLETED,state.status)
        assertEquals(3,state.processed)
        assertEquals(3,state.total)
        assertEquals(100,state.progress)
        assertEquals(0,state.successful)
        assertEquals(3,state.skipped)
        assertEquals(ScanRunStatus.FAILED,state.scanRun?.status)
        assertEquals(0, state.historyPersisted)
        assertEquals(1, recorder.states.size)
    }

    @Test fun deterministicVerifiedProvider_isComplete_andHistoryRecorderRuns() = runBlocking {
        val now=System.currentTimeMillis()
        val stocks=(1..4).map{idx-> verifiedStock("T$idx",now,idx)}
        val provider=object:MarketDataProvider{
            override val id="deterministic"; override val displayName="deterministic"
            override suspend fun scan(onProgress:(Int,Int)->Unit):List<Stock>{stocks.indices.forEach{onProgress(it+1,stocks.size)};return stocks}
            override suspend fun fetchOne(symbol:String)=stocks.firstOrNull{it.symbol==symbol}
        }
        val recorder = CapturingRecorder()
        val state=BistScanner(provider, recorder).scan{}
        assertEquals(ScanStatus.COMPLETED,state.status)
        assertEquals(ScanRunStatus.COMPLETE,state.scanRun?.status)
        assertEquals(4,state.successful)
        assertEquals(0,state.skipped)
        assertEquals(0,state.integrityRejected)
        assertTrue(state.results.isNotEmpty())
        assertEquals(1, recorder.states.size)
        assertEquals(ScanRunStatus.COMPLETE, recorder.states.single().scanRun?.status)
        assertEquals(state.results.size, state.historyPersisted)
    }

    @Test fun unverifiedAnalyzableData_isPartial_andHistoryRecorderRuns() = runBlocking {
        val now=System.currentTimeMillis()
        val stock=verifiedStock("DELAY",now,1).copy(isRealtime=false,delaySeconds=null,currentSessionIncluded=false)
        val provider=object:MarketDataProvider{
            override val id="delayed"; override val displayName="delayed"
            override suspend fun scan(onProgress:(Int,Int)->Unit):List<Stock>{onProgress(1,1);return listOf(stock)}
            override suspend fun fetchOne(symbol:String)=stock
        }
        val recorder = CapturingRecorder()
        val state=BistScanner(provider, recorder).scan{}
        assertEquals(ScanStatus.COMPLETED,state.status)
        assertEquals(ScanRunStatus.PARTIAL,state.scanRun?.status)
        assertEquals(1,state.successful)
        assertEquals(0,state.skipped)
        assertEquals(1,state.integrityRejected)
        assertEquals(1,state.results.size)
        assertEquals(1, recorder.states.size)
        assertEquals(ScanRunStatus.PARTIAL, recorder.states.single().scanRun?.status)
        assertEquals(1, state.historyPersisted)
    }

    @Test fun cancellation_isPropagated() {
        var cancelled=false
        try { runBlocking { throw CancellationException("test") } } catch(_:CancellationException){cancelled=true}
        assertTrue(cancelled)
    }

    private fun verifiedStock(symbol:String,now:Long,idx:Int)=Stock(
        symbol=symbol, companyName="Test $idx",
        candles=List(240){i->val close=20.0+idx+i*0.03;Candle(now-(239L-i)*86_400_000L,close,close+0.4,close-0.4,close,1000.0+i)},
        source="unit",dataTimestamp=now,isRealtime=true,delaySeconds=0,currentSessionIncluded=true,
        receivedAt=now,receivedElapsedRealtime=1L
    )
}
