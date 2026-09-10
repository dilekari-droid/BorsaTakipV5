package tr.borsatakip.v5.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import tr.borsatakip.v5.model.Candle
import tr.borsatakip.v5.model.DataMode
import tr.borsatakip.v5.model.SignalValidity
import tr.borsatakip.v5.model.Stock

class OpportunityValidityTest {
    private val now=1_800_000_000_000L
    private fun candles()=List(240){i->
        val close=20.0+i*0.02
        Candle(now-(239L-i)*86_400_000L,close,close+0.4,close-0.4,close,1_000_000.0+i*1_000)
    }

    @Test fun verifiedRealtimeBecomesValidAndFinalScoreRemainsCentral() {
        val stock=Stock("TEST",null,candles(),"licensed",now,true,0,true,receivedAt=now,receivedElapsedRealtime=10_000L)
        val x=OpportunityEngine.score(stock)
        assertNotNull(x)
        x!!
        assertEquals(DataMode.REALTIME,x.dataMode)
        assertEquals(SignalValidity.VALID,x.signalValidity)
        assertEquals(x.score,x.finalSignalScore)
        assertEquals(now,x.exchangeTimestamp)
        assertEquals(now,x.receivedAt)
    }

    @Test fun yahooFallbackIsDelayedWatchNotRealtime() {
        val stock=Stock("TEST",null,candles(),"Yahoo Finance • YEDEK / GECİKMELİ",now,false,null,false,receivedAt=now,receivedElapsedRealtime=10_000L)
        val x=OpportunityEngine.score(stock)!!
        assertEquals(DataMode.DELAYED,x.dataMode)
        assertEquals(SignalValidity.WATCH,x.signalValidity)
    }

    @Test fun futureTimestampIsRejected() {
        val stock=Stock("TEST",null,candles(),"licensed",now+20_000L,true,0,true,receivedAt=now,receivedElapsedRealtime=10_000L)
        val x=OpportunityEngine.score(stock)!!
        assertEquals(SignalValidity.REJECTED,x.signalValidity)
    }
}
