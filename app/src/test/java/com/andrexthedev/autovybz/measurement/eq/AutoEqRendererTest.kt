package com.andrexthedev.autovybz.measurement.eq

import com.andrexthedev.autovybz.measurement.dsp.FrequencyGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AutoEqRendererTest {

    private val grid = FrequencyGrid.log(pointsPerOctave = 48)

    /** Typical small Bluetooth speaker in a room. */
    private val measured: DoubleArray = run {
        val colour = listOf(
            PeqBand(PeqType.PEAK, 150.0, 6.0, 1.2),
            PeqBand(PeqType.PEAK, 2500.0, -5.0, 1.5),
            PeqBand(PeqType.HIGH_SHELF, 10000.0, -4.0, 0.7),
            PeqBand(PeqType.LOW_SHELF, 60.0, -18.0, 0.7), // bass roll-off
        )
        val v = DoubleArray(grid.size) { BiquadMath.totalDb(colour, grid[it]) }
        val m = FrequencyGrid.bandMean(grid, v, 300.0, 3000.0)
        DoubleArray(v.size) { v[it] - m }
    }

    @Test
    fun flatTargetIsApproximatedWithinLimits() {
        val settings = AutoEqSettings(bandCount = 10)
        val r = AutoEqRenderer.render(grid, measured, TargetCurve(TargetType.FLAT), settings)
        assertTrue("bands ${r.bands.size}", r.bands.size in 1..10)
        assertTrue("rms ${r.rmsErrorBeforeDb} -> ${r.rmsErrorAfterDb}", r.rmsErrorAfterDb < r.rmsErrorBeforeDb * 0.35)
        assertTrue("max boost ${r.maxFilterBoostDb}", r.maxFilterBoostDb <= settings.maxBoostDb + 0.5)
        r.bands.forEach { assertTrue("band boost $it", it.gainDb <= settings.maxBoostDb + 1e-9) }
        // Anti-clipping: pre-gain compensates the max boost plus headroom.
        assertTrue("preamp ${r.preampDb}", r.preampDb <= -(r.maxFilterBoostDb + settings.headroomDb) + 1e-9)
    }

    @Test
    fun neverBoostsBelowSpeakerRollOff() {
        val r = AutoEqRenderer.render(grid, measured, TargetCurve(TargetType.BASS_BOOST))
        assertTrue("low limit ${r.lowLimitHz}", r.lowLimitHz in 40.0..120.0)
        val i30 = grid.indexOfFirst { it >= 30.0 }
        assertTrue("30 Hz boost ${r.filterResponseDb[i30]}", r.filterResponseDb[i30] < 4.0)
    }

    @Test
    fun fiveBandModeUsesAtMostFiveBands() {
        val r5 = AutoEqRenderer.render(grid, measured, TargetCurve(TargetType.SMART), AutoEqSettings(bandCount = 5))
        val r10 = AutoEqRenderer.render(grid, measured, TargetCurve(TargetType.SMART), AutoEqSettings(bandCount = 10))
        assertTrue(r5.bands.size <= 5)
        assertTrue("10 bands not worse: ${r10.rmsErrorAfterDb} vs ${r5.rmsErrorAfterDb}", r10.rmsErrorAfterDb <= r5.rmsErrorAfterDb + 0.05)
    }

    @Test
    fun alreadyFlatSpeakerNeedsNoEq() {
        val flat = DoubleArray(grid.size)
        val r = AutoEqRenderer.render(grid, flat, TargetCurve(TargetType.FLAT))
        assertTrue("bands ${r.bands}", r.bands.isEmpty())
        assertEquals(0.0, r.preampDb, 0.0)
    }

    @Test
    fun targetsAreNormalisedAndShaped() {
        for (t in TargetType.values()) {
            val s = TargetCurve(t).sample(grid)
            assertEquals("$t mid mean", 0.0, FrequencyGrid.bandMean(grid, s, 300.0, 3000.0), 1e-9)
        }
        val smart = TargetCurve(TargetType.SMART).sample(grid)
        val harman = TargetCurve(TargetType.HARMAN).sample(grid)
        val i40 = grid.indexOfFirst { it >= 40.0 }
        val i10k = grid.indexOfFirst { it >= 10000.0 }
        assertTrue(smart[i40] > 2.5 && abs(smart[i10k]) < 0.5)
        assertTrue(harman[i40] > smart[i40] && harman[i10k] < -1.5)
    }

    @Test
    fun excursionGuardLimitsBoostNearRollOff() {
        val r = AutoEqRenderer.render(grid, measured, TargetCurve(TargetType.BASS_BOOST))
        val lo = r.lowLimitHz
        for (i in grid.indices) if (grid[i] < 2 * lo) {
            val cap = 6.0 * AutoEqRenderer.excursionGuard(grid[i], lo)
            assertTrue("desired ${r.desiredCorrectionDb[i]} @ ${grid[i]}", r.desiredCorrectionDb[i] <= cap + 1e-9)
        }
        assertEquals(0.0, AutoEqRenderer.excursionGuard(lo, lo), 1e-12)
        assertEquals(1.0, AutoEqRenderer.excursionGuard(2 * lo, lo), 1e-12)
    }

    @Test
    fun rangeWeightFadesOverHalfAnOctave() {
        assertEquals(1.0, AutoEqRenderer.rangeWeight(1000.0, 50.0, 16000.0), 1e-12)
        assertEquals(0.0, AutoEqRenderer.rangeWeight(30.0, 50.0, 16000.0), 1e-12)
        val mid = AutoEqRenderer.rangeWeight(50.0 / Math.pow(2.0, 0.25), 50.0, 16000.0)
        assertEquals(0.5, mid, 1e-9)
    }
}
