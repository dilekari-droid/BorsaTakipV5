package tr.borsatakip.v5.ui.chart

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Pure, cache-friendly Linear Regression Channel math over Close prices. */
object LinearRegressionChannelCalculator {
    enum class Trend { UP, DOWN, FLAT }

    data class Result(
        val length: Int,
        val startIndex: Int,
        val endIndex: Int,
        val intercept: Double,
        val slope: Double,
        val sigma: Double,
        val pearsonR: Double,
        val trend: Trend,
        val lastClose: Double,
        val lastRegression: Double,
        val distanceToMid: Double,
        val channelWidth2Sigma: Double
    ) {
        fun regressionAt(globalIndex: Int): Double {
            val x = (globalIndex - startIndex).toDouble()
            return intercept + slope * x
        }

        fun upperAt(globalIndex: Int, sigmaMultiplier: Double): Double =
            regressionAt(globalIndex) + sigma * sigmaMultiplier

        fun lowerAt(globalIndex: Int, sigmaMultiplier: Double): Double =
            regressionAt(globalIndex) - sigma * sigmaMultiplier
    }

    fun calculate(values: List<Double>, length: Int, endIndex: Int = values.lastIndex): Result? {
        if (length < 2 || endIndex !in values.indices) return null
        val startIndex = endIndex - length + 1
        if (startIndex < 0) return null
        val window = values.subList(startIndex, endIndex + 1)
        if (window.size != length || window.any { !it.isFinite() }) return null

        val n = length.toDouble()
        val meanX = (length - 1) / 2.0
        val meanY = window.average()
        if (!meanY.isFinite()) return null

        var sxx = 0.0
        var sxy = 0.0
        var syy = 0.0
        for (i in window.indices) {
            val dx = i - meanX
            val dy = window[i] - meanY
            sxx += dx * dx
            sxy += dx * dy
            syy += dy * dy
        }
        if (sxx <= 0.0 || !sxx.isFinite()) return null

        val slope = sxy / sxx
        val intercept = meanY - slope * meanX
        if (!slope.isFinite() || !intercept.isFinite()) return null

        var residualSq = 0.0
        for (i in window.indices) {
            val regression = intercept + slope * i
            val residual = window[i] - regression
            residualSq += residual * residual
        }
        // Two fitted parameters (intercept and slope): use n-2 for residual error estimate.
        val sigma = sqrt(max(0.0, residualSq / (n - 2.0)))
        if (!sigma.isFinite()) return null

        val pearson = if (syy <= 0.0) 0.0 else (sxy / sqrt(sxx * syy)).coerceIn(-1.0, 1.0)
        if (!pearson.isFinite()) return null

        val slopeEpsilon = max(abs(meanY) * 1e-6, 1e-9)
        val trend = when {
            slope > slopeEpsilon -> Trend.UP
            slope < -slopeEpsilon -> Trend.DOWN
            else -> Trend.FLAT
        }
        val lastRegression = intercept + slope * (length - 1)
        val lastClose = window.last()
        return Result(
            length = length,
            startIndex = startIndex,
            endIndex = endIndex,
            intercept = intercept,
            slope = slope,
            sigma = sigma,
            pearsonR = pearson,
            trend = trend,
            lastClose = lastClose,
            lastRegression = lastRegression,
            distanceToMid = lastClose - lastRegression,
            channelWidth2Sigma = 4.0 * sigma
        )
    }

    /** Precomputes one window-ending snapshot per candle; onDraw performs no regression math. */
    fun rolling(values: List<Double>, length: Int): List<Result?> {
        if (values.isEmpty()) return emptyList()
        val out = MutableList<Result?>(values.size) { null }
        if (length < 2 || values.size < length) return out
        for (end in length - 1 until values.size) out[end] = calculate(values, length, end)
        return out
    }
}
