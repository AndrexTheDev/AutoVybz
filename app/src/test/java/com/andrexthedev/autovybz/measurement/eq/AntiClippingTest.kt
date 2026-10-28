package com.andrexthedev.autovybz.measurement.eq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the *measured* limiter configuration (round 8, live probe on real programme material).
 *
 * Equalizer314's DynamicsProcessing stage has no look-ahead, so the attack time decides whether a
 * percussive transient is caught at all. With the old -1 dB / 10:1 / 1 ms setting a corrected party-box
 * preset peaked at **+0.14 dBFS with 13 clipped samples**; -2 dB / 20:1 / 0.2 ms kept every probed case at
 * or below -1.42 dBFS with zero clipped samples. These are safety values, not a sound design choice.
 */
class AntiClippingTest {

    @Test
    fun defaultLimiterIsAPeakProtector() {
        val limiter = AntiClipping.DEFAULT_LIMITER
        assertEquals(-2.0f, limiter.thresholdDb, 0f)
        assertEquals(20f, limiter.ratio, 0f)
        assertEquals(0.2f, limiter.attackMs, 0f)
        assertEquals(60f, limiter.releaseMs, 0f)
        assertEquals(0f, limiter.postGainDb, 0f)
    }

    @Test
    fun limiterSettingsStayInsideTheEngineRanges() {
        // DynamicsProcessingManager clamps these before writing them to the effect; a value outside the
        // range would be silently replaced and the measured behaviour would not be the shipped one.
        val l = AntiClipping.DEFAULT_LIMITER
        assertTrue("threshold ${l.thresholdDb}", l.thresholdDb in -30f..0f)
        assertTrue("ratio ${l.ratio}", l.ratio in 1f..20f)
        assertTrue("attack ${l.attackMs}", l.attackMs in 0.01f..500f)
        assertTrue("release ${l.releaseMs}", l.releaseMs in 1f..5000f)
        assertTrue("post gain ${l.postGainDb}", l.postGainDb in -12f..12f)
    }

    @Test
    fun maxBoostMatchesTheDensestGrid() {
        // A narrow +6 dB peak between two coarse grid points must not slip past the pre-gain.
        val narrow = PeqBand(PeqType.PEAK, 1000.0, 6.0, 4.0)
        assertEquals(6.0, AntiClipping.maxBoostDb(listOf(narrow)), 0.05)
        assertEquals(0.0, AntiClipping.maxBoostDb(emptyList()), 0.0)
    }

    @Test
    fun bassGuardOnlyForRealBassBoosts() {
        val mild = listOf(PeqBand(PeqType.PEAK, 80.0, 2.0, 1.0))
        assertTrue("mild bass boost must not arm the guard", AntiClipping.bassGuardFor(mild) == null)
        val strong = listOf(PeqBand(PeqType.PEAK, 80.0, 5.0, 1.0))
        assertTrue("5 dB bass boost must arm the guard", AntiClipping.bassGuardFor(strong) != null)
    }
}
