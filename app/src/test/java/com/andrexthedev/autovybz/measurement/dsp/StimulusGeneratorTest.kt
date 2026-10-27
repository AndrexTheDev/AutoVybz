package com.andrexthedev.autovybz.measurement.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class StimulusGeneratorTest {

    private val stimulus = StimulusGenerator.generate(48000)

    @Test
    fun totalDurationIsExactlyTenSeconds() {
        assertEquals(10.0, StimulusGenerator.TOTAL_S, 1e-12)
        assertEquals(480_000, stimulus.signal.size)
        assertEquals(10.0, stimulus.plan.durationSeconds, 1e-9)
    }

    @Test
    fun peakLevelLeavesHeadroom() {
        val peak = stimulus.signal.maxOf { abs(it) }
        assertTrue("peak $peak", peak <= StimulusGenerator.PEAK_LEVEL + 1e-6)
        assertTrue("peak $peak", peak > 0.4f)
    }

    @Test
    fun leadSilenceAndGapAreSilent() {
        val p = stimulus.plan
        for (i in 0 until p.pinkStart) assertEquals(0f, stimulus.signal[i], 0f)
        for (i in p.pinkStart + p.pinkLength until p.sweepStart) assertEquals(0f, stimulus.signal[i], 0f)
    }

    @Test
    fun pinkNoiseHasEqualEnergyPerOctave() {
        // Pink noise: power per octave band is constant (-3 dB/oct per Hz).
        val p = stimulus.plan
        val binHz = p.sampleRate.toDouble() / p.pinkPeriod
        fun octavePower(lo: Double) = stimulus.pinkPeriodPower.indices
            .filter { it * binHz >= lo && it * binHz < 2 * lo }
            .sumOf { stimulus.pinkPeriodPower[it] }
        val ref = powerToDb(octavePower(500.0))
        for (lo in listOf(50.0, 100.0, 200.0, 1000.0, 2000.0, 4000.0)) {
            assertEquals("octave from $lo Hz", ref, powerToDb(octavePower(lo)), 0.6)
        }
    }

    @Test
    fun pinkNoiseCrestFactorIsReduced() {
        // Random phases would give ~12 dB crest; the magnitude-preserving crest
        // reduction must bring it well below that at the same peak level
        // (more RMS = better SNR of the pink-noise analysis path).
        val p = stimulus.pinkPeriod
        var peak = 0.0f
        var sumSq = 0.0
        for (v in p) {
            peak = maxOf(peak, abs(v))
            sumSq += v.toDouble() * v
        }
        val rms = kotlin.math.sqrt(sumSq / p.size)
        val crestDb = 20 * kotlin.math.log10(peak / rms)
        assertEquals(StimulusGenerator.PEAK_LEVEL, peak, 2e-3f)
        assertTrue("crest factor $crestDb dB", crestDb < 7.0)
    }

    @Test
    fun everyRateTheCaptureLayerCanNegotiateBuildsAValidPlan() {
        // 44.1 kHz is the fallback for devices whose mic cannot run at 48 kHz (CaptureRates).
        for (fs in listOf(44100, 48000, 96000)) {
            val s = StimulusGenerator.generate(fs)
            val p = s.plan
            assertEquals("duration at $fs", 10.0, p.durationSeconds, 1e-9)
            assertTrue("period ${p.pinkPeriod} is a power of two", isPowerOfTwo(p.pinkPeriod))
            // The noise-floor snapshot uses one full period inside the lead silence.
            assertTrue("period ${p.pinkPeriod} fits the lead silence at $fs", p.pinkPeriod <= p.leadSilence)
            assertTrue("period ${p.pinkPeriod} usable at $fs", p.pinkPeriod >= 8192)
            // Still ~1.5 Hz / 2.7 Hz resolution where it matters.
            assertTrue("bin ${fs.toDouble() / p.pinkPeriod} Hz at $fs", fs.toDouble() / p.pinkPeriod < 3.0)
            assertTrue("sweep end ${p.sweepEndHz} at $fs", p.sweepEndHz <= fs * 0.45 + 1e-9)
            assertEquals(fs.toDouble() * 10, s.signal.size.toDouble(), 1.0)
            assertTrue(s.signal.maxOf { abs(it) } <= StimulusGenerator.PEAK_LEVEL + 1e-6)
        }
        // 48 kHz is unchanged by the rate-dependent window choice.
        assertEquals(32768, StimulusGenerator.plan(48000).pinkPeriod)
        assertEquals(16384, StimulusGenerator.plan(44100).pinkPeriod)
    }

    @Test
    fun phasesAreOrdered() {
        val p = stimulus.plan
        assertEquals(StimulusPlan.Phase.NOISE_FLOOR, p.phaseAt(0))
        assertEquals(StimulusPlan.Phase.PINK_NOISE, p.phaseAt(p.pinkStart + 10))
        assertEquals(StimulusPlan.Phase.SWEEP, p.phaseAt(p.sweepStart + 10))
        assertEquals(StimulusPlan.Phase.TAIL, p.phaseAt(p.totalLength - 1))
    }
}
