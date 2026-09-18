package tr.borsatakip.v5.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tr.borsatakip.v5.model.Candle
import tr.borsatakip.v5.model.Stock

class RealTimeIntegrityPolicyTest {
    private fun stock(
        receivedAt: Long,
        receivedElapsed: Long = 10_000L,
        realtime: Boolean = true,
        delay: Int? = 0,
        currentSession: Boolean = true,
        timestamp: Long = receivedAt
    ): Stock {
        val candles = (0 until 220).map { i ->
            Candle(receivedAt - (220L - i) * 86_400_000L,100.0,102.0,99.0,101.0,1_000_000.0)
        }
        return Stock(
            symbol="ASELS", companyName="ASELSAN", candles=candles, source="licensed-test-provider",
            dataTimestamp=timestamp, isRealtime=realtime, delaySeconds=delay, currentSessionIncluded=currentSession,
            receivedAt=receivedAt, receivedElapsedRealtime=receivedElapsed
        )
    }

    @Test fun acceptsVerifiedRealtimeData() {
        val received = 1_800_000_000_000L
        assertTrue(RealTimeIntegrityPolicy.validate(stock(received),10_500L).accepted)
    }

    @Test fun rejectsUnverifiedRealtimeFlag() {
        val received = 1_800_000_000_000L
        assertFalse(RealTimeIntegrityPolicy.validate(stock(received,realtime=false),10_500L).accepted)
    }

    @Test fun rejectsStaleDataUsingReceiptAnchor() {
        val received = 1_800_000_000_000L
        val stale = received - RealTimeIntegrityPolicy.MAX_DATA_AGE_MS - 1L
        assertFalse(RealTimeIntegrityPolicy.validate(stock(received,timestamp=stale),10_500L).accepted)
    }

    @Test fun monotonicElapsedMakesFreshDataStaleWithoutWallClockDependency() {
        val received = 1_800_000_000_000L
        val x = stock(received,receivedElapsed=10_000L)
        assertFalse(RealTimeIntegrityPolicy.validate(x,10_000L + RealTimeIntegrityPolicy.MAX_DATA_AGE_MS + 1L).accepted)
    }

    @Test fun rejectsFutureTimestamp() {
        val received = 1_800_000_000_000L
        val future = received + 16_000L
        assertFalse(RealTimeIntegrityPolicy.validate(stock(received,timestamp=future),10_500L).accepted)
    }

    @Test fun rejectsDeclaredDelayAboveLimit() {
        val received = 1_800_000_000_000L
        assertFalse(RealTimeIntegrityPolicy.validate(stock(received,delay=RealTimeIntegrityPolicy.MAX_DECLARED_DELAY_SECONDS+1),10_500L).accepted)
    }

    @Test fun rejectsMissingCurrentSession() {
        val received = 1_800_000_000_000L
        assertFalse(RealTimeIntegrityPolicy.validate(stock(received,currentSession=false),10_500L).accepted)
    }
}
