package com.andrexthedev.autovybz.measurement.eq

import com.andrexthedev.autovybz.measurement.dsp.FrequencyGrid
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Static pre-gain policy of a finished correction: **clipping safety first, perceived loudness second.**
 *
 * ## The problem this solves
 *
 * The classic rule ([Plan.clippingSafeDb]) lowers the master level by the largest boost of the filter
 * chain. That is the right rule for a *boost* - but it silently punished every correction that only
 * *cuts*:
 *
 * * A phone speaker measured with its own microphone shows a broad presence excess (1-4 kHz) that the
 *   target does not want. Correcting it is a cut of several dB in the band the ear is most sensitive
 *   to - the same music then plays *much* quieter, and because quiet = less detail at both ends of the
 *   spectrum (equal-loudness contours), the calibration "takes the details away".
 * * Together with the old pre-gain (which never *raises* anything) the total loss added up: a typical
 *   device-speaker correction cost 4-8 dB of perceived level.
 *
 * ## The policy
 *
 * 1. **Clipping-safe value** ([Plan.clippingSafeDb], the legacy rule): `-(peak + headroom)` if the
 *    correction boosts anywhere, else 0 dB.
 * 2. **Loudness compensation** ([Plan.makeUpDb]): the correction's estimated change of perceived level
 *    ([loudnessDeltaDb]) is undone - but only *upwards* ([MAX_MAKE_UP_DB] at most), so a correction that
 *    makes the sound louder is never pulled back down.
 * 3. **Headroom allowance** (`headroomAllowanceDb`): the make-up may go *this far above the static bound*
 *    when the DynamicProcessing limiter is armed ([AntiClipping.DEFAULT_LIMITER]: -2 dB threshold, 20:1,
 *    0.2 ms attack - AutoVybz arms it for device / rescue / outdoor presets). Without a limiter it is 0.
 *
 * The allowance is a *measured* number, not a guess. The previous 6 dB assumed the limiter would absorb a
 * 6 dB static overshoot; the live probe (tools/probe/live_probe.py, real programme through the modelled
 * device) showed the opposite: a 1 ms attack cannot catch a percussive transient, so the output peaked at
 * **+0.14 dBFS with 13 clipped samples**. With -2 dB / 20:1 / 0.2 ms and a 4 dB allowance every tested
 * case stayed at or below **-1.42 dBFS with zero clipped samples** while remaining loudness-neutral.
 *
 * The static bound itself (`-(peak + headroom)`, always applied - even when the correction only cuts)
 * is what keeps a full-scale input safe; the allowance only buys loudness that the limiter then has to
 * hold. A correction that only cuts therefore never loses level: its own attenuation *is* the headroom.
 *
 * The perceived level is estimated on the *predicted output* (`measured + correction`, i.e. what the
 * speaker will actually radiate) with an A-weighted pink programme spectrum on a 1/3-octave grid
 * 40 Hz-16 kHz:
 *
 * ```
 * ΔL = 10·log10( Σ w(f)·10^(predicted(f)/10) / Σ w(f) )  -  same for the measurement alone
 * w(f) = 10^((A_weighting(f) + programme_tilt(f)) / 10),  programme_tilt = -3 dB/octave
 * ```
 *
 * A constant level offset cancels out of ΔL, so the estimate is level-independent (the EQ never depends
 * on the recording level). Pure Kotlin, no Android dependencies - unit-tested by [PreampPlannerTest].
 */
object PreampPlanner {

    /** Upper bound of the loudness make-up (a wrong, huge correction must not turn into a huge gain). */
    const val MAX_MAKE_UP_DB = 6.0

    /**
     * Overshoot the *static* pre-gain may leave when the DynamicProcessing limiter is armed. Measured on
     * real programme material (see the class doc): 4 dB is what -2 dB / 20:1 / 0.2 ms absorbs with zero
     * clipped samples and >= 1.4 dB of remaining peak margin.
     */
    const val LIMITER_HEADROOM_ALLOWANCE_DB = 4.0

    /** Floor for pathological corrections (never mute the phone). */
    const val MIN_PREAMP_DB = -24.0

    /** Loudness model grid: 1/3 octave, 40 Hz - 16 kHz (below/above: no programme energy that matters). */
    private val loudnessGrid = FrequencyGrid.log(40.0, 16_000.0, 3)

    /** Per-grid-point loudness weight (A-weighting × pink programme tilt), precomputed. */
    private val loudnessWeights = DoubleArray(loudnessGrid.size) { i ->
        10.0.pow((aWeightingDb(loudnessGrid[i]) + programmeTiltDb(loudnessGrid[i])) / 10.0)
    }

    data class Plan(
        /** Pre-gain written to the engine / preset (<= 0 only when the correction really boosts). */
        val preampDb: Double,
        /** Part of [preampDb] above the clipping-safe value: loudness compensation (>= 0). */
        val makeUpDb: Double,
        /** Estimated perceived level change of the correction alone: negative = quieter. */
        val loudnessDeltaDb: Double,
        /** Largest gain of the correction before the pre-gain, dB. */
        val peakDb: Double,
        /** The classic anti-clipping pre-gain for this correction (<= 0). */
        val clippingSafeDb: Double,
    )

