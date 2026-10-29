package com.andrexthedev.autovybz.measurement

import com.andrexthedev.autovybz.measurement.dsp.FrequencyGrid
import com.andrexthedev.autovybz.measurement.dsp.MeasurementResult
import com.andrexthedev.autovybz.measurement.eq.AutoEqRenderer
import com.andrexthedev.autovybz.measurement.eq.AutoEqResult
import com.andrexthedev.autovybz.measurement.eq.AutoEqSettings
import com.andrexthedev.autovybz.measurement.eq.TargetCurve
import com.andrexthedev.autovybz.measurement.eq.TargetType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log10

/**
 * The verification pass is the only feature that can turn "trust us" into "here is your speaker, before and
 * after" - so the thing that must be tested is not that it produces a nice sentence, but that it *fails*
 * exactly when the chain lied: no correction applied, half the correction applied, or something entirely
 * different happened.
 */
class VerificationTest {

    private val grid = FrequencyGrid.log()

    private fun measurement(curve: (Double) -> Double): MeasurementResult {
        val db = DoubleArray(grid.size) { curve(grid[it]) }
        val norm = FrequencyGrid.bandMean(grid, db, 300.0, 3000.0)
        val n = DoubleArray(grid.size) { db[it] - norm }
        return MeasurementResult(
            sampleRate = 48000, frequencies = grid, rawDb = n.copyOf(), smoothedDb = n.copyOf(),
            thirdOctaveDb = n.copyOf(), sweepSmoothedDb = n.copyOf(), pinkSmoothedDb = n.copyOf(),
            snrDb = DoubleArray(grid.size) { 30.0 }, latencyMs = 20.0, inputPeak = 0.3,
            medianSnrDb = 30.0, warnings = emptyList(), normalizationDb = -40.0,
        )
    }

    private fun bump(f: Double, centre: Double, gainDb: Double, widthOct: Double): Double {
        val x = Math.log(f / centre) / Math.log(2.0) / widthOct
        return gainDb * Math.exp(-x * x)
    }

    /** A small box: boomy at 80 Hz, shouty at 1.6 kHz, rolled off above 12 kHz. */
    private fun speaker(f: Double) =
        bump(f, 80.0, 8.0, 0.5) + bump(f, 1600.0, 5.0, 0.6) + bump(f, 9000.0, -8.0, 0.8) - 0.5 * log10(f / 1000.0)

    private fun plan(before: MeasurementResult): AutoEqResult = AutoEqRenderer.render(
        before.frequencies, before.smoothedDb, TargetCurve(TargetType.SMART),
        AutoEqSettings(bandCount = 10, maxBoostDb = 6.0, highLimitHz = 16000.0),
        rollOffDb = before.thirdOctaveDb,
    )

    /** A measurement whose curve is [before] plus [response] (scaled), re-normalised like the analyzer does. */
    private fun shifted(before: MeasurementResult, response: DoubleArray, scale: Double = 1.0): MeasurementResult {
        val db = DoubleArray(grid.size) { before.smoothedDb[it] + scale * response[it] }
        val norm = FrequencyGrid.bandMean(grid, db, 300.0, 3000.0)
        val n = DoubleArray(grid.size) { db[it] - norm }
        return MeasurementResult(
            sampleRate = 48000, frequencies = grid, rawDb = n.copyOf(), smoothedDb = n.copyOf(),
            thirdOctaveDb = n.copyOf(), sweepSmoothedDb = n.copyOf(), pinkSmoothedDb = n.copyOf(),
            snrDb = DoubleArray(grid.size) { 30.0 }, latencyMs = 20.0, inputPeak = 0.3,
            medianSnrDb = 30.0, warnings = emptyList(), normalizationDb = -40.0,
        )
    }

    /** The "after" measurement of a chain where the plan was applied exactly as predicted. */
    private fun applied(before: MeasurementResult, plan: AutoEqResult): MeasurementResult =
        shifted(before, plan.filterResponseDb)

    @Test
    fun aChainThatDoesWhatThePlanSaidVerifiesAsPredicted() {
        val before = measurement(::speaker)
        val plan = plan(before)
        val report = Verification.compare(before, applied(before, plan), plan)
        assertEquals(Verification.Verdict.AS_PREDICTED, report.verdict)
        assertFalse(report.isUnexpected)
        assertTrue("prediction error ${report.predictionErrorDb}", report.predictionErrorDb < Verification.PREDICTION_TOLERANCE_DB)
        assertTrue("correlation ${report.correlation}", report.correlation > 0.9)
        assertTrue("the plan promised an improvement", report.predictedImprovementDb > 0.5)
        assertEquals(report.predictedImprovementDb, report.measuredImprovementDb, 1.5)
    }

    /** The classic field failure: the user changed the volume by 4 dB between the two runs. */
    @Test
    fun aLevelChangeBetweenTheRunsDoesNotMatter() {
        val before = measurement(::speaker)
        val plan = plan(before)
        val after = applied(before, plan)
        val quieter = after.copyWith(4.0)
        val report = Verification.compare(before, quieter, plan)
        assertEquals(Verification.Verdict.AS_PREDICTED, report.verdict)
    }

