package com.andrexthedev.autovybz.measurement.dsp

import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Combines measurements taken at several microphone positions around the listening spot.
 *
 * Why: a single position contains position-specific cancellations (deep, narrow dips) that an EQ must
 * not try to fill, while room modes that boom everywhere should be corrected. A power ("RMS") average
 * over a few positions 30–50 cm apart keeps the common peaks and fills the position-dependent dips –
 * the standard technique of room-correction systems (REW calls it "RMS average").
 *
 * All curves are power-averaged per grid point and re-normalised to 0 dB in 300 Hz – 3 kHz, like a
 * single measurement. SNR is the per-point minimum (conservative), latency the mean, warnings the union.
 */
object SpatialAverager {

    fun average(results: List<MeasurementResult>): MeasurementResult {
        require(results.isNotEmpty()) { "no measurements" }
        if (results.size == 1) return results[0]
        val first = results[0]
        val n = first.frequencies.size
        require(results.all { it.frequencies.size == n && it.sampleRate == first.sampleRate }) {
            "measurements use different frequency grids"
        }

        fun powerAverage(pick: (MeasurementResult) -> DoubleArray): DoubleArray = DoubleArray(n) { i ->
            var p = 0.0
            for (r in results) p += 10.0.pow(pick(r)[i] / 10.0)
            10.0 * log10(maxOf(p / results.size, 1e-20))
        }

        val smoothed = powerAverage { it.smoothedDb }
        val norm = FrequencyGrid.bandMean(first.frequencies, smoothed, 300.0, 3000.0)
        fun normalised(a: DoubleArray) = DoubleArray(n) { a[it] - norm }

        val snr = DoubleArray(n) { i -> results.minOf { it.snrDb[i] } }
        val sortedSnr = snr.sorted()
        val warnings = results.flatMap { it.warnings }.distinct()

        return MeasurementResult(
            sampleRate = first.sampleRate,
            frequencies = first.frequencies.copyOf(),
            rawDb = normalised(powerAverage { it.rawDb }),
            smoothedDb = normalised(smoothed),
            thirdOctaveDb = normalised(powerAverage { it.thirdOctaveDb }),
            sweepSmoothedDb = normalised(powerAverage { it.sweepSmoothedDb }),
            pinkSmoothedDb = normalised(powerAverage { it.pinkSmoothedDb }),
            snrDb = snr,
            latencyMs = results.map { it.latencyMs }.average(),
            inputPeak = results.maxOf { it.inputPeak },
            medianSnrDb = sortedSnr[sortedSnr.size / 2],
            warnings = warnings,
            normalizationDb = results.map { it.normalizationDb }.average() + norm,
        )
    }

    /**
     * How much the positions disagree: RMS over 100 Hz – 10 kHz of each position's deviation from the
     * average (smoothed curves, dB). ~1–3 dB is normal; > 6 dB means the phone moved a lot, the room is
     * very uneven or a measurement was disturbed.
     */
    fun spreadDb(results: List<MeasurementResult>, average: MeasurementResult = average(results)): Double {
        if (results.size < 2) return 0.0
        val f = average.frequencies
        var sum = 0.0
        var count = 0
        for (r in results) {
            val offset = FrequencyGrid.bandMean(f, r.smoothedDb, 300.0, 3000.0)
            for (i in f.indices) {
                if (f[i] < 100.0 || f[i] > 10_000.0) continue
                val d = (r.smoothedDb[i] - offset) - average.smoothedDb[i]
                sum += d * d
                count++
            }
        }
        return if (count == 0) 0.0 else sqrt(sum / count)
    }
}
