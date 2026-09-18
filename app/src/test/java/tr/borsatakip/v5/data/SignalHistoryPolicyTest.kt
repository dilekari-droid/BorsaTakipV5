package tr.borsatakip.v5.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tr.borsatakip.v5.model.ScanRunStatus

class SignalHistoryPolicyTest {
    @Test fun complete631Persists631() {
        assertEquals(631, SignalHistoryPolicy.expectedPersistedCount(ScanRunStatus.COMPLETE, 631))
    }

    @Test fun partial588Persists588() {
        assertEquals(588, SignalHistoryPolicy.expectedPersistedCount(ScanRunStatus.PARTIAL, 588))
    }

    @Test fun zeroSuccessfulPersistsNothing() {
        assertEquals(0, SignalHistoryPolicy.expectedPersistedCount(ScanRunStatus.PARTIAL, 0))
    }

    @Test fun failedAndStartedNeverPersist() {
        assertFalse(SignalHistoryPolicy.shouldPersist(ScanRunStatus.FAILED, 10))
        assertFalse(SignalHistoryPolicy.shouldPersist(ScanRunStatus.STARTED, 10))
    }

    @Test fun duplicateKeyIsStableAndUsesSignalTime() {
        val a = SignalHistoryPolicy.uniqueKey("scan-1", "thyao", 123456L)
        val b = SignalHistoryPolicy.uniqueKey("scan-1", "THYAO", 123456L)
        val c = SignalHistoryPolicy.uniqueKey("scan-1", "THYAO", 123457L)
        assertEquals(a, b)
        assertTrue(a != c)
    }
}
