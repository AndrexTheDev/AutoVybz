package com.andrexthedev.autovybz.measurement.audio

import kotlin.math.log10
import kotlin.math.pow

/**
 * W11: how much to turn the measurement down when the speaker is distorting at the level we picked.
 *
 * The app starts at 70 % of the stream (50 % for device speakers). That is a guess about the speaker,
 * and a small one driven at 70 % can be well into its excursion limit: the THD that
 * [com.andrexthedev.autovybz.measurement.dsp.HarmonicDistortion] already reads off the sweep says so
 * after ten seconds. This object turns that number into a volume step.
 *
 * The step is *derived*, not tabulated, because the relationship is not 1:1: a soft, symmetric
 * nonlinearity is dominated by its cubic term, so THD grows with the square of the level. The probe
 * measured 1.89 dB of THD per dB of drive on the soft-clip model (`pipeline_findings.py` finding 18),
 * which is why a 3 dB step down takes a 4 % speaker to 2 % - and why guessing "turn it down a bit" is
 * usually 6 dB of distortion either side of the target.
 *
 * Two rules the measurements forced:
 *
 * * **Only ever down.** The roadmap proposed raising the level until THD reaches 2 %. Finding 18 shows
 *   the trade is not symmetric: 9 dB quieter measures the same curve to 0.016 dB rms, and the tonal
 *   error at 6 dB SNR is *lower* than in a quiet room. There is nothing to win by getting louder, and a
 *   driver asked to play louder only distorts more.
 * * **A floor.** If the speaker distorts even at the bottom of the useful range, the honest answer is the
 *   [com.andrexthedev.autovybz.measurement.dsp.MeasurementWarning.SPEAKER_DISTORTION] warning, not an
 *   ever-quieter measurement that ends up below the room's noise floor.
 */
object MeasurementLevel {

    /** The level the app wants to measure at: below the 3 % warning line, far below audible damage. */
    const val TARGET_THD_PERCENT = 2.0

    /**
     * Measured slope: dB of THD per dB of level, on the soft-clip model of
     * `pipeline_findings.py` finding 18 (4.01 % -> 2.14 % -> 1.11 % -> 0.57 % over 0 / -3 / -6 / -9 dB).
     * A cubic-dominated nonlinearity gives 2.0; the measured 1.89 is the same thing with the fifth-order
     * term already pulling the far end down, i.e. the slope is slightly *conservative* near the target.
     */
    const val THD_DB_PER_LEVEL_DB = 1.89

    /** One step never exceeds this: beyond 6 dB the speaker is not "a bit hot", it is out of its depth. */
    const val MAX_STEP_DB = 6.0

    /**
     * Below this the step is not worth a second ten-second measurement: at 2.1 % THD we are already at
     * the target and the repeat would only add time.
     */
    const val MIN_STEP_DB = 1.0

    /** Never measure below this fraction of the stream range (the sweep needs ~40 dB of processing gain). */
    const val MIN_FRACTION = 0.25f

    /**
     * How many dB to turn the measurement down for a measured [thdPercent] (`null` = linear, no THD
     * available). 0.0 means "measure as planned".
     */
    fun stepDownDb(thdPercent: Double?): Double {
        if (thdPercent == null || !thdPercent.isFinite() || thdPercent < TARGET_THD_PERCENT) return 0.0
        // THD ~ level^THD_DB_PER_LEVEL_DB/10 in dB terms: solve for the level drop that reaches the target.
        val needed = 20.0 * log10(thdPercent / TARGET_THD_PERCENT) / THD_DB_PER_LEVEL_DB
        if (needed < MIN_STEP_DB) return 0.0
        return needed.coerceAtMost(MAX_STEP_DB)
    }

    /** The stream fraction after turning down by [stepDb] - never below [MIN_FRACTION]. */
    fun fractionAfter(fraction: Float, stepDb: Double): Float {
        if (stepDb <= 0.0) return fraction
        val quieter = (fraction * 10.0.pow(-stepDb / 20.0)).toFloat()
        // `coerceAtMost(fraction)`: a measurement that already starts below the floor stays where it is -
        // this function may only ever lower the level.
        return quieter.coerceAtLeast(MIN_FRACTION).coerceAtMost(fraction)
    }
}
