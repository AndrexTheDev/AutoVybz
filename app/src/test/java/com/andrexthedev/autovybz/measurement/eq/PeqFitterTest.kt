package com.andrexthedev.autovybz.measurement.eq

import com.andrexthedev.autovybz.measurement.dsp.FrequencyGrid
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * The band fitter must deliver the *plan* it is given, not just "something plausible".
 *
 * Round 8 measured the opposite on real material: the greedy acceptance threshold was frozen at 1.5 % of
 * the **initial** loss (0.096 dB^2 for the mini box), so it stopped after 4 of 10 bands while the next
 * band still bought 0.060 dB^2 - a broad +2.3 dB lift at ~590 Hz stayed un-fitted and the probe's
 * fidelity check (max |desired - realised|) failed at 2.33 dB. The tests below pin the two changes that
 * fixed it: the threshold follows the *remaining* error, and the optimiser is offered several residual
 * extrema instead of only the worst one.
 */
class PeqFitterTest {

    private val grid = FrequencyGrid.log(pointsPerOctave = 48)
    private val biquad = BiquadMath.Grid(grid)
    private val unitWeights = DoubleArray(grid.size) { 1.0 }

    /** The desired-correction shape measured on the mini box, plus the broad lift it left behind. */
    private fun miniBoxShape(): DoubleArray {
        val lobes = listOf(
            PeqBand(PeqType.PEAK, 125.0, 5.4, 2.7),
            PeqBand(PeqType.PEAK, 354.0, 3.1, 4.0),
            PeqBand(PeqType.PEAK, 1577.0, -3.2, 2.6),
            PeqBand(PeqType.PEAK, 14600.0, 3.0, 0.5),
        )
        val base = biquad.response(lobes)
        return DoubleArray(grid.size) { i ->
            val o = ln(grid[i] / 600.0) / ln(2.0) / 0.6
            base[i] + 2.5 * exp(-o * o)
        }
    }

    private fun fit(desired: DoubleArray, constraints: FitConstraints = FitConstraints(maxBands = 10)): List<PeqBand> =
        PeqFitter(biquad, desired, unitWeights, constraints).fit()

    private fun maxError(desired: DoubleArray, bands: List<PeqBand>, lo: Double, hi: Double): Double {
        val realised = biquad.response(bands)
        var maxError = 0.0
        for (i in grid.indices) {
            if (grid[i] < lo || grid[i] > hi) continue
            maxError = maxOf(maxError, abs(desired[i] - realised[i]))
        }
        return maxError
    }

    @Test
    fun broadResidualIsActuallyFittedNotJustApproached() {
        val desired = miniBoxShape()
        val bands = fit(
            desired,
            FitConstraints(maxBands = 10, minFrequency = 30.0, minQ = 0.4, maxQ = 4.0, allowShelves = true),
        )
        // Before the fix the fitter stopped with 2.33 dB un-fitted while six bands were still free.
        val error = maxError(desired, bands, 100.0, 14_000.0)
        assertTrue("max |desired - realised| $error dB with $bands", error < 1.0)
        assertTrue("bands ${bands.size}", bands.size in 4..10)
    }

    @Test
    fun flatDesiredNeedsNoBands() {
        assertTrue(fit(DoubleArray(grid.size) { 0.0 }).isEmpty())
    }

    @Test
    fun fiveBandModeIsStillARealFit() {
        val desired = miniBoxShape()
        val bands = fit(desired, FitConstraints(maxBands = 5, minFrequency = 30.0, allowShelves = true))
        assertTrue("bands ${bands.size}", bands.size <= 5)
        // Fewer biquads are allowed to cost accuracy - but not to degenerate into a stub.
        val error = maxError(desired, bands, 200.0, 8_000.0)
        assertTrue("5-band max error $error dB", error < 2.0)
    }

    @Test
    fun twoSeparatedLobesAreBothFitted() {
        // With a single candidate per step the greedy can spend its budget on one lobe and leave the
        // other 3 dB short; the multi-candidate version has to see both.
        val desired = DoubleArray(grid.size) { i ->
            val a = ln(grid[i] / 400.0) / ln(2.0) / 0.5
            val b = ln(grid[i] / 3000.0) / ln(2.0) / 0.5
            4.0 * exp(-a * a) + 3.0 * exp(-b * b)
        }
        val bands = fit(desired, FitConstraints(maxBands = 10, minFrequency = 30.0))
        val error = maxError(desired, bands, 200.0, 6_000.0)
        assertTrue("max error $error dB with $bands", error < 1.0)
    }

    @Test
    fun staysNearTheExcursionBoundary() {
        // `noBoostBelowHz` is a soft penalty (it has to trade against the rest of the fit), but the
        // combined response must not follow a requested +6 dB all the way down past the boundary.
        val desired = DoubleArray(grid.size) { i -> if (grid[i] < 200.0) 6.0 else 0.0 }
        val bands = fit(desired, FitConstraints(maxBands = 10, minFrequency = 30.0, noBoostBelowHz = 40.0))
        val realised = biquad.response(bands)
        var worst = -100.0
        for (i in grid.indices) if (grid[i] < 40.0) worst = maxOf(worst, realised[i])
        assertTrue("boost $worst dB below 40 Hz with $bands", worst < 3.0)
    }
}
