package tr.borsatakip.v5.analysis

import tr.borsatakip.v5.model.Candle
import kotlin.math.max
import kotlin.math.min

/** Analysis-wide OHLCV validation. No indicator may bypass this contract. */
object CandleSeriesValidator {
    data class Result(
        val candles: List<Candle>,
        val rejectedCount: Int,
        val duplicateCount: Int,
        val wasReordered: Boolean
    ) {
        val inputIssueCount: Int get() = rejectedCount + duplicateCount + if (wasReordered) 1 else 0
    }

    fun validate(raw: List<Candle>): Result {
        var rejected = 0
        val valid = raw.filter { c ->
            val finite = listOf(c.open, c.high, c.low, c.close, c.volume).all { it.isFinite() }
            val ok = c.timestamp > 0L && finite &&
                c.open > 0.0 && c.high > 0.0 && c.low > 0.0 && c.close > 0.0 && c.volume >= 0.0 &&
                c.high >= max(c.open, c.close) && c.low <= min(c.open, c.close) && c.high >= c.low
            if (!ok) rejected++
            ok
        }
        val reordered = valid.zipWithNext().any { (a, b) -> a.timestamp > b.timestamp }
        val sorted = valid.sortedBy { it.timestamp }
        val unique = LinkedHashMap<Long, Candle>()
        sorted.forEach { unique[it.timestamp] = it }
        return Result(unique.values.toList(), rejected, sorted.size - unique.size, reordered)
    }
}
