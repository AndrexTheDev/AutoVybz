package com.andrexthedev.autovybz.measurement

import com.andrexthedev.autovybz.measurement.dsp.FrequencyGrid
import com.andrexthedev.autovybz.measurement.dsp.MeasurementResult
import com.andrexthedev.autovybz.measurement.eq.AutoEqResult
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Verification pass (roadmap W6): measures the *result* instead of promising it.
 *
 * The app predicts what its correction will do - "here is your speaker before, and here is what it will
 * look like after" - and until now nobody ever checked. This compares three curves that all exist after a
 * second measurement run through the active EQ:
 *
 * * **measured** – `after − before`, both normalised to 0 dB at 300 Hz–3 kHz the way every measurement is;
 * * **predicted** – the plan's own filter response, normalised the same way (the pre-gain is a pure level
 *   change, so it disappears in the normalisation - which is exactly right for a *shape* comparison);
 * * **residual** – what is left of the target, before and after.
 *
 * The comparison is level-invariant on purpose: a user who turns the volume down between the two runs
 * invalidates nothing, because both curves are normalised (and the measured difference is aligned in the
 * same band again, so an offset that survived the analyzer cannot pass for a tonal change). What it *does* catch is everything that broke
 * the chain in between: the EQ never got applied, the route switched to another speaker, a limiter ate the
 * correction, the speaker rattles at measurement level. In those cases the measured change is not the
 * predicted one, and [Report.verdict] says so.
 *
 * This is deliberately *not* an auto-retry: a verification run that disagrees with itself is information
 * for the user ("your speaker does not do what we predicted - here are both curves"), and the honest
 * response is to show it, not to silently re-fit until the numbers look better.
 */
object Verification {

    /** Above this RMS difference between measured and predicted change something else is going on. */
    const val PREDICTION_TOLERANCE_DB = 1.5

    /** Below this the correction did not measurably change anything (the honest "it did nothing"). */
    const val MIN_AUDIBLE_CHANGE_DB = 0.5

    /** Below this shape correlation the measured change is not the predicted one, whatever its size. */
    const val MIN_CORRELATION = 0.5

    /** Both curves are normalised in this band, so it cancels in every difference. */
    const val NORMALISATION_LO_HZ = 300.0
    const val NORMALISATION_HI_HZ = 3000.0

    /** The verification judges the shape between these fractions of the correction range. */
    const val JUDGE_LOW_FACTOR = 1.41 // +0.5 octave above the bass limit: where the correction has authority

    enum class Verdict {
        /** Measured change is the predicted one within [PREDICTION_TOLERANCE_DB]. */
        AS_PREDICTED,

        /** The measured change is larger (or the residual smaller) than predicted - the plan was conservative. */
        BETTER_THAN_PREDICTED,

        /** The change is predicted, but smaller: something in the chain ate part of it. */
        WEAKER_THAN_PREDICTED,

        /** Measured and predicted have nothing to do with each other: wrong route, EQ off, other speaker. */
        UNEXPECTED,

        /** The correction did not change anything measurable at all. */
        NO_CHANGE,
    }

    data class Report(
        /** Pearson correlation of measured and predicted shape over the judged band (−1…1). */
        val correlation: Double,
        /** RMS of the measured shape change over the judged band (dB). */
        val measuredChangeDb: Double,
        /** RMS of the predicted shape change over the same band (dB). */
        val predictedChangeDb: Double,
        /** RMS of `measured − predicted` (dB): the number the verdict is based on. */
        val predictionErrorDb: Double,
        /** Residual deviation from the target before / after / as predicted (dB RMS). */
        val residualBeforeDb: Double,
        val residualAfterDb: Double,
        val residualPredictedDb: Double,
        /** `residualBefore − residualAfter`: positive means the verification heard an improvement. */
        val measuredImprovementDb: Double,
        /** `residualBefore − residualPredicted`: what the plan promised. */
        val predictedImprovementDb: Double,
        /** Lowest / highest frequency that entered the judgement. */
        val judgeLowHz: Double,
        val judgeHighHz: Double,
        val verdict: Verdict,
    ) {
        /** Sentence-ready summary, e.g. "measured 2.1 dB better, predicted 2.3 dB". */
        val summary: String
            get() = "measured %.1f dB better, predicted %.1f dB".format(measuredImprovementDb, predictedImprovementDb)

        /**
         * One English line for the exports (APO comment, REW header, internal JSON): exports are technical
         * files that keep the English "Preamp:"/"Filter 1:" vocabulary, so the check stays English there too
         * - the UI (wizard card, share card) is what gets translated.
         */
        val exportLine: String
            get() = "Check: " + when (verdict) {
                Verdict.AS_PREDICTED -> "as predicted"
                Verdict.BETTER_THAN_PREDICTED -> "better than predicted"
                Verdict.WEAKER_THAN_PREDICTED -> "weaker than predicted - part of the correction did not arrive"
                Verdict.NO_CHANGE -> "no measurable change - the correction is not reaching the speaker"
                Verdict.UNEXPECTED -> "unexpected change - not the predicted correction"
            } + java.lang.String.format(
                java.util.Locale.US,
                ", measured %+.1f dB, predicted %+.1f dB (error %.1f dB, %d-%d Hz)",
                measuredImprovementDb, predictedImprovementDb, predictionErrorDb,
                judgeLowHz.toInt(), judgeHighHz.toInt(),
            )

        /** True when the wizard should show a warning rather than a confirmation. */
        val isUnexpected: Boolean get() = verdict == Verdict.UNEXPECTED

        /**
         * True when the run confirmed the plan: it did what it said, or more. Everything else is a finding
         * the user has to see - `NO_CHANGE` and `UNEXPECTED` most of all, because they mean the correction
         * is not reaching the speaker.
         */
        val isConfirmation: Boolean
            get() = verdict == Verdict.AS_PREDICTED || verdict == Verdict.BETTER_THAN_PREDICTED
    }

