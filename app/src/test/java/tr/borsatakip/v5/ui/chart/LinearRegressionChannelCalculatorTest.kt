package tr.borsatakip.v5.ui.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class LinearRegressionChannelCalculatorTest {
    @Test fun flatPrices_haveNearZeroSlopeAndZeroR() {
        val r = LinearRegressionChannelCalculator.calculate(List(100) { 10.0 }, 100)!!
        assertTrue(abs(r.slope) < 1e-12)
        assertEquals(0.0, r.pearsonR, 1e-12)
        assertEquals(LinearRegressionChannelCalculator.Trend.FLAT, r.trend)
    }

    @Test fun risingPrices_havePositiveSlopeAndR() {
        val r = LinearRegressionChannelCalculator.calculate(List(100) { 100.0 + it * 0.5 }, 100)!!
        assertTrue(r.slope > 0.0)
        assertTrue(r.pearsonR > 0.999)
        assertEquals(LinearRegressionChannelCalculator.Trend.UP, r.trend)
    }

    @Test fun fallingPrices_haveNegativeSlopeAndR() {
        val r = LinearRegressionChannelCalculator.calculate(List(100) { 200.0 - it * 0.25 }, 100)!!
        assertTrue(r.slope < 0.0)
        assertTrue(r.pearsonR < -0.999)
        assertEquals(LinearRegressionChannelCalculator.Trend.DOWN, r.trend)
    }

    @Test fun insufficientData_returnsNull() {
        assertNull(LinearRegressionChannelCalculator.calculate(List(99) { it.toDouble() + 1.0 }, 100))
    }

    @Test fun identicalPrices_doNotCrashOrProduceNan() {
        val r = LinearRegressionChannelCalculator.calculate(List(100) { 42.0 }, 100)
        assertNotNull(r)
        assertTrue(r!!.sigma.isFinite())
        assertTrue(r.pearsonR.isFinite())
    }

    @Test fun nanOrInfinity_returnsNull() {
        val nan = MutableList(100) { it.toDouble() + 1.0 }.also { it[50] = Double.NaN }
        val inf = MutableList(100) { it.toDouble() + 1.0 }.also { it[50] = Double.POSITIVE_INFINITY }
        assertNull(LinearRegressionChannelCalculator.calculate(nan, 100))
        assertNull(LinearRegressionChannelCalculator.calculate(inf, 100))
    }

    @Test fun manualLinearDataset_matchesExpectedRegression() {
        val values = List(100) { i -> 12.0 + 1.75 * i }
        val r = LinearRegressionChannelCalculator.calculate(values, 100)!!
        assertEquals(12.0, r.intercept, 1e-9)
        assertEquals(1.75, r.slope, 1e-9)
        assertEquals(185.25, r.lastRegression, 1e-9)
        assertTrue(r.sigma < 1e-9)
    }

    @Test fun residualSigma_usesTwoParameterDegreesOfFreedom() {
        val values = listOf(1.0, 2.0, 4.0, 4.0, 5.0)
        val r = LinearRegressionChannelCalculator.calculate(values, values.size)!!
        val residualSum = values.indices.sumOf { i ->
            val d = values[i] - r.regressionAt(i)
            d * d
        }
        assertEquals(kotlin.math.sqrt(residualSum / (values.size - 2)), r.sigma, 1e-12)
    }
}
