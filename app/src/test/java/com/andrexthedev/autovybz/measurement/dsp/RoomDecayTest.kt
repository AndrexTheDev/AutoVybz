package com.andrexthedev.autovybz.measurement.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.random.Random

/**
 * Reverberation time from a synthetic response whose decay is known exactly: noise shaped by
 * `exp(-6.9078·t/T60)` after the direct sound. The test asserts what the code claims - accuracy within a
 * few percent on a clean response, and **null** (not a number) whenever the recording cannot answer.
 */
class RoomDecayTest {

    private val fs = 48000

    /** Direct sound at [peak] plus a diffuse tail with a known [rt60]. */
    private fun response(rt60: Double, peak: Int = 4800, tailLevel: Double = 0.2, noise: Double = 0.0,
                         seconds: Double = 3.0): DoubleArray {
        val n = (seconds * fs).toInt()
        val rng = Random(0x5EED)
        val x = DoubleArray(n)
        x[peak] = 1.0
        val tailEnergy = DoubleArray(n)
        for (i in peak until n) {
            val t = (i - peak).toDouble() / fs
            tailEnergy[i] = rng.nextDouble(-1.0, 1.0) * exp(-6.9078 * t / rt60)
        }
        // Normalise the tail to `tailLevel` of the direct sound (RMS).
        var sum = 0.0
        for (i in peak until n) sum += tailEnergy[i] * tailEnergy[i]
        val rms = kotlin.math.sqrt(sum / (n - peak))
        val scale = if (rms > 0.0) tailLevel / rms else 0.0
        for (i in peak until n) x[i] += tailEnergy[i] * scale
        if (noise > 0.0) for (i in 0 until n) x[i] += rng.nextDouble(-1.0, 1.0) * noise
        return x
    }

    @Test
    fun aKnownDecayIsRecoveredWithinAFewPercent() {
        for (rt60 in doubleArrayOf(0.4, 0.6, 0.9, 1.2)) {
            val estimate = RoomDecay.estimate(response(rt60), 4800, fs)
            assertNotNull("$rt60 s: no estimate", estimate)
            assertEquals("rt60 $rt60", rt60, estimate!!, rt60 * 0.12)
        }
    }

    @Test
    fun aDryRoomIsNotInvented() {
        // Direct sound only: there is no decay to measure. The naive Schroeder estimate (no direct-sound
        // skip, threshold crossing instead of a fit) reported ~0.5 s here - a room that does not exist.
        val dry = DoubleArray(3 * fs).also { it[4800] = 1.0 }
        assertNull(RoomDecay.estimate(dry, 4800, fs))
    }

    @Test
    fun aNoiseFloorThatSwallowsTheDecayYieldsUnknown() {
        // A floor only ~30 dB below the direct sound ends the decay inside the fit region. Extrapolating
        // that plateau is how a dry car interior becomes a 5 s hall (the probe's traffic model reported
        // 5.3-5.5 s for a 0.9 s room at 20 dB SNR); "unknown" is the only honest answer.
        val noisy = response(0.9, noise = 0.03)
        assertNull(RoomDecay.estimate(noisy, 4800, fs))
        // ... while a floor 50 dB down still leaves the decay intact: the rule is about the plateau, not
        // about the noise as such.
        assertNotNull(RoomDecay.estimate(response(0.9, noise = 0.002), 4800, fs))
    }

    @Test
    fun theDirectSoundIsNotCountedAsRoomDecay() {
        // Same tail, but a much louder direct sound must not change the answer: if the direct sound were
        // inside the integration, a louder one would shorten the apparent decay.
        val quiet = RoomDecay.estimate(response(0.9, tailLevel = 0.1), 4800, fs)
        val loud = RoomDecay.estimate(response(0.9, tailLevel = 0.4), 4800, fs)
        assertNotNull(quiet)
        assertNotNull(loud)
        assertEquals(quiet!!, loud!!, 0.1)
        assertEquals(0.9, quiet, 0.12)
    }

    @Test
    fun theBoostGateIsAMonotoneTrustFactor() {
        assertEquals(1.0, RoomDecay.boostTrust(null), 0.0)
        assertEquals(1.0, RoomDecay.boostTrust(0.0), 0.0)
        // 1/(1+T60): a 1 s room keeps half of each boost above its transition frequency.
        assertEquals(0.5, RoomDecay.boostTrust(1.0), 1e-9)
        assertTrue(RoomDecay.boostTrust(1.5) < RoomDecay.boostTrust(0.5))
    }

    @Test
    fun theTransitionFrequencyFollowsSchroederAndStaysInRange() {
        // 2000·sqrt(T60/V) for an assumed 40 m3 room: 0.9 s -> ~300 Hz.
        assertEquals(300.0, RoomDecay.transitionHz(0.9), 10.0)
        assertTrue(RoomDecay.transitionHz(0.9) > RoomDecay.transitionHz(0.4))
        // Clamped: a tiny volume or an absurd T60 must not push the gate into the treble.
        assertTrue(RoomDecay.transitionHz(30.0) <= 600.0)
        assertTrue(RoomDecay.transitionHz(0.001) >= 150.0)
        assertTrue(RoomDecay.transitionHz(0.0).isInfinite())
    }

    @Test
    fun anEmptyOrTooShortResponseIsRejected() {
        assertNull(RoomDecay.estimate(DoubleArray(0), 0, fs))
        assertNull(RoomDecay.estimate(DoubleArray(100), 0, fs))
    }
}
