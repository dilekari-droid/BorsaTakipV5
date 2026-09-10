package tr.borsatakip.v5.analysis

import tr.borsatakip.v5.model.Opportunity
import tr.borsatakip.v5.model.SignalValidity

enum class OpportunityFilter { ALL, LONG, SHORT, HIGH_POWER }

/** Pure local policy: no provider/network dependency. */
object OpportunityFilterPolicy {
    fun apply(items: List<Opportunity>, filter: OpportunityFilter): List<Opportunity> {
        val sorted = items.sortedWith(
            compareByDescending<Opportunity> { it.finalSignalScore }
                .thenBy { it.symbol }
        )
        fun publishable(x: Opportunity) =
            x.signalValidity == SignalValidity.VALID || x.signalValidity == SignalValidity.WATCH
        return when (filter) {
            OpportunityFilter.ALL -> sorted
            OpportunityFilter.LONG -> sorted.filter { publishable(it) && it.direction.equals("LONG", true) }
            OpportunityFilter.SHORT -> sorted.filter { publishable(it) && it.direction.equals("SHORT", true) }
            OpportunityFilter.HIGH_POWER -> sorted.filter { publishable(it) && it.finalSignalScore >= 85 }
        }
    }
}
