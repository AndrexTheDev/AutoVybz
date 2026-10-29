package com.andrexthedev.autovybz.measurement.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W11: the level the app measures at, derived from the distortion it just measured.
 *
 * The numbers below are the probe's (`pipeline_findings.py` finding 18), so a change to the slope or the
 * target shows up here as a failing expectation rather than as a quietly different behaviour on a phone.
 */
class MeasurementLevelTest {

    @Test
    fun aQuietSpeakerIsMeasuredAsPlanned() {
        assertEquals(0.0, MeasurementLevel.stepDownDb(null), 0.0)
        assertEquals(0.0, MeasurementLevel.stepDownDb(0.0), 0.0)
        assertEquals(0.0, MeasurementLevel.stepDownDb(1.9), 0.0)
        assertEquals(0.0, MeasurementLevel.stepDownDb(Double.NaN), 0.0)
    }

    @Test
    fun theStepIsDerivedFromTheMeasuredDistortion() {
        // 4.01 % at the reference level is the probe's soft-clip model: 20*log10(4.01/2)/1.89 = 3.20 dB,
        // and the probe measured -3 dB landing at 2.14 % - i.e. the derived step is right to a tenth.
        assertEquals(3.20, MeasurementLevel.stepDownDb(4.01), 0.05)
        assertEquals(4.65, MeasurementLevel.stepDownDb(5.5), 0.05)
        // Never more than one bounded step: 11.9 % THD is "out of its depth", not "turn it down 8 dB".
        assertEquals(MeasurementLevel.MAX_STEP_DB, MeasurementLevel.stepDownDb(11.9), 0.0)
        assertTrue(MeasurementLevel.stepDownDb(30.0) <= MeasurementLevel.MAX_STEP_DB)
    }

    @Test
    fun aStepTooSmallToHearIsNotWorthASecondMeasurement() {
        // 2.1 % is the target plus a rounding error; measuring ten more seconds would buy nothing.
        assertEquals(0.0, MeasurementLevel.stepDownDb(2.1), 0.0)
        // 2.5 % -> 20*log10(1.25)/1.89 = 1.03 dB, just above the threshold.
        assertTrue(MeasurementLevel.stepDownDb(2.5) >= MeasurementLevel.MIN_STEP_DB)
    }

    @Test
    fun theFractionFollowsTheStepAndKeepsAFloor() {
        // 70 % of the stream, 3.2 dB down -> 0.484 (a factor of 10^(-3.2/20) = 0.69).
        assertEquals(0.484, MeasurementLevel.fractionAfter(0.70f, 3.2).toDouble(), 0.01)
        assertEquals(0.70, MeasurementLevel.fractionAfter(0.70f, 0.0).toDouble(), 1e-6)
        // Below the floor it stays at the floor, and it may never *raise* the level.
        assertEquals(MeasurementLevel.MIN_FRACTION.toDouble(),
            MeasurementLevel.fractionAfter(0.30f, 6.0).toDouble(), 1e-6)
        assertEquals(0.20, MeasurementLevel.fractionAfter(0.20f, 6.0).toDouble(), 1e-6)
    }

    @Test
    fun aDistortingSpeakerEndsBelowTheWarningLine() {
        // The one promise that matters: after the step the model is under the 3 % SPEAKER_DISTORTION gate.
        var thd = 4.01
        val step = MeasurementLevel.stepDownDb(thd)
        thd *= Math.pow(10.0, -step * MeasurementLevel.THD_DB_PER_LEVEL_DB / 20.0)
        assertTrue("THD after the step: $thd %", thd < 3.0)
    }
}
