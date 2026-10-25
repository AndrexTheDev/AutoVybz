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

    /** Normalised curve with a dip/bump of [gain] dB at [fc] (peaking, [q]). */
    private fun curve(fc: Double, gain: Double, q: Double): DoubleArray {
        val v = DoubleArray(grid.size) { BiquadMath.magnitudeDb(PeqBand(PeqType.PEAK, fc, gain, q), grid[it]) }
        val m = FrequencyGrid.bandMean(grid, v, 300.0, 3000.0)
        return DoubleArray(v.size) { v[it] - m }
    }

    private fun boostAt(r: AutoEqResult, hz: Double) = r.filterResponseDb[grid.indexOfFirst { it >= hz }]

    @Test
    fun snrGatingSkipsNoiseDominatedFrequencies() {
        val dip = curve(5000.0, -8.0, 1.0)
        val goodSnr = DoubleArray(grid.size) { 40.0 }
        val birdSnr = DoubleArray(grid.size) { if (grid[it] in 3000.0..8000.0) 4.0 else 40.0 }
        val clean = AutoEqRenderer.render(grid, dip, TargetCurve(TargetType.FLAT), snrDb = goodSnr)
        val noisy = AutoEqRenderer.render(grid, dip, TargetCurve(TargetType.FLAT), snrDb = birdSnr)
        assertTrue("clean boost ${boostAt(clean, 5000.0)}", boostAt(clean, 5000.0) > 3.0)
        assertTrue("gated boost ${boostAt(noisy, 5000.0)}", abs(boostAt(noisy, 5000.0)) < 1.0)
        assertTrue(clean.noiseGatedRanges.isEmpty())
        val g = noisy.noiseGatedRanges.single()
        assertTrue("range $g", g.first in 2800.0..3300.0 && g.second in 7500.0..8500.0)
    }

    @Test
    fun broadBoostIgnoresNarrowNotchesButKeepsBroadDeficits() {
        val notch = curve(800.0, -10.0, 6.0)      // ground-reflection style notch
        val narrow = AutoEqRenderer.render(grid, notch, TargetCurve(TargetType.FLAT), AutoEqSettings(broadBoostOctaves = 1.0))
        val classic = AutoEqRenderer.render(grid, notch, TargetCurve(TargetType.FLAT))
        assertTrue("classic fills the notch: ${boostAt(classic, 800.0)}", boostAt(classic, 800.0) > 3.0)
        assertTrue("broad mode: ${boostAt(narrow, 800.0)}", boostAt(narrow, 800.0) < 1.5)

        val wide = curve(2500.0, -5.0, 0.7)       // real, broad speaker deficit
        val w = AutoEqRenderer.render(grid, wide, TargetCurve(TargetType.FLAT), AutoEqSettings(broadBoostOctaves = 1.0))
        val wc = AutoEqRenderer.render(grid, wide, TargetCurve(TargetType.FLAT))
        assertTrue("broad deficit still corrected: ${boostAt(w, 2500.0)} vs ${boostAt(wc, 2500.0)}",
            boostAt(w, 2500.0) > 2.0 && boostAt(w, 2500.0) > 0.75 * boostAt(wc, 2500.0))

        val peak = curve(800.0, 8.0, 6.0)         // narrow peaks are still cut
        val p = AutoEqRenderer.render(grid, peak, TargetCurve(TargetType.FLAT), AutoEqSettings(broadBoostOctaves = 1.0))
        assertTrue("narrow peak cut: ${boostAt(p, 800.0)}", boostAt(p, 800.0) < -4.0)
    }

    /** Normalised curve from a set of colouring bands (like [measured]). */
    private fun curveOf(vararg bands: PeqBand): DoubleArray {
        val list = bands.toList()
        val v = DoubleArray(grid.size) { BiquadMath.totalDb(list, grid[it]) }
        val m = FrequencyGrid.bandMean(grid, v, 300.0, 3000.0)
        return DoubleArray(v.size) { v[it] - m }
    }

    @Test
    fun lowLimitIgnoresIsolatedDipAboveRollOff() {
        val c = curveOf(
            PeqBand(PeqType.HIGH_PASS, 55.0, 0.0, 0.707), // the real bass roll-off
            PeqBand(PeqType.PEAK, 140.0, -9.0, 2.0),      // a room dip above it
        )
        val lo = AutoEqRenderer.detectLowLimit(grid, c)
        // Old scan-down logic stopped at the dip (~160 Hz clamp); the contiguous run finds ~52 Hz.
        assertTrue("low limit $lo", lo in 35.0..90.0)
    }

    @Test
    fun lowLimitWideScanFindsPhoneSpeakerRollOff() {
        val phone = curveOf(PeqBand(PeqType.HIGH_PASS, 700.0, 0.0, 0.707))
        // Default (external-speaker) scan saturates at the 160 Hz clamp...
        assertEquals(160.0, AutoEqRenderer.detectLowLimit(grid, phone), 1e-9)
        // ...the wide device-speaker scan finds the real roll-off.
        val lo = AutoEqRenderer.detectLowLimit(grid, phone, scanFromHz = 1200.0, maxHz = 900.0)
        assertTrue("phone low limit $lo", lo in 450.0..800.0)
    }

    @Test
    fun protectiveHighPassProtectsCheapSpeaker() {
        val cheap = curveOf(
            PeqBand(PeqType.HIGH_PASS, 160.0, 0.0, 0.707),   // no real bass below ~160 Hz
            PeqBand(PeqType.PEAK, 300.0, 7.0, 1.0),          // boom
            PeqBand(PeqType.PEAK, 3200.0, 6.0, 1.5),         // shout
            PeqBand(PeqType.HIGH_SHELF, 7000.0, -8.0, 0.7),  // dull top end
        )
        val settings = AutoEqSettings(
            bandCount = 10, protectiveHighPass = true, lowLimitScanHz = 400.0, lowLimitMaxHz = 350.0,
        )
        val r = AutoEqRenderer.render(grid, cheap, TargetCurve(TargetType.HARMAN), settings)
        val hp = r.bands.filter { it.type == PeqType.HIGH_PASS }
        assertEquals(1, hp.size)
        assertTrue("bands ${r.bands.size}", r.bands.size <= 10)
        assertEquals(hp[0].frequency, r.highPassHz!!, 1e-9)
        assertEquals(r.lowLimitHz / AutoEqRenderer.LOW_LIMIT_SAFETY, hp[0].frequency, 1e-6)
        assertTrue("fc ${hp[0].frequency}", hp[0].frequency in 80.0..200.0)
        val half = grid.indexOfFirst { it >= hp[0].frequency / 2 }
        assertTrue("one octave below fc: ${r.filterResponseDb[half]} dB", r.filterResponseDb[half] < -7.0)
        assertTrue("rms ${r.rmsErrorBeforeDb} -> ${r.rmsErrorAfterDb}", r.rmsErrorAfterDb < r.rmsErrorBeforeDb * 0.5)
    }

    @Test
    fun protectiveHighPassSkippedForFullRangeSpeaker() {
        val good = curveOf(
            PeqBand(PeqType.HIGH_PASS, 25.0, 0.0, 0.707),
            PeqBand(PeqType.PEAK, 2500.0, -4.0, 1.5),
        )
        val r = AutoEqRenderer.render(grid, good, TargetCurve(TargetType.FLAT), AutoEqSettings(protectiveHighPass = true))
        assertTrue(r.bands.none { it.type == PeqType.HIGH_PASS })
        assertEquals(null, r.highPassHz)
    }

    @Test
    fun phoneSpeakerRenderIsBandLimitedAndProtected() {
        val phone = curveOf(
            PeqBand(PeqType.HIGH_PASS, 700.0, 0.0, 0.707),
            PeqBand(PeqType.PEAK, 3000.0, 5.0, 1.5), // typical small-speaker shout
        )
        val settings = AutoEqSettings(
            maxBoostDb = 4.0, highLimitHz = 12000.0, protectiveHighPass = true,
            lowLimitScanHz = 1200.0, lowLimitMaxHz = 900.0,
            broadBoostOctaves = 1.0, excursionGuardOctaves = 2.0,
        )
        val r = AutoEqRenderer.render(grid, phone, TargetCurve(TargetType.HARMAN), settings)
        assertTrue("low limit ${r.lowLimitHz}", r.lowLimitHz in 400.0..900.0)
        assertTrue("hp ${r.highPassHz}", r.highPassHz != null && r.highPassHz in 300.0..750.0)
        assertTrue("max boost ${r.maxFilterBoostDb}", r.maxFilterBoostDb <= 4.0 + 0.5)
        r.bands.forEach { assertTrue("band above correction limit $it", it.frequency <= 12000.0 + 1e-9) }
        val deep = grid.indexOfFirst { it >= 100.0 }
        assertTrue("100 Hz is cut hard: ${r.filterResponseDb[deep]} dB", r.filterResponseDb[deep] < -15.0)
    }

    @Test
    fun widerExcursionGuardRampsSlower() {
        assertEquals(0.5, AutoEqRenderer.excursionGuard(2 * 50.0, 50.0, 2.0), 1e-12)
        assertEquals(1.0, AutoEqRenderer.excursionGuard(2 * 50.0, 50.0, 1.0), 1e-12)
        val flat = DoubleArray(grid.size)
        assertTrue(AutoEqRenderer.medianLog(grid, flat, 1.0).all { it == 0.0 })
        // a 1/8-octave notch does not move a 1-octave median at all
        val notch = DoubleArray(grid.size) { if (grid[it] in 950.0..1050.0) -12.0 else 0.0 }
        assertEquals(0.0, AutoEqRenderer.medianLog(grid, notch, 1.0)[grid.indexOfFirst { it >= 1000.0 }], 1e-12)
    }
}
