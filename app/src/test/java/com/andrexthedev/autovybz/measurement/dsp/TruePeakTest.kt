package com.andrexthedev.autovybz.measurement.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

/**
 * [TruePeak] against analytically known cases. The expected numbers were produced with an FFT-exact
 * band-limited reconstruction of the same signals (see `tools/probe/pipeline_findings.py`, finding 2),
 * so this test pins the interpolator to physics, not to its own implementation.
 */
class TruePeakTest {

    private fun tone(frequency: Double, samples: Int = 8192, amplitude: Double = 1.0, phase: Double = 0.0) =
        DoubleArray(samples) { i -> amplitude * cos(2.0 * PI * frequency * i / 48000.0 + phase) }

    @Test
    fun aConstantKeepsItsLevel() {
        val dc = DoubleArray(4096) { 0.5 }
        // Unity DC gain per phase: a constant must not gain or lose anything between the samples.
        assertEquals(-6.02, TruePeak.peakDbtp(dc), 0.01)
        assertEquals(-6.02, TruePeak.samplePeakDbfs(dc), 0.01)
        assertEquals(0.0, TruePeak.interSampleMarginDb(dc), 1e-9)
    }

    @Test
    fun aSlowToneReachesItsTrueAmplitude() {
        // 1 kHz, 48 kHz: the samples miss the crest by up to 22.5 degrees every period, so the sample
        // peak is at most 0.98 (-0.18 dB) while the reconstructed waveform reaches exactly 0 dBFS.
        val peak = TruePeak.peakDbtp(tone(1000.0))
        assertEquals("true peak of a full-scale 1 kHz tone", 0.0, peak, 0.02)
    }

    @Test
    fun theInterSampleMarginIsTheDifferenceThatMatters() {
        // fs/4 at 45 degrees phase: every sample sits at +-0.7071 (-3.01 dBFS), the reconstructed
        // waveform between them reaches ~0 dBTP. This is the case a sample-peak meter cannot see.
        val x = tone(12000.0, 8192, 1.0, PI / 4.0)
        assertEquals(-3.01, TruePeak.samplePeakDbfs(x), 0.01)
        assertEquals(-0.17, TruePeak.peakDbtp(x), 0.05)
        // 4x oversampling under-reads a crest near fs/4 by up to 1 - cos(pi/8) = 0.17 dB; document it
        // instead of hiding it, because that is the residual this metric cannot resolve.
        assertEquals(2.84, TruePeak.interSampleMarginDb(x), 0.05)
    }

    @Test
    fun theOversampledPeakIsNeverBelowTheSamplePeakInBand() {
        for (frequency in intArrayOf(100, 1000, 5000, 10000, 15000)) {
            val x = tone(frequency.toDouble(), 8192, 0.9, 0.37)
            val truePeak = TruePeak.peakDbtp(x)
            val samplePeak = TruePeak.samplePeakDbfs(x)
            assertTrue("$frequency Hz: $truePeak < $samplePeak",
                truePeak >= samplePeak - 0.02)
        }
    }

    @Test
    fun contentOutsideTheAudioBandIsAttenuatedNotInvented() {
        // Sample-alternating (+1/-1) is fs/2: no interpolator passes it, and it must never read as an
        // overshoot (that would make the pre-gain needlessly conservative).
        val alternating = DoubleArray(2048) { if (it % 2 == 0) 1.0 else -1.0 }
        val truePeak = TruePeak.peakDbtp(alternating)
        assertTrue("fs/2 reads $truePeak dBTP", truePeak < 0.0 && truePeak > -1.5)
    }

    @Test
    fun silenceAndTinyBuffersAreHandled() {
        assertTrue(TruePeak.peakDbtp(DoubleArray(0)).isInfinite())
        assertTrue(TruePeak.peakDbtp(DoubleArray(4096)).isInfinite())
        // Shorter than two kernel tails: the sample peak is the best available answer, never a crash.
        val short = DoubleArray(20) { 0.25 }
        assertEquals(-12.04, TruePeak.peakDbtp(short), 0.01)
    }

    @Test
    fun floatAndDoubleBuffersAgree() {
        val x = tone(3000.0, 4096, 0.8, 0.2)
        val asFloat = FloatArray(x.size) { x[it].toFloat() }
        val delta = abs(TruePeak.peakDbtp(x) - TruePeak.peakDbtp(asFloat))
        assertTrue("float vs double: $delta dB", delta < 0.01)
    }
}
