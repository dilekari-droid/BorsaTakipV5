package tr.borsatakip.v5.ui

import tr.borsatakip.v5.model.Opportunity

data class OpportunityFilterState(
    val longEnabled: Boolean = false,
    val shortEnabled: Boolean = false,
    val highPowerEnabled: Boolean = false
) {
    val isActive: Boolean get() = longEnabled || shortEnabled || highPowerEnabled

    fun label(): String = buildList {
        if (longEnabled) add("LONG")
        if (shortEnabled) add("SHORT")
        if (highPowerEnabled) add("85+")
    }.ifEmpty { listOf("Tümü") }.joinToString(" + ")
}

object OpportunityFilter {
    const val HIGH_POWER_THRESHOLD = 85

    fun apply(items: List<Opportunity>, state: OpportunityFilterState): List<Opportunity> {
        val directionOpen = state.longEnabled == state.shortEnabled
        return items.asSequence()
            .filter { item ->
                val direction = item.direction.trim().uppercase()
                directionOpen ||
                    (state.longEnabled && direction == "LONG") ||
                    (state.shortEnabled && direction == "SHORT")
            }
            .filter { item -> !state.highPowerEnabled || item.finalSignalScore >= HIGH_POWER_THRESHOLD }
            .sortedWith(compareByDescending<Opportunity> { it.finalSignalScore }.thenByDescending { it.score })
            .toList()
    }
}
