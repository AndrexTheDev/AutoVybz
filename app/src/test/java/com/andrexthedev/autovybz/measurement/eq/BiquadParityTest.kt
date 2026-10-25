package com.andrexthedev.autovybz.measurement.eq

import com.bearinmind.equalizer314.dsp.BiquadFilter
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.log10
import kotlin.random.Random

/** AutoVybz's fast biquad maths must match the Equalizer314 engine exactly. */
class BiquadParityTest {

    @Test
    fun highPassMatchesEqualizer314() {
        val rnd = Random(11)
        repeat(50) {
            val f = 30.0 * Math.pow(30.0, rnd.nextDouble()) // 30..900 Hz (protective-pass range)
            val q = rnd.nextDouble(0.5, 1.5)
            // Gain is meaningless for a pass - both implementations must ignore it.
            val upstream = BiquadFilter(f.toFloat(), 7.5f, BiquadFilter.FilterType.HIGH_PASS, 48000, q)
            val band = PeqBand(PeqType.HIGH_PASS, f.toFloat().toDouble(), 0.0, q)
            for (probe in listOf(20.0, 40.0, 80.0, 160.0, 320.0, 640.0, 1280.0, 4000.0)) {
                val expected = 20.0 * log10(upstream.getFrequencyResponse(probe.toFloat()).toDouble())
                assertEquals("$band @ $probe Hz", expected, BiquadMath.magnitudeDb(band, probe), 1e-3)
            }
        }
    }


    @Test
    fun matchesEqualizer314BiquadFilter() {
        val rnd = Random(7)
        val types = mapOf(
            PeqType.PEAK to BiquadFilter.FilterType.BELL,
            PeqType.LOW_SHELF to BiquadFilter.FilterType.LOW_SHELF,
            PeqType.HIGH_SHELF to BiquadFilter.FilterType.HIGH_SHELF,
        )
        repeat(200) {
            val type = PeqType.values()[rnd.nextInt(3)]
            val f = 20.0 * Math.pow(1000.0, rnd.nextDouble())
            val g = rnd.nextDouble(-12.0, 12.0)
            val q = rnd.nextDouble(0.4, 6.0)
            val upstream = BiquadFilter(f.toFloat(), g.toFloat(), types.getValue(type), 48000, q)
            val band = PeqBand(type, f.toFloat().toDouble(), g.toFloat().toDouble(), q)
            for (probe in listOf(25.0, 100.0, 440.0, 1000.0, 3150.0, 9000.0, 17000.0)) {
                val expected = 20.0 * log10(upstream.getFrequencyResponse(probe.toFloat()).toDouble())
                assertEquals("$band @ $probe Hz", expected, BiquadMath.magnitudeDb(band, probe), 1e-3)
            }
        }
    }
}
