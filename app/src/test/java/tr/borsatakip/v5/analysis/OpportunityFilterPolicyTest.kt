package tr.borsatakip.v5.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tr.borsatakip.v5.model.DataMode
import tr.borsatakip.v5.model.Opportunity
import tr.borsatakip.v5.model.SignalValidity
import tr.borsatakip.v5.model.TechnicalSnapshot

class OpportunityFilterPolicyTest {
    @Test fun long_onlyReturnsPublishableLongAndSortsDescending() {
        val out=OpportunityFilterPolicy.apply(listOf(o("B",90,"LONG"),o("A",95,"LONG"),o("X",99,"SHORT")),OpportunityFilter.LONG)
        assertEquals(listOf("A","B"),out.map{it.symbol})
    }
    @Test fun short_onlyReturnsShort() {
        val out=OpportunityFilterPolicy.apply(listOf(o("L",99,"LONG"),o("S",80,"SHORT")),OpportunityFilter.SHORT)
        assertEquals(listOf("S"),out.map{it.symbol})
    }
    @Test fun highPowerUsesFinalSignal85Threshold() {
        val out=OpportunityFilterPolicy.apply(listOf(o("A",84,"LONG"),o("B",85,"SHORT"),o("C",100,"LONG")),OpportunityFilter.HIGH_POWER)
        assertEquals(listOf("C","B"),out.map{it.symbol})
    }
    @Test fun rejectedCannotEnterDirectionalOr85Filter() {
        val rejected=o("R",99,"LONG",SignalValidity.REJECTED)
        assertTrue(OpportunityFilterPolicy.apply(listOf(rejected),OpportunityFilter.LONG).isEmpty())
        assertTrue(OpportunityFilterPolicy.apply(listOf(rejected),OpportunityFilter.HIGH_POWER).isEmpty())
    }
    @Test fun tiesAreDeterministicBySymbol() {
        val out=OpportunityFilterPolicy.apply(listOf(o("ZZZ",85,"LONG"),o("AAA",85,"LONG")),OpportunityFilter.ALL)
        assertEquals(listOf("AAA","ZZZ"),out.map{it.symbol})
    }

    private fun o(symbol:String,final:Int,direction:String,validity:SignalValidity=SignalValidity.VALID)=Opportunity(
        symbol=symbol,companyName=null,price=10.0,dailyChangePct=0.0,score=50,riskScore=30,direction=direction,
        technicalLabel="",volumeLabel="1.0x",kapLabel="Veri yok",liquidityLabel="",support=null,resistance=null,
        source="unit",dataTimestamp=1L,candles=emptyList(),technical=TechnicalSnapshot(null,null,null,null,null,null,null,null,null,null,null,null,null),
        finalSignalScore=final,dataConfidenceScore=70,dataMode=DataMode.REALTIME,signalValidity=validity
    )
}
