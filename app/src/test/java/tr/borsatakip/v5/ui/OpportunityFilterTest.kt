package tr.borsatakip.v5.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tr.borsatakip.v5.model.Candle
import tr.borsatakip.v5.model.Opportunity
import tr.borsatakip.v5.model.TechnicalSnapshot

class OpportunityFilterTest {
    private fun item(symbol: String, direction: String, finalScore: Int) = Opportunity(
        symbol = symbol,
        companyName = null,
        price = 100.0,
        dailyChangePct = 0.0,
        score = finalScore,
        riskScore = 30,
        direction = direction,
        technicalLabel = "",
        volumeLabel = "",
        kapLabel = "",
        liquidityLabel = "",
        support = null,
        resistance = null,
        source = "test",
        dataTimestamp = 1L,
        candles = listOf(Candle(1L, 1.0, 1.0, 1.0, 1.0, 1.0)),
        technical = TechnicalSnapshot(null, null, null, null, null, null, null, null, null, null, null, null, null),
        finalSignalScore = finalScore
    )

    private val all = listOf(
        item("AAA", "LONG", 87),
        item("BBB", "SHORT", 87),
        item("CCC", "LONG", 84),
        item("DDD", "SHORT", 70)
    )

    @Test fun allFiltersOffShowsAll() {
        assertEquals(4, OpportunityFilter.apply(all, OpportunityFilterState()).size)
    }

    @Test fun longFilterHidesShort() {
        val result = OpportunityFilter.apply(all, OpportunityFilterState(longEnabled = true))
        assertTrue(result.all { it.direction == "LONG" })
        assertEquals(2, result.size)
    }

    @Test fun shortFilterHidesLong() {
        val result = OpportunityFilter.apply(all, OpportunityFilterState(shortEnabled = true))
        assertTrue(result.all { it.direction == "SHORT" })
        assertEquals(2, result.size)
    }

    @Test fun highPowerUsesDisplayedFinalSignalScore() {
        val result = OpportunityFilter.apply(all, OpportunityFilterState(highPowerEnabled = true))
        assertTrue(result.all { it.finalSignalScore >= OpportunityFilter.HIGH_POWER_THRESHOLD })
        assertEquals(listOf("AAA", "BBB"), result.map { it.symbol })
    }

    @Test fun longAndHighPowerCompose() {
        val result = OpportunityFilter.apply(all, OpportunityFilterState(longEnabled = true, highPowerEnabled = true))
        assertEquals(listOf("AAA"), result.map { it.symbol })
    }

    @Test fun shortAndHighPowerCompose() {
        val result = OpportunityFilter.apply(all, OpportunityFilterState(shortEnabled = true, highPowerEnabled = true))
        assertEquals(listOf("BBB"), result.map { it.symbol })
    }

    @Test fun longAndShortTogetherShowBothDirections() {
        val result = OpportunityFilter.apply(all, OpportunityFilterState(longEnabled = true, shortEnabled = true))
        assertEquals(4, result.size)
    }
}
