package tr.borsatakip.v5.analysis

import org.junit.Assert.assertEquals
import org.junit.Test

class OpportunityEngineLrcTest {
    private val mid = 100.0
    private val u1 = 110.0
    private val l1 = 90.0
    private val u2 = 120.0
    private val l2 = 80.0

    @Test fun channelPosition_exactUpper1_staysInMiddleToPlus1() {
        assertEquals("ORTA / +1σ", OpportunityEngine.channelPosition(u1, mid, u1, l1, u2, l2))
    }

    @Test fun channelPosition_exactUpper2_staysInPlus1ToPlus2() {
        assertEquals("+1σ / +2σ", OpportunityEngine.channelPosition(u2, mid, u1, l1, u2, l2))
    }

    @Test fun channelPosition_exactLower1_staysInMinus1ToMiddle() {
        assertEquals("-1σ / ORTA", OpportunityEngine.channelPosition(l1, mid, u1, l1, u2, l2))
    }

    @Test fun channelPosition_exactLower2_staysInMinus2ToMinus1() {
        assertEquals("-2σ / -1σ", OpportunityEngine.channelPosition(l2, mid, u1, l1, u2, l2))
    }

    @Test fun channelPosition_outsideTwoSigma_isExplicit() {
        assertEquals("ALT -2σ ALTINDA", OpportunityEngine.channelPosition(79.99, mid, u1, l1, u2, l2))
        assertEquals("+2σ ÜZERİNDE", OpportunityEngine.channelPosition(120.01, mid, u1, l1, u2, l2))
    }
}
