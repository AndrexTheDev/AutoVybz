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

    // --- outlier rejection (analyse) -------------------------------------------------------

    @Test
    fun twoPositionsCanNeverReject() {
        // With two candidates there is no majority to say which one is wrong: whatever the spread,
        // both are kept (a disturbed second measurement still shows up as a large spreadDb).
        val good = result({ f -> bump(f, 100.0, 4.0, 0.5) })
        val bad = result({ f -> bump(f, 100.0, 4.0, 0.5) + bump(f, 900.0, -18.0, 0.2) })
        val a = SpatialAverager.analyse(listOf(good, bad))
        assertTrue(a.rejected.isEmpty())
        assertEquals(2, a.deviations.size)
        assertTrue("spread ${a.spreadDb}", a.spreadDb > 1.0)
        // Both deviate equally from their common average: nothing singles one of them out.
        assertEquals(a.deviations[0], a.deviations[1], 1e-9)
    }

    @Test
    fun oneDisturbedPositionIsDropped() {
        val room = { f: Double -> bump(f, 62.0, 7.0, 0.45) + bump(f, 240.0, -3.0, 0.3) }
        val a = result({ f -> room(f) + bump(f, 150.0, -12.0, 0.15) })
        val b = result({ f -> room(f) + bump(f, 400.0, -14.0, 0.15) })
        val c = result({ f -> room(f) + bump(f, 1000.0, -13.0, 0.15) })
        // A hand (or a passing body) over the phone: a broad 20 dB suck-out across the whole midrange,
        // which no position in the room actually shows.
        val bad = result({ f -> room(f) + bump(f, 900.0, -20.0, 1.5) })

        val kept = SpatialAverager.analyse(listOf(a, b, c))
        assertTrue(kept.rejected.isEmpty())

        val withBad = SpatialAverager.analyse(listOf(a, b, c, bad))
        assertEquals(listOf(3), withBad.rejected)
        // The averaged curve is the clean average, not a compromise with the disturbed position.
        val clean = SpatialAverager.average(listOf(a, b, c))
        for (i in grid.indices) assertEquals(clean.smoothedDb[i], withBad.result.smoothedDb[i], 1e-9)
        // ... and the spread is measured on the kept positions, so it stays small.
        assertTrue("spread ${withBad.spreadDb}", withBad.spreadDb < 1.0)
    }

    @Test
    fun atLeastTwoPositionsAlwaysSurvive() {
        val clean = { f: Double -> bump(f, 80.0, 5.0, 0.6) }
        val disturbed = { f: Double -> clean(f) + bump(f, 900.0, -20.0, 1.5) }
        // Whatever the deviations, a calibration never ends up with fewer than two positions: with
        // one survivor there is nothing left to average and the "outlier" would be the room.
        listOf(3, 4, 5).forEach { n ->
            val results = List(n) { i -> result(if (i == 0) clean else disturbed) }
            val r = SpatialAverager.analyse(results)
            assertTrue("n=$n rejected ${r.rejected}", n - r.rejected.size >= 2)
            assertTrue("n=$n duplicates ${r.rejected}", r.rejected.size == r.rejected.distinct().size)
        }
    }

    @Test
    fun equallyGoodPositionsAreAllKept() {
        val a = result({ f -> bump(f, 100.0, 5.0, 0.5) + bump(f, 900.0, -4.0, 0.4) })
        val b = result({ f -> bump(f, 100.0, 5.0, 0.5) + bump(f, 1100.0, -4.0, 0.4) })
        val c = result({ f -> bump(f, 100.0, 5.0, 0.5) + bump(f, 1050.0, -4.0, 0.4) })
        assertTrue(SpatialAverager.analyse(listOf(a, b, c)).rejected.isEmpty())
        // The floor keeps a merely "different" position in: without it the median deviation of three
        // evenly spread positions would be tiny and every one of them would look like an outlier.
        assertEquals(0, SpatialAverager.analyse(listOf(a, a, b)).rejected.size)
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
