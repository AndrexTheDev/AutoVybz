package com.andrexthedev.autovybz.measurement.dsp

import kotlin.math.log10
import kotlin.math.max
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
 *
 * ## One bad position must not poison the average
 *
 * A calibration is a sequence of measurements, and any one of them can be disturbed: a door, a voice, a
 * hand on the phone, the Bluetooth speaker auto-powering off and on. Without a rule, such a position
 * pulls the averaged curve towards a response nobody hears – and the user has no way to tell which
 * measurement was the bad one (the app only showed a "positions disagree" hint *after* the fact).
 * [analyse] therefore compares every position against the *median* curve of all of them and drops the
 * ones that are far outside the spread of the others ([average] returns just the curve, [analyse] the
 * whole story). The median matters: against the mean, one very different position pulls the reference
 * towards itself, so all positions end up looking equally wrong (measured: a 20 dB suck-out at one of
 * four positions gave deviations of 5.8/4.6/4.3/4.7 dB against the mean - indistinguishable - and
 * 2.5/2.4/1.4/8.5 dB against the median - and the 8.5 dB one is the position that gets dropped).
 * With two positions nothing can be dropped either way:
 * there is no majority to say which one is wrong.
 */
object SpatialAverager {

    /** A position is dropped when it deviates more than this multiple of the *median* deviation. */
    const val OUTLIER_FACTOR = 3.0

    /**
     * ... but never below this absolute deviation: three clean positions in a lively room can differ by
     * a few dB from each other, and dropping "the loudest" of them would be worse than averaging them.
     */
    const val OUTLIER_FLOOR_DB = 1.5

    /** Result of a multi-position calibration, including what had to be excluded. */
    data class SpatialAverage(
        val result: MeasurementResult,
        /** RMS disagreement of the kept positions against their average (see [spreadDb]). */
        val spreadDb: Double,
        /** 0-based indices of the positions that were excluded as outliers, ascending. */
        val rejected: List<Int>,
        /** Deviations of every input position from the average of all of them (dB, same order). */
        val deviations: List<Double>,
    )

    fun average(results: List<MeasurementResult>): MeasurementResult = analyse(results).result

    fun analyse(results: List<MeasurementResult>): SpatialAverage {
        require(results.isNotEmpty()) { "no measurements" }
        if (results.size == 1) {
            return SpatialAverage(results[0], 0.0, emptyList(), listOf(0.0))
        }
        val first = results[0]
        val n = first.frequencies.size
        require(results.all { it.frequencies.size == n && it.sampleRate == first.sampleRate }) {
            "measurements use different frequency grids"
        }

        val deviations = deviationsFromReference(results)
        val rejected = if (results.size < 3) emptyList() else outliers(deviations)
        val kept = results.filterIndexed { index, _ -> index !in rejected }
        val averaged = powerAverage(kept)
        return SpatialAverage(averaged, spreadDb(kept, averaged), rejected, deviations)
    }

    /**
     * Deviation of each position from the *median* curve of all positions: level-aligned RMS over
     * 100 Hz – 10 kHz. The median is per grid point (mean of the two middle values for an even count),
     * i.e. a reference that a single disturbed position cannot move. Level alignment matters because a
     * position can be simply closer to the speaker.
     */
    private fun deviationsFromReference(results: List<MeasurementResult>): List<Double> {
        val f = results[0].frequencies
        val reference = medianCurve(results)
        return results.map { r ->
            val offset = FrequencyGrid.bandMean(f, r.smoothedDb, 300.0, 3000.0)
            var sum = 0.0
            var count = 0
            for (i in f.indices) {
                if (f[i] < 100.0 || f[i] > 10_000.0) continue
                val d = (r.smoothedDb[i] - offset) - reference[i]
                sum += d * d
                count++
            }
            if (count == 0) 0.0 else sqrt(sum / count)
        }
    }

    /** Per grid point the median across positions: the reference the outlier rule is built on. */
    private fun medianCurve(results: List<MeasurementResult>): DoubleArray {
        val n = results[0].frequencies.size
        val column = DoubleArray(results.size)
        return DoubleArray(n) { i ->
            for (k in results.indices) column[k] = results[k].smoothedDb[i] - levelOffset(results[k])
            column.sort()
            if (results.size % 2 == 1) column[results.size / 2]
            else 0.5 * (column[results.size / 2 - 1] + column[results.size / 2])
        }
    }

    private fun levelOffset(r: MeasurementResult): Double =
        FrequencyGrid.bandMean(r.frequencies, r.smoothedDb, 300.0, 3000.0)

    /**
     * Drop the positions whose deviation is more than [OUTLIER_FACTOR] × the median deviation, but never
     * fewer than two positions: a calibration must keep a majority, otherwise the "outlier" is the rule.
     */
    private fun outliers(deviations: List<Double>): List<Int> {
        val sorted = deviations.sorted()
        val median = sorted[sorted.size / 2]
        val limit = max(OUTLIER_FACTOR * median, OUTLIER_FLOOR_DB)
        val candidates = deviations.indices
            .filter { deviations[it] > limit }
            .sortedByDescending { deviations[it] }
            .take(maxOf(0, deviations.size - 2))
        return candidates.sorted()
    }

    private fun powerAverage(results: List<MeasurementResult>): MeasurementResult {
        if (results.size == 1) return results[0]
        val first = results[0]
        val n = first.frequencies.size

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

    /** Convenience for tests and diagnostics: the largest deviation, 0 for a single position. */
    fun maxDeviationDb(results: List<MeasurementResult>): Double =
        if (results.size < 2) 0.0 else deviationsFromReference(results).maxOrNull() ?: 0.0
}