    /** EQ not applied at all (wrong route, engine off, preset not saved). */
    @Test
    fun anUnappliedCorrectionIsNotVerifiedAsPredicted() {
        val before = measurement(::speaker)
        val plan = plan(before)
        val report = Verification.compare(before, measurement(::speaker), plan)
        assertTrue("verdict ${report.verdict}", report.verdict != Verification.Verdict.AS_PREDICTED)
        assertTrue("prediction error ${report.predictionErrorDb}", report.predictionErrorDb > Verification.PREDICTION_TOLERANCE_DB)
    }

    /** Half the correction arrived (a limiter clamped it, or the engine dropped bands). */
    @Test
    fun aHalfAppliedCorrectionIsWeakerThanPredicted() {
        val before = measurement(::speaker)
        val plan = plan(before)
        val half = shifted(before, plan.filterResponseDb, scale = 0.45)
        val report = Verification.compare(before, half, plan)
        assertEquals(Verification.Verdict.WEAKER_THAN_PREDICTED, report.verdict)
        assertTrue("correlation ${report.correlation}", report.correlation > 0.5)
    }

    /** Something else changed completely - the shape has nothing to do with the plan. */
    @Test
    fun anUnrelatedChangeIsUnexpected() {
        val before = measurement(::speaker)
        val plan = plan(before)
        val unrelated = shifted(before, DoubleArray(grid.size) { i ->
            bump(grid[i], 300.0, -12.0, 0.7) - bump(grid[i], 4000.0, 6.0, 0.5)
        })
        val report = Verification.compare(before, unrelated, plan)
        assertEquals(Verification.Verdict.UNEXPECTED, report.verdict)
        assertTrue(report.isUnexpected)
    }

    @Test
    fun aFlatPlanVerifiesTrivially() {
        // A speaker that already matches the target: nothing to do, nothing expected, nothing measured.
        val flat = measurement { 0.0 }
        val plan = plan(flat)
        val report = Verification.compare(flat, measurement { 0.0 }, plan)
        assertTrue("verdict ${report.verdict}", report.verdict == Verification.Verdict.AS_PREDICTED)
    }

    @Test
    fun mismatchedGridsAreRejected() {
        val before = measurement(::speaker)
        val plan = plan(before)
        val other = FrequencyGrid.log(pointsPerOctave = 24)
        val after = MeasurementResult(48000, other, DoubleArray(other.size), DoubleArray(other.size),
            DoubleArray(other.size), DoubleArray(other.size), DoubleArray(other.size), DoubleArray(other.size),
            0.0, 0.1, 20.0, emptyList(), 0.0)
        try {
            Verification.compare(before, after, plan)
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    /**
     * What the wizard's confirmation card is allowed to call a success. A review of the flow found the
     * obvious bug this pins: `WEAKER` *is* an improvement (half the correction arrived) but it is not a
     * confirmation, and `NO_CHANGE`/`UNEXPECTED` are exactly the cases the card exists for.
     */
    @Test
    fun onlyAsPredictedAndBetterCountAsConfirmation() {
        val before = measurement(::speaker)
        val plan = plan(before)
        val applied = Verification.compare(before, applied(before, plan), plan)
        val half = Verification.compare(before, shifted(before, plan.filterResponseDb, scale = 0.45), plan)
        val nothing = Verification.compare(before, measurement(::speaker), plan)
        val other = Verification.compare(before, shifted(before, DoubleArray(grid.size) { i ->
            bump(grid[i], 300.0, -12.0, 0.7) - bump(grid[i], 4000.0, 6.0, 0.5)
        }), plan)
        assertTrue(applied.isConfirmation)
        assertFalse(half.isConfirmation)
        assertFalse(nothing.isConfirmation)
        assertFalse(other.isConfirmation)
        assertTrue(other.isUnexpected)
        assertFalse(applied.isUnexpected)
    }

    /** The sentence the card shows: two numbers, no verdict words - the verdict is a separate line. */
    @Test
    fun theSummaryNamesBothImprovements() {
        val before = measurement(::speaker)
        val plan = plan(before)
        val report = Verification.compare(before, applied(before, plan), plan)
        val summary = report.summary
        assertTrue(summary, summary.contains("%.1f".format(report.measuredImprovementDb)))
        assertTrue(summary, summary.contains("%.1f".format(report.predictedImprovementDb)))
    }

    /** The correlation helper itself, including its degenerate cases. */
    @Test
    fun correlationIsAPearsonCoefficient() {
        val a = doubleArrayOf(1.0, 2.0, 3.0, 4.0)
        assertEquals(1.0, Verification.correlation(a, a), 1e-9)
        assertEquals(-1.0, Verification.correlation(a, DoubleArray(4) { -a[it] }), 1e-9)
        assertEquals(0.0, Verification.correlation(a, doubleArrayOf(1.0, 1.0, 1.0, 1.0)), 1e-9)
        assertTrue(abs(Verification.correlation(a, doubleArrayOf(4.0, 3.0, 2.0, 1.0))) > 0.99)
    }

    /** A MeasurementResult shifted by a constant - "the user turned the volume down". */
    private fun MeasurementResult.copyWith(offsetDb: Double) = MeasurementResult(
        sampleRate, frequencies, rawDb, DoubleArray(smoothedDb.size) { smoothedDb[it] + offsetDb },
        thirdOctaveDb, sweepSmoothedDb, pinkSmoothedDb, snrDb, latencyMs, inputPeak, medianSnrDb, warnings,
        normalizationDb,
    )
}
