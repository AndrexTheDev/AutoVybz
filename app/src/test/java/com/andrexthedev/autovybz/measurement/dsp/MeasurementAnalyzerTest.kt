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

    @Test
    fun irPeakSearchIgnoresIsolatedSpike() {
        // The direct arrival concentrates energy (main peak + ringing) inside
        // ~1 ms; a single taller noise sample must not steal the latency.
        val fs = 48000
        val ir = DoubleArray(fs / 2)
        val rnd = java.util.Random(7)
        for (i in ir.indices) ir[i] = rnd.nextGaussian() * 0.10
        val peak = 10000
        ir[peak] = 1.0
        ir[peak - 1] = 0.6
        ir[peak + 1] = 0.62
        ir[peak - 2] = 0.3
        ir[peak + 2] = 0.28
        for (j in 3 until 48) {
            ir[peak + j] += 0.5 * kotlin.math.exp(-j / 18.0) * kotlin.math.sin(2 * Math.PI * 900.0 * j / fs)
        }
        ir[3000] = 1.45 // isolated deconvolved-noise spike, taller than the direct peak
        assertEquals(peak, analyzer.locateIrPeak(ir, ir.size - 1, fs))
    }

    @Test
    fun latencyIsFoundDespiteNoiseAndReflection() {
        val sim = SpeakerSimulator(
            highPassHz = 80.0,
            latencySeconds = 0.30,
            gain = 0.06,
            noiseRms = 0.0015,
            reflectionDelaySeconds = 0.008,
            reflectionGain = 0.3,
        )
        val r = analyzer.analyze(sim.play(stimulus.signal), stimulus)
        assertEquals(300.0, r.latencyMs, 1.0)
        val err = maxErrorDb(r, sim, 200.0, 6000.0)
        assertTrue("max error $err dB", err < 3.0)
    }

    @Test
    fun theWholePipelineAlsoWorksAtTheFallbackRate() {
        // 44.1 kHz is what a device without 48 kHz capture runs at (see CaptureRates); the stimulus, the
        // noise-floor window and the analysis grid all have to follow that rate.
        val fs = 44100
        val s44 = StimulusGenerator.generate(fs)
        val sim = SpeakerSimulator(
            sampleRate = fs,
            bands = listOf(
                PeqBand(PeqType.PEAK, 130.0, 6.0, 1.6),
                PeqBand(PeqType.PEAK, 2600.0, -4.0, 1.5),
            ),
            highPassHz = 60.0,
            latencySeconds = 0.18,
            gain = 0.25,
            noiseRms = 5e-4,
            extraTailSeconds = 1.3,
        )
        val r = analyzer.analyze(sim.play(s44.signal), s44)
        assertEquals(180.0, r.latencyMs, 0.5)
        val err = maxErrorDb(r, sim, 60.0, 16000.0)
        assertTrue("44.1 kHz max error $err dB", err < 2.0)
        assertTrue("SNR ${r.medianSnrDb}", r.medianSnrDb > 20.0)
        assertTrue("warnings ${r.warnings}", MeasurementWarning.LATENCY_OUT_OF_RANGE !in r.warnings)
    }

    @Test
    fun dcOffsetIsRemovedBeforeTheAnalysis() {
        // Phone mics (and USB-C dongles) can deliver a constant offset. It is not part of the room
        // response: it would inflate the peak (fake clipping) and leak into DC/low-frequency bins.
        val sim = SpeakerSimulator(gain = 0.3, latencySeconds = 0.012)
        val clean = sim.play(stimulus.signal)
        val offset = 0.25f
        val withDc = FloatArray(clean.size) { clean[it] + offset }
        val r = analyzer.analyze(withDc, stimulus)
        val err = maxErrorDb(r, sim, 25.0, 18000.0)
        assertTrue("DC offset error $err dB", err < 0.3)
        assertEquals(12.0, r.latencyMs, 0.1)
        assertTrue("warnings ${r.warnings}", MeasurementWarning.CLIPPING !in r.warnings)
    }

    @Test
    fun aPureDcRecordingIsStillSilence() {
        // After the offset is removed nothing is left - the recording has to be rejected, not analysed.
        val silence = FloatArray(stimulus.signal.size) { 0.3f }
        val error = runCatching { analyzer.analyze(silence, stimulus) }.exceptionOrNull()
        assertTrue("expected MeasurementException, got $error", error is MeasurementException)
        assertEquals(MeasurementException.Reason.NO_SIGNAL, (error as MeasurementException).reason)
    }

    @Test
    fun sweepWeightRampsWithSnr() {
        // Pure sweep where the pink noise is buried in the noise floor...
        assertEquals(1.0, analyzer.blendWeight(0.0, 0.5), 0.0)
        assertEquals(1.0, analyzer.blendWeight(3.0, 0.5), 0.0)
        // ...the configured weight once the pink path is trustworthy...
        assertEquals(0.5, analyzer.blendWeight(12.0, 0.5), 0.0)
        assertEquals(0.5, analyzer.blendWeight(45.0, 0.5), 0.0)
        // ...and a linear ramp in between (no step for the fitter to chase).
        assertEquals(0.75, analyzer.blendWeight(7.5, 0.5), 1e-12)
        assertEquals(1.0, analyzer.blendWeight(12.0, 1.0), 0.0)
        assertEquals(1.0, analyzer.blendWeight(40.0, 1.5), 0.0) // out-of-range input is clamped
        var previous = 1.1
        for (i in 0..60) {
            val w = analyzer.blendWeight(i / 2.0, 0.5)
            assertTrue("weight rises with SNR at $i", w <= previous + 1e-12)
            assertTrue("weight within [sweepWeight, 1]", w >= 0.5 - 1e-12 && w <= 1.0 + 1e-12)
            previous = w
        }
    }

    @Test
    fun latencyAtTheSearchLimitIsFlagged() {
        // 799 ms sits inside the latency window, but within the 2 ms edge margin: the curve then comes
        // from a truncated response and must not be presented as trustworthy.
        val sim = SpeakerSimulator(gain = 0.3, latencySeconds = 0.799, extraTailSeconds = 1.0)
        val r = analyzer.analyze(sim.play(stimulus.signal), stimulus)
        assertEquals(799.0, r.latencyMs, 2.0)
        assertTrue("warnings ${r.warnings}", MeasurementWarning.LATENCY_OUT_OF_RANGE in r.warnings)
        assertTrue("warnings ${r.warnings}", MeasurementWarning.HIGH_LATENCY in r.warnings)
    }

    @Test
    fun normalLatencyIsNotFlagged() {
        val sim = SpeakerSimulator(gain = 0.3, latencySeconds = 0.12)
        val r = analyzer.analyze(sim.play(stimulus.signal), stimulus)
        assertTrue("warnings ${r.warnings}", r.warnings.isEmpty())
    }

    @Test(expected = MeasurementException::class)
    fun silenceThrows() {
        analyzer.analyze(FloatArray(stimulus.signal.size + 1000), stimulus)
    }
}
