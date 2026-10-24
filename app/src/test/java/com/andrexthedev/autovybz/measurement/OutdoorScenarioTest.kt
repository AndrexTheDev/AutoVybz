package com.andrexthedev.autovybz.measurement

import com.andrexthedev.autovybz.measurement.dsp.AnalyzerConfig
import com.andrexthedev.autovybz.measurement.dsp.FrequencyGrid
import com.andrexthedev.autovybz.measurement.dsp.KotlinFft
import com.andrexthedev.autovybz.measurement.dsp.MeasurementAnalyzer
import com.andrexthedev.autovybz.measurement.dsp.MeasurementResult
import com.andrexthedev.autovybz.measurement.dsp.MeasurementWarning
import com.andrexthedev.autovybz.measurement.dsp.SmoothingProfile
import com.andrexthedev.autovybz.measurement.dsp.SpatialAverager
import com.andrexthedev.autovybz.measurement.dsp.StimulusGenerator
import com.andrexthedev.autovybz.measurement.eq.AutoEqRenderer
import com.andrexthedev.autovybz.measurement.eq.AutoEqResult
import com.andrexthedev.autovybz.measurement.eq.AutoEqSettings
import com.andrexthedev.autovybz.measurement.eq.PeqBand
import com.andrexthedev.autovybz.measurement.eq.PeqType
import com.andrexthedev.autovybz.measurement.eq.TargetCurve
import com.andrexthedev.autovybz.measurement.eq.TargetType
import com.andrexthedev.autovybz.testutil.OutdoorNoise
import com.andrexthedev.autovybz.testutil.SpeakerSimulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * Outdoor / party scenario, end-to-end: party Bluetooth speaker (bass hump, presence dip, treble
 * roll-off, 55 Hz high-pass) in a garden - no room, strong ground reflection, 250 ms Bluetooth latency,
 * wind, birds, dropouts. See docs/OUTDOOR.md.
 */
class OutdoorScenarioTest {

    private val fs = 48000
    private val stimulus = StimulusGenerator.generate(fs)
    private val analyzer = MeasurementAnalyzer(KotlinFft)
    private val speaker = listOf(
        PeqBand(PeqType.PEAK, 120.0, 4.0, 1.0),
        PeqBand(PeqType.PEAK, 2800.0, -4.0, 1.4),
        PeqBand(PeqType.HIGH_SHELF, 9000.0, -3.0, 0.7),
    )
    private val truthSim = SpeakerSimulator(bands = speaker, highPassHz = 55.0)

    /** Ground reflection delay (s) and strength for different microphone spots. */
    private val spots = listOf(0.0015 to 0.6, 0.0021 to 0.7, 0.0028 to 0.5, 0.0034 to 0.7, 0.0041 to 0.8, 0.0052 to 0.7, 0.0060 to 0.6)

    private fun record(spot: Int): FloatArray {
        val (delay, gain) = spots[spot]
        return SpeakerSimulator(
            bands = speaker, highPassHz = 55.0, gain = 0.3, latencySeconds = 0.25,
            reflectionDelaySeconds = delay, reflectionGain = gain, noiseRms = 1e-4, seed = 21 + spot, extraTailSeconds = 1.0,
        ).play(stimulus.signal)
    }

    private fun analyze(rec: FloatArray, env: ListeningEnvironment): MeasurementResult {
        val t = EnvironmentTuning.of(env)
        return analyzer.analyze(rec, stimulus, config = AnalyzerConfig(smoothing = t.smoothing, sweepWeight = t.sweepWeight))
    }

    private fun fit(r: MeasurementResult, env: ListeningEnvironment): AutoEqResult {
        val t = EnvironmentTuning.of(env)
        val settings = AutoEqSettings(maxBoostDb = t.maxBoostDb, broadBoostOctaves = t.broadBoostOctaves, excursionGuardOctaves = t.excursionGuardOctaves)
        // Indoor = behaviour before the outdoor work (no SNR gating), to measure the improvement.
        val snr = if (env == ListeningEnvironment.OUTDOOR) r.snrDb else null
        return AutoEqRenderer.render(r.frequencies, r.smoothedDb, TargetCurve(TargetType.SMART), settings, r.thirdOctaveDb, snr)
    }

    /** RMS shape deviation (150 Hz - 8 kHz) of the *true* speaker + EQ from the target - what listeners get on average. */
    private fun trueError(e: AutoEqResult?): Double {
        val f = FrequencyGrid.log()
        val truth = DoubleArray(f.size) { truthSim.truthDb(f[it]) }
        val tn = FrequencyGrid.bandMean(f, truth, 300.0, 3000.0)
        val target = TargetCurve(TargetType.SMART).sample(f)
        val idx = f.indices.filter { f[it] in 150.0..8000.0 }
        val dev = idx.map { truth[it] - tn + (e?.filterResponseDb?.get(it) ?: 0.0) - target[it] }
        val off = dev.average()
        return sqrt(dev.sumOf { (it - off) * (it - off) } / dev.size)
    }

