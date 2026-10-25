package com.andrexthedev.autovybz.measurement.dsp

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Plausibility checks for measurements in noisy / uncontrolled environments (outdoors, parties).
 * All inputs are on the analyzer's log grid in 1/3-octave resolution.
 */
object NoiseChecks {

    /**
     * Wind on the microphone, traffic rumble or a generator: the noise floor is dominated by the bass
     * (40–150 Hz is > [LF_DOMINANCE_DB] above 1–4 kHz, where a quiet floor is *lower* in the bass) **and**
     * it actually masks the speaker there (bass SNR < [LF_SNR_DB]). A quiet room with a small speaker that
     * simply plays no bass does not trigger this - its noise floor is not bass-dominated.
     */
    fun isLowFrequencyNoise(grid: DoubleArray, noiseDb: DoubleArray, snrDb: DoubleArray): Boolean {
        val lf = median(grid, noiseDb, 40.0, 150.0) ?: return false
        val mf = median(grid, noiseDb, 1000.0, 4000.0) ?: return false
        val lfSnr = median(grid, snrDb, 60.0, 150.0) ?: return false
        return lf - mf > LF_DOMINANCE_DB && lfSnr < LF_SNR_DB
    }

    /**
     * The noise at the start and at the end of the measurement differ by more than [UNSTEADY_DB] on
     * average (50 Hz – 5 kHz) - gusts, a passing car, voices. Only reported if the noise is loud enough
     * to matter (median SNR below [UNSTEADY_RELEVANT_SNR_DB]).
     */
    fun isUnsteady(grid: DoubleArray, headDb: DoubleArray, tailDb: DoubleArray, medianSnrDb: Double): Boolean {
        if (medianSnrDb >= UNSTEADY_RELEVANT_SNR_DB) return false
        val idx = grid.indices.filter { grid[it] in 50.0..5000.0 }
        if (idx.isEmpty()) return false
        return idx.map { abs(headDb[it] - tailDb[it]) }.average() > UNSTEADY_DB
    }

    /**
     * Signal dropout during the (stationary, broadband) pink-noise segment: at least
     * [DROPOUT_MIN_FRAMES] consecutive 10 ms frames more than 20 dB below the median frame energy.
     * Pink noise varies by about ±1 dB per frame, so this never triggers on a healthy recording;
     * noise bursts can only *raise* the level.
     */
    fun hasDropout(recording: FloatArray, start: Int, end: Int, sampleRate: Int): Boolean {
        val frame = (0.010 * sampleRate).roundToInt().coerceAtLeast(16)
        val s = start.coerceAtLeast(0)
        val e = end.coerceAtMost(recording.size)
        val frames = (e - s) / frame
        if (frames < 20) return false
        val energy = DoubleArray(frames) { f ->
            var acc = 0.0
            val o = s + f * frame
            for (i in o until o + frame) { val v = recording[i].toDouble(); acc += v * v }
            acc / frame
        }
        val median = energy.sorted()[frames / 2]
        if (median <= 0.0) return false
        var run = 0
        for (v in energy) {
            run = if (v < median * DROPOUT_RATIO) run + 1 else 0
            if (run >= DROPOUT_MIN_FRAMES) return true
        }
        return false
    }

    private fun median(grid: DoubleArray, values: DoubleArray, lo: Double, hi: Double): Double? {
        val v = grid.indices.filter { grid[it] in lo..hi }.map { values[it] }.sorted()
        return if (v.isEmpty()) null else v[v.size / 2]
    }

    const val LF_DOMINANCE_DB = 15.0
    const val LF_SNR_DB = 15.0
    const val UNSTEADY_DB = 6.0
    const val UNSTEADY_RELEVANT_SNR_DB = 35.0
    private const val DROPOUT_RATIO = 0.01 // -20 dB
    private const val DROPOUT_MIN_FRAMES = 2
}
