package com.andrexthedev.autovybz.measurement.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * ITU-R BS.1770-4, checked against the values the standard itself publishes.
 *
 * The strongest check in here is the coefficient table: BS.1770 prints the two biquads for 48 kHz, and
 * reproducing them to 1e-9 proves that the analog prototype was prewarped correctly. A cookbook (RBJ) shelf
 * gets the 1 kHz gain wrong by 0.26 dB - which would shift *every* loudness number in the app by a quarter
 * of a decibel while still looking plausible.
 */
class IntegratedLoudnessTest {

    private val fs = 48000

    private fun sine(amplitude: Double, seconds: Double, frequency: Double = 1000.0): FloatArray {
        val n = (seconds * fs).toInt()
        return FloatArray(n) { i -> (amplitude * sin(2.0 * PI * frequency * i / fs)).toFloat() }
    }

    /** Amplitude of a 1 kHz sine that reads [lufs] on this implementation (inverse of the anchor below). */
    private fun amplitudeFor(lufs: Double): Double {
        val gain = IntegratedLoudness.kWeightingGainDb(1000.0, fs)
        return sqrt(2.0 * 10.0.pow((lufs - IntegratedLoudness.LOUDNESS_OFFSET_DB - gain) / 10.0))
    }

    @Test
    fun theFiltersAreTheOnesTheStandardPrints() {
        // BS.1770-4, Tables 1 and 2 (48 kHz).
        val expectedShelf = doubleArrayOf(
            1.53512485958697, -2.69169618940638, 1.19839281085285, -1.69065929318241, 0.73248077421585,
        )
        val expectedRlb = doubleArrayOf(1.0, -2.0, 1.0, -1.99004745483398, 0.99007225036621)
        IntegratedLoudness.shelf(fs).forEachIndexed { i, v -> assertEquals("shelf[$i]", expectedShelf[i], v, 1e-9) }
        IntegratedLoudness.rlb(fs).forEachIndexed { i, v -> assertEquals("rlb[$i]", expectedRlb[i], v, 1e-9) }
    }

    @Test
    fun theKWeightingHasTheDocumentedShape() {
        assertEquals("1 kHz should read +0.70 dB", 0.698, IntegratedLoudness.kWeightingGainDb(1000.0, fs), 0.01)
        // The head shelf reaches its +4 dB plateau in the presence region...
        assertTrue(IntegratedLoudness.kWeightingGainDb(3000.0, fs) > 3.0)
        assertTrue(IntegratedLoudness.kWeightingGainDb(10000.0, fs) > 3.5)
        // ...and the RLB filter takes the bass away: about -12 dB an octave below its 38 Hz corner, but
        // only in the asymptote - at 40 Hz (just above the corner) it is -5.6 dB, at 20 Hz -13.3 dB, and
        // the head shelf adds its ~0 dB there. The thresholds below are those two numbers, not the
        // asymptote: asserting the asymptote at 40 Hz fails (and did, until `check_parity.py` said so).
        assertTrue("40 Hz", IntegratedLoudness.kWeightingGainDb(40.0, fs) < -5.4)
        assertTrue("20 Hz", IntegratedLoudness.kWeightingGainDb(20.0, fs) < -13.0)
    }

    /**
     * The anchor of the whole standard: a full-scale 1 kHz sine reads -3.01 LUFS. Everything else - the
     * pre-gain promise, the verification pass - is measured relative to this number being right.
     */
    @Test
    fun aFullScaleSineReadsMinusThree() {
        assertEquals(-3.01, IntegratedLoudness.integrated(sine(1.0, 5.0), fs)!!, 0.05)
        assertEquals(-23.0, IntegratedLoudness.integrated(sine(amplitudeFor(-23.0), 5.0), fs)!!, 0.05)
        assertEquals(-33.0, IntegratedLoudness.integrated(sine(amplitudeFor(-33.0), 5.0), fs)!!, 0.05)
    }

    /** EBU Tech 3341 case 3: quiet ends must be removed by the *absolute* gate. */
    @Test
    fun theAbsoluteGateRemovesSilence() {
        val quiet = sine(amplitudeFor(-72.0), 2.0)
        val loud = sine(amplitudeFor(-36.0), 8.0)
        val joined = FloatArray(quiet.size + loud.size + quiet.size)
        quiet.copyInto(joined, 0)
        loud.copyInto(joined, quiet.size)
        quiet.copyInto(joined, quiet.size + loud.size)
        assertEquals(-36.0, IntegratedLoudness.integrated(joined, fs)!!, 0.1)
        // Pure silence is not "-inf LUFS", it is "no measurement".
        assertNull(IntegratedLoudness.integrated(FloatArray(fs * 3), fs))
    }

    /**
     * The *relative* gate: a passage 17 LU below the rest must not drag the integrated value down (a raw
     * energy average over the whole file would read ~-24.3 LUFS instead of -23.1).
     */
    @Test
    fun theRelativeGateIgnoresQuietPassages() {
        val loud = sine(amplitudeFor(-23.0), 6.0)
        val quiet = sine(amplitudeFor(-40.0), 6.0)
        val joined = FloatArray(loud.size + quiet.size)
        loud.copyInto(joined, 0)
        quiet.copyInto(joined, loud.size)
        val lufs = IntegratedLoudness.integrated(joined, fs)!!
        assertEquals(-23.0, lufs, 0.2)
        // Without the relative gate the same material would read clearly lower - the gate is doing work.
        val naive = 10.0 * log10(0.5 * (10.0.pow(-23.0 / 10.0) + 10.0.pow(-40.0 / 10.0)))
        assertTrue("naive $naive is not what we report", lufs > naive + 0.5)
    }

    @Test
    fun tooShortIsNoMeasurement() {
        assertNull(IntegratedLoudness.integrated(FloatArray(100), fs))
        assertNull(IntegratedLoudness.analyse(FloatArray(100), fs))
    }

    @Test
    fun theResultCarriesTheTruePeakNextToTheLoudness() {
        val signal = sine(0.5, 2.0, 12000.0).also { x ->
            // A 12 kHz tone at 45 degrees has its crest between the samples.
            for (i in x.indices) x[i] = (0.5 * cos(2.0 * PI * 12000.0 * i / fs + PI / 4.0)).toFloat()
        }
        val result = IntegratedLoudness.analyse(signal, fs)
        assertNotNull(result)
        assertTrue("true peak ${result!!.truePeakDbtp} dBTP", result.truePeakDbtp > -1.0 && result.truePeakDbtp < 0.1)
        assertTrue("sample peak ${IntegratedLoudness.samplePeakDbfs(signal)}", IntegratedLoudness.samplePeakDbfs(signal) < -2.9)
        assertTrue(result.blocks > 0)
    }

    /** A 6 dB level change must read as 6 LU - the property the "as loud as before" promise needs. */
    @Test
    fun theScaleIsLinearInDecibels() {
        val base = sine(amplitudeFor(-20.0), 4.0)
        val louder = FloatArray(base.size) { (base[it] * 2.0).toFloat() }
        assertEquals(6.02, IntegratedLoudness.loudnessDifferenceLu(base, louder, fs)!!, 0.05)
    }

    @Test
    fun otherSampleRatesWorkToo() {
        // The same tone at 44.1 kHz must read the same loudness: the coefficients are derived, not tabulated.
        val n = (5.0 * 44100).toInt()
        val at441 = FloatArray(n) { i -> (amplitudeFor(-23.0) * sin(2.0 * PI * 1000.0 * i / 44100)).toFloat() }
        assertEquals(-23.0, IntegratedLoudness.integrated(at441, 44100)!!, 0.05)
    }
}
