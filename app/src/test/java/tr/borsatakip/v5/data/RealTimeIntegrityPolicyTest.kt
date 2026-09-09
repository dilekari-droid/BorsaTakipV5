package tr.borsatakip.v5.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tr.borsatakip.v5.model.Candle
import tr.borsatakip.v5.model.Stock

class RealTimeIntegrityPolicyTest {
    private fun stock(
        now: Long,
        realtime: Boolean = true,
        delay: Int? = 0,
        currentSession: Boolean = true,
        timestamp: Long = now
    ): Stock {
        val candles = (0 until 220).map { i ->
            Candle(
                timestamp = now - (220L - i) * 86_400_000L,
                open = 100.0,
                high = 102.0,
                low = 99.0,
                close = 101.0,
                volume = 1_000_000.0
            )
        }
        return Stock(
            symbol = "ASELS",
            companyName = "ASELSAN",
            candles = candles,
            source = "licensed-test-provider",
            dataTimestamp = timestamp,
            isRealtime = realtime,
            delaySeconds = delay,
            currentSessionIncluded = currentSession
        )
    }

    @Test
    fun acceptsVerifiedRealtimeData() {
        val now = 1_800_000_000_000L
        assertTrue(RealTimeIntegrityPolicy.validate(stock(now), now).accepted)
    }

    @Test
    fun rejectsUnverifiedRealtimeFlag() {
        val now = 1_800_000_000_000L
        assertFalse(RealTimeIntegrityPolicy.validate(stock(now, realtime = false), now).accepted)
    }

    @Test
    fun rejectsStaleData() {
        val now = 1_800_000_000_000L
        val stale = now - RealTimeIntegrityPolicy.MAX_DATA_AGE_MS - 1L
        assertFalse(RealTimeIntegrityPolicy.validate(stock(now, timestamp = stale), now).accepted)
    }

    @Test
    fun rejectsDeclaredDelayAboveLimit() {
        val now = 1_800_000_000_000L
        assertFalse(
            RealTimeIntegrityPolicy.validate(
                stock(now, delay = RealTimeIntegrityPolicy.MAX_DECLARED_DELAY_SECONDS + 1),
                now
            ).accepted
        )
    }

    @Test
    fun rejectsMissingCurrentSession() {
        val now = 1_800_000_000_000L
        assertFalse(RealTimeIntegrityPolicy.validate(stock(now, currentSession = false), now).accepted)
    }
}
