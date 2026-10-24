package com.andrexthedev.autovybz.measurement

import com.andrexthedev.autovybz.measurement.dsp.AnalyzerConfig
import com.andrexthedev.autovybz.measurement.dsp.KotlinFft
import com.andrexthedev.autovybz.measurement.dsp.MeasurementAnalyzer
import com.andrexthedev.autovybz.measurement.dsp.SpatialAverager
import com.andrexthedev.autovybz.measurement.dsp.StimulusGenerator
import com.andrexthedev.autovybz.measurement.eq.AutoEqRenderer
import com.andrexthedev.autovybz.measurement.eq.AutoEqSettings
import com.andrexthedev.autovybz.measurement.eq.PeqBand
import com.andrexthedev.autovybz.measurement.eq.PeqType
import com.andrexthedev.autovybz.measurement.eq.TargetCurve
import com.andrexthedev.autovybz.measurement.eq.TargetType
import com.andrexthedev.autovybz.testutil.SpeakerSimulator
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Performance budget of the pure-Kotlin fallback path (the slowest one: no native FFT). A CI runner is
 * roughly as fast as a mid-range phone's big core, the budget leaves >= 3x headroom for low-end devices,
 * where the native C++ FFT is used anyway. Timings are printed for the test report.
 */
class PerformanceBudgetTest {

    private inline fun <T> timed(label: String, budgetMs: Long, block: () -> T): T {
        block() // warm-up (JIT)
        val t0 = System.nanoTime()
        val r = block()
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("PERF $label: $ms ms (budget $budgetMs ms)")
        assertTrue("$label took $ms ms > $budgetMs ms", ms <= budgetMs)
        return r
    }

    @Test
    fun fullPipelineFitsTheBudget() {
        val stimulus = timed("stimulus generation (10 s @ 48 kHz)", 1500) { StimulusGenerator.generate(48000) }
        val rec = SpeakerSimulator(
            bands = listOf(PeqBand(PeqType.PEAK, 150.0, 5.0, 1.2), PeqBand(PeqType.PEAK, 3000.0, -4.0, 2.0)),
            highPassHz = 60.0, gain = 0.3, latencySeconds = 0.2, noiseRms = 1e-4, extraTailSeconds = 1.0,
        ).play(stimulus.signal)
        val analyzer = MeasurementAnalyzer(KotlinFft)
        val result = timed("analysis (Kotlin FFT)", 4000) { analyzer.analyze(rec, stimulus, config = AnalyzerConfig()) }
        timed("spatial average of 3 positions", 500) { SpatialAverager.average(listOf(result, result, result)) }
        timed("auto-EQ fit, 10 bands", 1500) {
            AutoEqRenderer.render(result.frequencies, result.smoothedDb, TargetCurve(TargetType.SMART), AutoEqSettings(bandCount = 10), result.thirdOctaveDb, result.snrDb)
        }
        timed("auto-EQ fit, 5 bands", 1000) {
            AutoEqRenderer.render(result.frequencies, result.smoothedDb, TargetCurve(TargetType.HARMAN), AutoEqSettings(bandCount = 5), result.thirdOctaveDb, result.snrDb)
        }
    }

    @Test
    fun analysisDoesNotLeakMemoryAcrossRepeatedMeasurements() {
        val stimulus = StimulusGenerator.generate(48000)
        val rec = SpeakerSimulator(gain = 0.3, noiseRms = 1e-4, extraTailSeconds = 1.0).play(stimulus.signal)
        val analyzer = MeasurementAnalyzer(KotlinFft)
        val rt = Runtime.getRuntime()
        fun used(): Long { repeat(3) { System.gc(); Thread.sleep(50) }; return rt.totalMemory() - rt.freeMemory() }
        analyzer.analyze(rec, stimulus)
        val before = used()
        repeat(8) { analyzer.analyze(rec, stimulus) }
        val growthMb = (used() - before) / 1_048_576.0
        println("PERF heap growth after 8 analyses: %.1f MB".format(growthMb))
        assertTrue("heap grew by $growthMb MB", growthMb < 40)
    }
}