    /** Pearson correlation of two equally long series; 0 when either has no variance. */
    internal fun correlation(a: DoubleArray, b: DoubleArray): Double {
        require(a.size == b.size && a.isNotEmpty())
        var meanA = 0.0
        var meanB = 0.0
        for (i in a.indices) { meanA += a[i]; meanB += b[i] }
        meanA /= a.size
        meanB /= b.size
        var cov = 0.0
        var varA = 0.0
        var varB = 0.0
        for (i in a.indices) {
            val da = a[i] - meanA
            val db = b[i] - meanB
            cov += da * db
            varA += da * da
            varB += db * db
        }
        return if (varA <= 1e-12 || varB <= 1e-12) 0.0 else cov / sqrt(varA * varB)
    }

    /**
     * Compares a verification measurement ([after], taken with the correction active) with the original
     * [before] measurement and the plan [autoEq] that was applied in between.
     */
    fun compare(before: MeasurementResult, after: MeasurementResult, autoEq: AutoEqResult): Report {
        val f = before.frequencies
        require(after.frequencies.size == f.size) { "measurements use different frequency grids" }

        val predicted = DoubleArray(f.size) { autoEq.filterResponseDb[it] }
        val predictedNorm = FrequencyGrid.bandMean(f, predicted, NORMALISATION_LO_HZ, NORMALISATION_HI_HZ)
        val predictedShape = DoubleArray(f.size) { predicted[it] - predictedNorm }
        // The analyzer already normalises both measurements to 0 dB in the same band, so their difference
        // is a shape change. It is aligned once more anyway: a level offset that survived (a different
        // capture gain, a run taken at another volume) must not be able to look like a tonal change.
        val measuredRaw = DoubleArray(f.size) { after.smoothedDb[it] - before.smoothedDb[it] }
        val measuredOffset = FrequencyGrid.bandMean(f, measuredRaw, NORMALISATION_LO_HZ, NORMALISATION_HI_HZ)
        val measuredShape = DoubleArray(f.size) { measuredRaw[it] - measuredOffset }

        val lo = max(autoEq.lowLimitHz * JUDGE_LOW_FACTOR, NORMALISATION_LO_HZ / 2.0)
        val hi = autoEq.highLimitHz
        val judge = f.indices.filter { f[it] in lo..hi }
        require(judge.isNotEmpty()) { "empty judgement band" }

        fun rms(values: DoubleArray): Double {
            var sum = 0.0
            for (i in judge) sum += values[i] * values[i]
            return sqrt(sum / judge.size)
        }

        val error = DoubleArray(f.size) { measuredShape[it] - predictedShape[it] }
        val predictionError = rms(error)
        val measuredChange = rms(measuredShape)
        val predictedChange = rms(predictedShape)

        // Residuals against the plan's own target curve, level-aligned in the judged band (a constant
        // offset is volume, not tonal balance - the same convention the probe's judge uses).
        val target = autoEq.targetDb
        val beforeError = DoubleArray(f.size) { before.smoothedDb[it] - target[it] }
        val afterError = DoubleArray(f.size) { after.smoothedDb[it] - target[it] }
        val predictedError = DoubleArray(f.size) { autoEq.predictedDb[it] - target[it] }
        fun alignedResidual(values: DoubleArray): Double {
            var offset = 0.0
            for (i in judge) offset += values[i]
            offset /= judge.size
            var sum = 0.0
            for (i in judge) {
                val d = values[i] - offset
                sum += d * d
            }
            return sqrt(sum / judge.size)
        }
        val residualBefore = alignedResidual(beforeError)
        val residualAfter = alignedResidual(afterError)
        val residualPredicted = alignedResidual(predictedError)

        // Shape correlation: does the change we measured *look like* the plan at all? Magnitude alone
        // cannot tell "the EQ was never applied" (change ≈ 0, caught by NO_CHANGE) from "something else
        // changed and it was not our correction" (a large change with no relation to the plan).
        val correlation = correlation(
            DoubleArray(judge.size) { measuredShape[judge[it]] },
            DoubleArray(judge.size) { predictedShape[judge[it]] },
        )
        val verdict = when {
            // Nothing to do and nothing done: a flat correction verifies trivially.
            predictedChange < MIN_AUDIBLE_CHANGE_DB && measuredChange < MIN_AUDIBLE_CHANGE_DB -> Verdict.AS_PREDICTED
            measuredChange < MIN_AUDIBLE_CHANGE_DB -> Verdict.NO_CHANGE
            predictionError <= PREDICTION_TOLERANCE_DB -> Verdict.AS_PREDICTED
            correlation < MIN_CORRELATION -> Verdict.UNEXPECTED
            measuredChange < predictedChange -> Verdict.WEAKER_THAN_PREDICTED
            else -> Verdict.BETTER_THAN_PREDICTED
        }

        return Report(
            correlation = correlation,
            measuredChangeDb = measuredChange,
            predictedChangeDb = predictedChange,
            predictionErrorDb = predictionError,
            residualBeforeDb = residualBefore,
            residualAfterDb = residualAfter,
            residualPredictedDb = residualPredicted,
            measuredImprovementDb = residualBefore - residualAfter,
            predictedImprovementDb = residualBefore - residualPredicted,
            judgeLowHz = f[judge.first()],
            judgeHighHz = f[judge.last()],
            verdict = verdict,
        )
    }
}
