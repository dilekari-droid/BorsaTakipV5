package tr.borsatakip.v5.ui.chart

import tr.borsatakip.v5.analysis.CandleSeriesValidator
import tr.borsatakip.v5.model.Candle

/** Pure calculations. Every output is derived from the same validated OHLCV series. */
object ChartMath {
    data class MacdSeries(
        val macd: List<Double?>,
        val signal: List<Double?>,
        val histogram: List<Double?>
    )

    data class ValidationResult(
        val candles: List<Candle>,
        val rejectedCount: Int,
        val duplicateCount: Int
    )

    fun validate(raw: List<Candle>): ValidationResult {
        val result = CandleSeriesValidator.validate(raw)
        return ValidationResult(
            candles = result.candles,
            rejectedCount = result.rejectedCount,
            duplicateCount = result.duplicateCount
        )
    }

    fun ema(values: List<Double>, period: Int): List<Double?> {
        require(period > 0)
        if (values.isEmpty()) return emptyList()
        val out = MutableList<Double?>(values.size) { null }
        if (values.size < period) return out
        val seed = values.take(period).average()
        out[period - 1] = seed
        val alpha = 2.0 / (period + 1.0)
        var previous = seed
        for (i in period until values.size) {
            previous = values[i] * alpha + previous * (1.0 - alpha)
            out[i] = previous
        }
        return out
    }

    fun rsi(values: List<Double>, period: Int = 14): List<Double?> {
        require(period > 0)
        val out = MutableList<Double?>(values.size) { null }
        if (values.size <= period) return out

        var gain = 0.0
        var loss = 0.0
        for (i in 1..period) {
            val d = values[i] - values[i - 1]
            if (d >= 0) gain += d else loss -= d
        }
        var avgGain = gain / period
        var avgLoss = loss / period
        out[period] = rsiValue(avgGain, avgLoss)

        for (i in period + 1 until values.size) {
            val d = values[i] - values[i - 1]
            val g = if (d > 0) d else 0.0
            val l = if (d < 0) -d else 0.0
            avgGain = ((avgGain * (period - 1)) + g) / period
            avgLoss = ((avgLoss * (period - 1)) + l) / period
            out[i] = rsiValue(avgGain, avgLoss)
        }
        return out
    }

    private fun rsiValue(avgGain: Double, avgLoss: Double): Double = when {
        avgLoss == 0.0 && avgGain == 0.0 -> 50.0
        avgLoss == 0.0 -> 100.0
        else -> 100.0 - (100.0 / (1.0 + avgGain / avgLoss))
    }

    fun macd(values: List<Double>): MacdSeries {
        val fast = ema(values, 12)
        val slow = ema(values, 26)
        val macd = MutableList<Double?>(values.size) { null }
        for (i in values.indices) {
            val f = fast.getOrNull(i)
            val s = slow.getOrNull(i)
            if (f != null && s != null) macd[i] = f - s
        }

        val defined = macd.mapIndexedNotNull { index, value -> value?.let { index to it } }
        val signal = MutableList<Double?>(values.size) { null }
        if (defined.size >= 9) {
            val signalCompact = ema(defined.map { it.second }, 9)
            defined.forEachIndexed { compactIndex, pair -> signal[pair.first] = signalCompact[compactIndex] }
        }
        val hist = MutableList<Double?>(values.size) { null }
        for (i in values.indices) {
            val m = macd[i]
            val s = signal[i]
            if (m != null && s != null) hist[i] = m - s
        }
        return MacdSeries(macd, signal, hist)
    }
}
