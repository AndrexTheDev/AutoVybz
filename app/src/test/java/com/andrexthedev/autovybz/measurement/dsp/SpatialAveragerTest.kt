package com.andrexthedev.autovybz.measurement.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import com.andrexthedev.autovybz.measurement.eq.PeqBand
import com.andrexthedev.autovybz.measurement.eq.PeqType
import com.andrexthedev.autovybz.testutil.SpeakerSimulator
import kotlin.math.abs
import kotlin.math.log2

class SpatialAveragerTest {

    private val grid = FrequencyGrid.log()

    /** Gaussian bump/dip on a log-frequency axis (width in octaves). */
    private fun bump(f: Double, center: Double, gainDb: Double, widthOct: Double): Double {
        val x = log2(f / center) / widthOct
        return gainDb * kotlin.math.exp(-x * x)
    }

    private fun result(curve: (Double) -> Double, snr: Double = 30.0, latency: Double = 20.0,
                       warnings: List<MeasurementWarning> = emptyList()): MeasurementResult {
        val db = DoubleArray(grid.size) { curve(grid[it]) }
        val norm = FrequencyGrid.bandMean(grid, db, 300.0, 3000.0)
        val n = DoubleArray(grid.size) { db[it] - norm }
        return MeasurementResult(
            sampleRate = 48000, frequencies = grid, rawDb = n.copyOf(), smoothedDb = n.copyOf(), thirdOctaveDb = n.copyOf(),
            sweepSmoothedDb = n.copyOf(), pinkSmoothedDb = n.copyOf(), snrDb = DoubleArray(grid.size) { snr },
            latencyMs = latency, inputPeak = 0.3, medianSnrDb = snr, warnings = warnings, normalizationDb = -40.0,
        )
    }

    private fun at(r: MeasurementResult, hz: Double) = r.smoothedDb[grid.indexOfFirst { it >= hz }]

    @Test
    fun commonRoomModeStaysWhilePositionDipsAreFilled() {
        val mode = { f: Double -> bump(f, 60.0, 6.0, 0.4) }
        val a = result({ f -> mode(f) + bump(f, 180.0, -15.0, 0.12) })
        val b = result({ f -> mode(f) + bump(f, 420.0, -15.0, 0.12) })
        val c = result({ f -> mode(f) + bump(f, 1200.0, -12.0, 0.12) })
        val avg = SpatialAverager.average(listOf(a, b, c))

        assertEquals(6.0, at(avg, 60.0), 1.0)              // the boom everyone hears is kept
        assertTrue(at(avg, 180.0) > -3.0)                   // single-position null: -15 dB -> about -1.6 dB
        assertTrue(at(avg, 420.0) > -3.0)
        assertTrue(at(a, 180.0) < -12.0)
        assertEquals(0.0, FrequencyGrid.bandMean(grid, avg.smoothedDb, 300.0, 3000.0), 1e-9)
        assertTrue(SpatialAverager.spreadDb(listOf(a, b, c), avg) > 0.5)
    }

    @Test
    fun identicalMeasurementsAverageToThemselves() {
        val a = result({ f -> bump(f, 100.0, 5.0, 0.5) + bump(f, 3000.0, -3.0, 0.7) })
        val avg = SpatialAverager.average(listOf(a, a, a))
        for (i in grid.indices) assertEquals(a.smoothedDb[i], avg.smoothedDb[i], 1e-9)
        assertEquals(0.0, SpatialAverager.spreadDb(listOf(a, a, a), avg), 1e-9)
        assertTrue(SpatialAverager.average(listOf(a)) === a)
    }

    @Test
    fun metadataIsCombinedConservatively() {
        val a = result({ 0.0 }, snr = 35.0, latency = 200.0)
        val b = result({ 0.0 }, snr = 12.0, latency = 220.0, warnings = listOf(MeasurementWarning.LOW_SNR, MeasurementWarning.HIGH_LATENCY))
        val avg = SpatialAverager.average(listOf(a, b))
        assertEquals(12.0, avg.medianSnrDb, 1e-9)
        assertEquals(210.0, avg.latencyMs, 1e-9)
        assertEquals(listOf(MeasurementWarning.LOW_SNR, MeasurementWarning.HIGH_LATENCY), avg.warnings)
        assertTrue(abs(avg.smoothedDb.maxOf { abs(it) }) < 1e-9)
    }

    /**
     * End-to-end: the same speaker measured at three spots whose floor/wall reflection arrives with a
     * different delay (= comb filter at different frequencies). The average must be closer to the
     * speaker's true response than the single measurements are on average.
     */
    @Test
    fun averagingPositionsReducesCombFilterErrorEndToEnd() {
        val stimulus = StimulusGenerator.generate(48000)
        val analyzer = MeasurementAnalyzer(KotlinFft)
        val speaker = listOf(PeqBand(PeqType.PEAK, 90.0, 6.0, 2.0), PeqBand(PeqType.PEAK, 3000.0, -4.0, 1.5))
        val sims = listOf(0.0019, 0.0031, 0.0047).mapIndexed { i, d ->
            SpeakerSimulator(bands = speaker, gain = 0.3, latencySeconds = 0.02, reflectionDelaySeconds = d,
                reflectionGain = 0.6, noiseRms = 1e-4, seed = 7 + i)
        }
        val results = sims.map { analyzer.analyze(it.play(stimulus.signal), stimulus) }
        val avg = SpatialAverager.average(results)

        fun meanError(r: MeasurementResult): Double {
            val f = r.frequencies
            val truth = DoubleArray(f.size) { sims[0].truthDb(f[it]) }
            val tNorm = FrequencyGrid.bandMean(f, truth, 300.0, 3000.0)
            val idx = f.indices.filter { f[it] in 150.0..8000.0 }
            return idx.sumOf { abs(r.smoothedDb[it] - (truth[it] - tNorm)) } / idx.size
        }
        val single = results.map(::meanError).average()
        val averaged = meanError(avg)
        assertTrue("averaged $averaged dB vs single $single dB", averaged < single)
        assertTrue(SpatialAverager.spreadDb(results, avg) > 0.0)
    }

    @Test
    fun rejectsMismatchedGrids() {
        val a = result({ 0.0 })
        val g2 = FrequencyGrid.log(pointsPerOctave = 24)
        val b = MeasurementResult(48000, g2, DoubleArray(g2.size), DoubleArray(g2.size), DoubleArray(g2.size), DoubleArray(g2.size),
            DoubleArray(g2.size), DoubleArray(g2.size), 0.0, 0.1, 20.0, emptyList(), 0.0)
        assertThrows(IllegalArgumentException::class.java) { SpatialAverager.average(listOf(a, b)) }
    }
}