    /**
     * @param frequencies ascending log grid shared by [measuredDb] and [correctionDb].
     * @param measuredDb the (normalised) measurement - the reference for the loudness estimate.
     * @param correctionDb the combined filter response of the finished correction.
     * @param peakDb largest gain the filter chain reaches anywhere (20 Hz-20 kHz). Defaults to the
     *   maximum of [correctionDb], which is only as fine as the grid; callers pass
     *   [AntiClipping.maxBoostDb] so a narrow peak between two grid points cannot slip through.
     */
    fun plan(
        frequencies: DoubleArray,
        measuredDb: DoubleArray,
        correctionDb: DoubleArray,
        headroomDb: Double = 0.5,
        maxMakeUpDb: Double = MAX_MAKE_UP_DB,
        headroomAllowanceDb: Double = 0.0,
        loudnessMakeUp: Boolean = true,
        peakDb: Double = correctionDb.maxOrNull() ?: 0.0,
    ): Plan {
        require(frequencies.size == measuredDb.size && frequencies.size == correctionDb.size) {
            "grid mismatch: ${frequencies.size}/${measuredDb.size}/${correctionDb.size}"
        }
        val peak = peakDb
        // Reported "legacy" value: what a boost-only view of the correction would demand.
        val clippingSafe = roundDown(if (peak > 0.0) -(peak + headroomDb) else 0.0)
        // Hard static bound for a full-scale input. Unlike [clippingSafe] it *credits* a correction that
        // only cuts: a 3 dB cut really does leave 3 dB of headroom, and that is where a cut-heavy device
        // correction gets its loudness back instead of borrowing it from the limiter.
        val ceiling = roundDown(-(peak + headroomDb))

        val delta = if (loudnessMakeUp && frequencies.size > 8) {
            loudnessDeltaDb(frequencies, measuredDb, correctionDb)
        } else 0.0

        val allowance = headroomAllowanceDb.coerceAtLeast(0.0)
        val preamp = if (loudnessMakeUp && allowance > 0.0) {
            // Limiter armed: the pre-gain is loudness-neutral (the corrected chain is as loud as the
            // original), bounded below by the clipping-safe value and above by what the limiter can
            // absorb. The static overshoot (peak + preamp) never exceeds the allowance.
            val upper = ceiling + minOf(allowance, maxMakeUpDb.coerceAtLeast(0.0))
            roundDown(minOf(maxOf(-delta, clippingSafe), upper).coerceAtLeast(MIN_PREAMP_DB))
        } else {
            // No limiter (or make-up switched off): the static pre-gain is the last line of defence and
            // must not be traded for loudness - exactly the legacy value.
            clippingSafe
        }
        return Plan(
            preampDb = preamp,
            makeUpDb = (preamp - clippingSafe).coerceAtLeast(0.0),
            loudnessDeltaDb = delta,
            peakDb = peak,
            clippingSafeDb = clippingSafe,
        )
    }

    /** Perceived level change of applying [correctionDb] on top of [measuredDb] (dB, negative = quieter). */
    fun loudnessDeltaDb(frequencies: DoubleArray, measuredDb: DoubleArray, correctionDb: DoubleArray): Double {
        val predicted = DoubleArray(measuredDb.size) { measuredDb[it] + correctionDb[it] }
        return loudnessDb(frequencies, predicted) - loudnessDb(frequencies, measuredDb)
    }

    /** A-weighted power average of a response (dB) over the loudness grid; level-independent by construction. */
    fun loudnessDb(frequencies: DoubleArray, responseDb: DoubleArray): Double {
        val r = FrequencyGrid.resample(frequencies, responseDb, loudnessGrid)
        var num = 0.0
        var den = 0.0
        for (i in loudnessGrid.indices) {
            num += loudnessWeights[i] * 10.0.pow(r[i] / 10.0)
            den += loudnessWeights[i]
        }
        return if (den <= 0.0) 0.0 else 10.0 * log10(num / den)
    }

    /**
     * IEC 61672 A-weighting in dB (0 dB at 1 kHz, -34.5 dB at 40 Hz and -2.5 dB at 10 kHz). The ear's
     * sensitivity is what makes a presence-band cut cost so much loudness - exactly the effect the
     * compensation has to model.
     */
    fun aWeightingDb(f: Double): Double {
        if (f <= 0.0 || !f.isFinite()) return -200.0
        val f2 = f * f
        val numerator = 12194.0 * 12194.0 * f2 * f2
        val denominator = (f2 + 20.6 * 20.6) *
            sqrt((f2 + 107.7 * 107.7) * (f2 + 737.9 * 737.9)) *
            (f2 + 12194.0 * 12194.0)
        return 20.0 * log10(numerator / denominator) + 2.0
    }

    /** Music / speech programme spectrum approximation: -3 dB per octave around 1 kHz (pink-ish). */
    private fun programmeTiltDb(f: Double): Double = -3.0 * kotlin.math.ln(f / 1000.0) / kotlin.math.ln(2.0)

    /** Round *down* to 0.1 dB: the written gain is never larger than the planned one. */
    private fun roundDown(db: Double): Double =
        if (!db.isFinite()) 0.0 else kotlin.math.floor(db * 10.0 + 1e-9) / 10.0
}