    @Test
    fun outdoorProfileBeatsIndoorProfileAndKeepsLoudness() {
        val recs = spots.indices.map { record(it) }
        val noEq = trueError(null)
        val indoor = recs.map { fit(analyze(it, ListeningEnvironment.INDOOR), ListeningEnvironment.INDOOR) }
        val outdoorResults = recs.map { analyze(it, ListeningEnvironment.OUTDOOR) }
        val outdoor = outdoorResults.map { fit(it, ListeningEnvironment.OUTDOOR) }

        val indoorErr = indoor.map(::trueError).average()
        val outdoorErr = outdoor.map(::trueError).average()
        assertTrue("outdoor $outdoorErr dB vs indoor $indoorErr dB", outdoorErr < indoorErr - 0.25)
        assertTrue("outdoor $outdoorErr dB vs no EQ $noEq dB", outdoorErr < noEq)
        // Loudness: the pre-gain never costs more than the 3 dB boost cap + 0.5 dB headroom
        // (+0.5 dB tolerance of the soft boost limit in the fitter).
        outdoor.forEach { assertTrue("preamp ${it.preampDb}", it.preampDb >= -4.0 - 1e-9) }
        assertTrue(indoor.minOf { it.preampDb } < -5.0) // the old behaviour wasted a lot of loudness here

        // Three spots spread over the audience area beat a single spot.
        val avg = fit(SpatialAverager.average(listOf(0, 3, 6).map { outdoorResults[it] }), ListeningEnvironment.OUTDOOR)
        assertTrue("3-position ${trueError(avg)} dB", trueError(avg) < 1.0 && trueError(avg) < outdoorErr)
    }

    @Test
    fun windIsDetectedAndBassIsNotCorrectedFromWindNoise() {
        val windy = OutdoorNoise.addWind(record(1), fs, rms = 0.03, seed = 5)
        val r = analyze(windy, ListeningEnvironment.OUTDOOR)
        assertTrue("warnings ${r.warnings}", MeasurementWarning.LOW_FREQ_NOISE in r.warnings)
        val e = fit(r, ListeningEnvironment.OUTDOOR)
        val f = r.frequencies
        val lfBoost = f.indices.filter { f[it] in 30.0..90.0 }.maxOf { e.filterResponseDb[it] }
        assertTrue("bass boost from wind: $lfBoost dB", lfBoost <= 0.5)
        assertTrue("gated ${e.noiseGatedRanges}", e.noiseGatedRanges.any { it.first < 100.0 })
    }

    @Test
    fun calmAndBirdsDoNotTriggerFalseNoiseWarnings() {
        val calm = analyze(record(2), ListeningEnvironment.OUTDOOR)
        assertTrue("calm ${calm.warnings}", calm.warnings.none { it in NOISE_WARNINGS })
        val birds = analyze(OutdoorNoise.addBirds(record(2), fs, 0.02), ListeningEnvironment.OUTDOOR)
        assertFalse("birds ${birds.warnings}", MeasurementWarning.LOW_FREQ_NOISE in birds.warnings)
        assertFalse(MeasurementWarning.DROPOUT in birds.warnings)
    }

    @Test
    fun bluetoothDropoutIsDetected() {
        // pink noise runs 0.75 s .. 3.5 s (+ 250 ms latency)
        val rec = OutdoorNoise.addDropout(record(0), fs, atSecond = 2.2, seconds = 0.08)
        assertTrue(MeasurementWarning.DROPOUT in analyze(rec, ListeningEnvironment.OUTDOOR).warnings)
    }

    @Test
    fun noiseBurstMakesNoiseUnsteady() {
        // a car passes at the end of the recording (after the sweep has decayed)
        val rec = OutdoorNoise.addNoiseBurst(record(3), fs, fromSecond = 10.3, toSecond = 11.3, rms = 0.01)
        assertTrue(MeasurementWarning.NOISE_UNSTEADY in analyze(rec, ListeningEnvironment.OUTDOOR).warnings)
    }

    @Test
    fun outdoorTuningRespectsUserLimits() {
        val o = EnvironmentTuning.of(ListeningEnvironment.OUTDOOR, SmoothingProfile.DYNAMIC, 9.0)
        assertEquals(EnvironmentTuning.OUTDOOR_MAX_BOOST_DB, o.maxBoostDb, 0.0)
        assertEquals(SmoothingProfile.STRONG, o.smoothing)
        assertTrue(o.forceLimiter && o.recommendedPositions == 3 && o.broadBoostOctaves != null)
        assertEquals(2.0, EnvironmentTuning.of(ListeningEnvironment.OUTDOOR, SmoothingProfile.FINE, 2.0).maxBoostDb, 0.0)
        assertEquals(SmoothingProfile.FINE, EnvironmentTuning.of(ListeningEnvironment.OUTDOOR, SmoothingProfile.FINE, 6.0).smoothing)
        val i = EnvironmentTuning.of(ListeningEnvironment.INDOOR, SmoothingProfile.DYNAMIC, 6.0)
        assertTrue(i.maxBoostDb == 6.0 && i.broadBoostOctaves == null && !i.forceLimiter && i.recommendedPositions == 1)
    }

    private companion object {
        val NOISE_WARNINGS = setOf(MeasurementWarning.LOW_FREQ_NOISE, MeasurementWarning.NOISE_UNSTEADY, MeasurementWarning.DROPOUT)
    }
}
