package com.andrexthedev.autovybz.measurement.dsp

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Reverberation time from the impulse response the analyzer already has.
 *
 * F3 of the roadmap: a lively room costs ~2 dB of measured accuracy, and until now the app simply threw
 * that information away. The deconvolved response contains it - all that is needed is the standard
 * Schroeder backward integration, plus two decisions that a naive implementation gets wrong:
 *
 * 1. **The direct sound is not room decay.** The first milliseconds are the loudspeaker, and including
 *    them makes every room look ~40 % drier (measured on synthetic rooms: 0.50 s read as 0.27 s with the
 *    direct sound inside the window, 0.50 s with [DIRECT_SKIP_SECONDS] skipped).
 * 2. **An exponential fit, not a threshold crossing.** A real recording ends its decay in the noise
 *    plateau of the deconvolution, and a plateau has no slope. A "-5 → -35 dB" rule then extrapolates the
 *    plateau; at 20 dB SNR our own traffic model reported 5.4 s for a 0.9 s room. The fit is taken over
 *    -5 … -25 dB and is only accepted when the plateau sits [NOISE_CLEARANCE_DB] below that region and the
 *    line is straight (R² ≥ [MIN_R_SQUARED]); otherwise the answer is "unknown", never a plausible number.
 *
 * The result is *T60 by extrapolation* of a T20 fit (×3), which is what the app needs: a number to decide
 * whether to correct less, and one sentence for the user.
 */
object RoomDecay {

    /** Above this reverberation time the correction is gated and the wizard says so (roadmap W4). */
    const val REVERBERANT_THRESHOLD_S = 0.8

    /** The direct sound (and the earliest reflections) are skipped by this much before integrating. */
    const val DIRECT_SKIP_SECONDS = 0.010

    /** How far below the end of the fit region the noise plateau must sit for the fit to be believable. */
    const val NOISE_CLEARANCE_DB = 10.0

    /** Upper bound of the integrated window; beyond it the plateau (or the end of the capture) decides. */
    const val MAX_SECONDS = 2.0

    /** A fitted line this straight is a decay; anything less is noise that happens to slope. */
    const val MIN_R_SQUARED = 0.98

    /**
     * Reverberation time (s) of [ir] whose direct arrival is at [peak], or `null` when this recording
     * cannot answer the question (too dry to measure, too noisy, or not enough captured tail).
     */
    fun estimate(ir: DoubleArray, peak: Int, sampleRate: Int): Double? {
        val start = max(0, peak) + (DIRECT_SKIP_SECONDS * sampleRate).roundToInt()
        val end = minOf(ir.size, peak + (MAX_SECONDS * sampleRate).roundToInt())
        if (end - start < (0.05 * sampleRate).roundToInt()) return null

        // Schroeder backward integration: the energy still to come, from each sample to the end.
        val total = DoubleArray(end - start)
        var run = 0.0
        for (i in end - 1 downTo start) {
            run += ir[i] * ir[i]
            total[i - start] = run
        }
        val reference = total[0]
        if (reference <= 0.0) return null
        val decayDb = DoubleArray(total.size) { 10.0 * log10(max(total[it] / reference, 1e-30)) }

        val plateau = median(decayDb, (0.75 * decayDb.size).toInt(), decayDb.size)
        if (plateau > -25.0 - NOISE_CLEARANCE_DB) return null

        // Least squares over the -5 … -25 dB region.
        var n = 0
        var sumT = 0.0
        var sumY = 0.0
        var sumTT = 0.0
        var sumTY = 0.0
        var firstT = Double.NaN
        var lastT = Double.NaN
        for (i in decayDb.indices) {
            if (decayDb[i] > -5.0 || decayDb[i] < -25.0) continue
            val t = i.toDouble() / sampleRate
            if (n == 0) firstT = t
            lastT = t
            n++
            sumT += t
            sumY += decayDb[i]
            sumTT += t * t
            sumTY += t * decayDb[i]
        }
        if (n < 8 || lastT - firstT < 0.02) return null
        val meanT = sumT / n
        val meanY = sumY / n
        val varT = sumTT / n - meanT * meanT
        if (varT <= 0.0) return null
        val slope = (sumTY / n - meanT * meanY) / varT
        if (slope >= 0.0) return null
        val intercept = meanY - slope * meanT
        var residual = 0.0
        var spread = 0.0
        for (i in decayDb.indices) {
            if (decayDb[i] > -5.0 || decayDb[i] < -25.0) continue
            val t = i.toDouble() / sampleRate
            val d = decayDb[i] - (slope * t + intercept)
            residual += d * d
            spread += (decayDb[i] - meanY) * (decayDb[i] - meanY)
        }
        val rSquared = 1.0 - residual / max(spread, 1e-30)
        if (rSquared < MIN_R_SQUARED) return null
        return -60.0 / slope
    }

    /**
     * How much of a correction a room this reverberant still justifies, as a factor on **boosts** above
     * [transitionHz]. A ringing room already stores energy; adding more low-mid energy there is what makes
     * a boosted calibration sound muddy rather than fuller, and the measurement itself is less certain
     * (reverb fills dips, so a deficit looks smaller than it is). 1.0 for a dry room.
     */
    fun boostTrust(rt60S: Double?): Double = if (rt60S == null || rt60S <= 0.0) 1.0 else 1.0 / (1.0 + rt60S)

    /**
     * Schroeder frequency of the room the reverb was measured in: `2000 · sqrt(T60 / V)`.
     *
     * The app cannot know the volume, and pretending to would be a lie in a different direction, so this
     * assumes the room the product is used in: 40 m³ (a 4 × 4 × 2.5 m living room). A 0.9 s room lands at
     * ~300 Hz, which is where the mode field of a room that size really ends - and the gate is smooth over
     * half an octave on either side, so the assumption only has to be right within a factor of three.
     */
    fun transitionHz(rt60S: Double): Double =
        if (rt60S <= 0.0) Double.POSITIVE_INFINITY else (2000.0 * sqrt(rt60S / ASSUMED_ROOM_VOLUME_M3)).coerceIn(150.0, 600.0)

    /** Volume the transition frequency is based on (m³); see [transitionHz]. */
    const val ASSUMED_ROOM_VOLUME_M3 = 40.0

    private fun median(values: DoubleArray, from: Int, to: Int): Double {
        if (to <= from) return 0.0
        val copy = values.copyOfRange(from, to)
        copy.sort()
        return copy[copy.size / 2]
    }
}
