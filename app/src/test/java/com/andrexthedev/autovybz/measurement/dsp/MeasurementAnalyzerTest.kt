package com.andrexthedev.autovybz.measurement.dsp

import com.andrexthedev.autovybz.measurement.eq.PeqBand
import com.andrexthedev.autovybz.measurement.eq.PeqType
import com.andrexthedev.autovybz.testutil.SpeakerSimulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * End-to-end: stimulus -> simulated speaker/room/mic -> analyzer, compared
 * against the simulator's known ground truth.
 */
class MeasurementAnalyzerTest {

    private val stimulus = StimulusGenerator.generate(48000)
    private val analyzer = MeasurementAnalyzer(KotlinFft)

    private fun maxErrorDb(result: MeasurementResult, sim: SpeakerSimulator, lo: Double, hi: Double): Double {
        val f = result.frequencies
        val truth = DoubleArray(f.size) { sim.truthDb(f[it]) }
        val tNorm = FrequencyGrid.bandMean(f, truth, 300.0, 3000.0)
        return f.indices.filter { f[it] in lo..hi }.maxOf { abs(result.smoothedDb[it] - (truth[it] - tNorm)) }
    }

    @Test
    fun identitySystemIsFlat() {
        val sim = SpeakerSimulator(gain = 0.3, latencySeconds = 0.012)
        val r = analyzer.analyze(sim.play(stimulus.signal), stimulus)
        val err = maxErrorDb(r, sim, 25.0, 18000.0)
        assertTrue("identity max error $err dB", err < 0.3)
        assertEquals(12.0, r.latencyMs, 0.1)
        assertTrue(r.warnings.isEmpty())
    }

    @Test
    fun bluetoothSpeakerWithRoomModeIsRecovered() {
        val sim = SpeakerSimulator(
            bands = listOf(
                PeqBand(PeqType.PEAK, 120.0, 7.0, 2.0),     // room mode
                PeqBand(PeqType.PEAK, 2500.0, -5.0, 1.5),   // driver dip
                PeqBand(PeqType.HIGH_SHELF, 9000.0, -4.0, 0.7),
            ),
            highPassHz = 65.0,                               // small speaker roll-off
            latencySeconds = 0.235,                          // typical Bluetooth latency
            gain = 0.2,
            noiseRms = 0.0005,                               // ~ -66 dBFS room noise
        )
        val r = analyzer.analyze(sim.play(stimulus.signal), stimulus)
        assertEquals(235.0, r.latencyMs, 0.1)
        val err = maxErrorDb(r, sim, 60.0, 16000.0)
        // Smoothing (1 oct in the bass) legitimately flattens the narrow 120 Hz mode a bit.
        assertTrue("max error $err dB", err < 2.0)
        val errMid = maxErrorDb(r, sim, 400.0, 16000.0)
        assertTrue("mid/high max error $errMid dB", errMid < 0.8)
        assertTrue("SNR ${r.medianSnrDb}", r.medianSnrDb > 25.0)
        assertTrue(MeasurementWarning.CLIPPING !in r.warnings)
    }

    @Test
    fun micCompensationIsSubtracted() {
        val sim = SpeakerSimulator(gain = 0.3)
        val mic: (Double) -> Double = { f -> if (f > 8000.0) 3.0 else 0.0 }
        val r = analyzer.analyze(sim.play(stimulus.signal), stimulus, mic)
        val i12k = r.frequencies.indexOfFirst { it >= 12000.0 }
        assertEquals(-3.0, r.smoothedDb[i12k], 0.4)
    }

    @Test
    fun clippingAndLowSnrAreFlagged() {
        val loud = SpeakerSimulator(gain = 2.5)
        val clipped = loud.play(stimulus.signal).map { it.coerceIn(-1f, 1f) }.toFloatArray()
        assertTrue(MeasurementWarning.CLIPPING in analyzer.analyze(clipped, stimulus).warnings)

        val noisy = SpeakerSimulator(gain = 0.01, noiseRms = 0.01)
        assertTrue(MeasurementWarning.LOW_SNR in analyzer.analyze(noisy.play(stimulus.signal), stimulus).warnings)
    }

    @Test(expected = MeasurementException::class)
    fun silenceThrows() {
        analyzer.analyze(FloatArray(stimulus.signal.size + 1000), stimulus)
    }
}
