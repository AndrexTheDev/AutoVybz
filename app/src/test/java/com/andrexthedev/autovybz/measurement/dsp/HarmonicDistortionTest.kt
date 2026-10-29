package com.andrexthedev.autovybz.measurement.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * The harmonic windows are checked against an impulse response in which the *only* energy is where theory
 * puts it: one impulse per order at `peak - T·ln(n)/ln(f2/f1)`. That makes the expected numbers exact
 * (20·log10 of the amplitude ratio), so a wrong lag formula or an off-by-one window cannot hide.
 */
class HarmonicDistortionTest {

    private val fs = 48000
    private val sweepSeconds = 5.0
    private val f1 = 30.0
    private val f2 = 18000.0
    private val n = 1 shl 16
    private val peak = 20000

    private fun lag(order: Int): Int =
        (peak - sweepSeconds * ln(order.toDouble()) / ln(f2 / f1) * fs).roundToInt()

    private fun ir(vararg harmonics: Pair<Int, Double>): DoubleArray {
        val x = DoubleArray(n)
        x[peak] = 1.0
        for ((order, amplitude) in harmonics) x[lag(order)] = amplitude
        return x
    }

    private fun analyse(x: DoubleArray) =
        HarmonicDistortion.analyse(x, peak, sweepSeconds, f1, f2, fs)

    @Test
    fun aLinearSystemHasNoMeasurableHarmonics() {
        // Only the direct sound: every harmonic window sits at the noise floor, so there is no number to
        // report - and "0.0 % THD" would be a claim the recording cannot support.
        assertNull(analyse(ir()))
    }

    @Test
    fun harmonicLevelsAreReadExactlyWhereTheoryPutsThem() {
        val result = analyse(ir(2 to 0.1, 3 to 0.01, 5 to 0.001))
        assertNotNull(result)
        result!!
        assertEquals(-20.0, result.harmonicsDb[2]!!, 0.5)
        assertEquals(-40.0, result.harmonicsDb[3]!!, 0.5)
        assertEquals(-60.0, result.harmonicsDb[5]!!, 0.5)
        // THD = sqrt(0.1² + 0.01² + 0.001²) · 100 = 10.05 %
        assertEquals(10.05, result.thdPercent, 0.1)
        assertEquals(-20.0, result.h3MinusH2Db!!, 0.5)
        assertFalse(result.isRattleLike)
    }

    /** The threshold in [HarmonicDistortion.THD_WARN_PERCENT] is the one the probe calibrated at 3 %. */
    @Test
    fun theThreePercentBoundarySeparatesCleanFromDriven() {
        assertTrue(analyse(ir(3 to 0.02))!!.thdPercent < HarmonicDistortion.THD_WARN_PERCENT)
        assertTrue(analyse(ir(3 to 0.04))!!.thdPercent > HarmonicDistortion.THD_WARN_PERCENT)
    }

    /**
     * A symmetric nonlinearity produces odd orders that fall fast (this is what a driven driver does).
     * A mechanical buzz is impulsive, i.e. broadband: H5 stays close to H3. Only the second case may be
     * called a rattle - the roadmap's original "H3 − H2 > 6 dB" rule would have flagged every soft clip.
     */
    @Test
    fun onlyAFlatHarmonicSeriesCountsAsRattle() {
        val clipped = analyse(ir(3 to 0.05, 5 to 0.005))!!
        assertTrue("H3 ${clipped.harmonicsDb[3]}, H5 ${clipped.harmonicsDb[5]}", clipped.harmonicsDb[5]!! - clipped.harmonicsDb[3]!! < -6.0)
        assertFalse(clipped.isRattleLike)

        val buzzing = analyse(ir(2 to 0.02, 3 to 0.02, 4 to 0.018, 5 to 0.017))!!
        assertTrue(buzzing.isRattleLike)
    }

    @Test
    fun aTinyPeakAboveTheFloorIsNotMeasured() {
        // A floor 10 dB below the direct sound (a very noisy recording): every "harmonic" would be that
        // floor, so returning a THD number here would be inventing one. 12 dB is the documented gate.
        val noisy = ir(2 to 0.5, 3 to 0.5)
        for (i in noisy.indices) if (abs(i - peak) > 4000 && noisy[i] == 0.0) noisy[i] = 0.3
        assertNull(analyse(noisy))
    }

    @Test
    fun theSweepRangeMustBeReal() {
        assertNull(HarmonicDistortion.analyse(ir(), peak, 0.0, f1, f2, fs))
        assertNull(HarmonicDistortion.analyse(ir(), peak, sweepSeconds, f2, f1, fs))
        assertNull(HarmonicDistortion.analyse(DoubleArray(0), 0, sweepSeconds, f1, f2, fs))
    }

    /** Sanity on the physics: the lag of H2 at this sweep is ~0.53 s, i.e. 25 500 samples. */
    @Test
    fun theLagFormulaMatchesFarinasPrediction() {
        val dt = (peak - lag(2)).toDouble() / fs
        assertEquals(sweepSeconds * ln(2.0) / ln(f2 / f1), dt, 1e-4)
        assertTrue("H2 lags the linear response by $dt s", dt > 0.5 && dt < 0.6)
    }
}
