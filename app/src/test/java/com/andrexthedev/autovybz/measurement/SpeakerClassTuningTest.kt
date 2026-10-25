package com.andrexthedev.autovybz.measurement

import com.andrexthedev.autovybz.measurement.audio.VolumeMath
import com.andrexthedev.autovybz.measurement.dsp.SmoothingProfile
import com.andrexthedev.autovybz.measurement.eq.AutoEqSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The speaker-class strategies must leave the classic external-speaker path untouched. */
class SpeakerClassTuningTest {

    private val standard = SpeakerClassTuning.of(SpeakerClass.STANDARD)
    private val rescue = SpeakerClassTuning.of(SpeakerClass.RESCUE)
    private val device = SpeakerClassTuning.of(SpeakerClass.DEVICE)

    @Test
    fun standardClassChangesNothing() {
        assertEquals(SpeakerClassTuning(), standard)
        for (env in ListeningEnvironment.values()) {
            val tuning = EnvironmentTuning.of(env, SmoothingProfile.DYNAMIC, 6.0)
            assertEquals(
                AutoEqSettings(
                    bandCount = 10,
                    maxBoostDb = tuning.maxBoostDb,
                    broadBoostOctaves = tuning.broadBoostOctaves,
                    excursionGuardOctaves = tuning.excursionGuardOctaves,
                ),
                CorrectionPlanner.autoEqSettings(10, tuning, standard),
            )
            assertEquals(tuning.forceLimiter, CorrectionPlanner.forceLimiter(tuning, standard))
            assertEquals(tuning.smoothing, CorrectionPlanner.smoothing(SmoothingProfile.DYNAMIC, tuning, standard))
        }
    }

    @Test
    fun rescueCutsDeeperAndProtects() {
        val indoor = EnvironmentTuning.of(ListeningEnvironment.INDOOR)
        val s = CorrectionPlanner.autoEqSettings(10, indoor, rescue)
        assertEquals(16.0, s.maxCutDb, 0.0)
        assertEquals(6.0, s.maxBoostDb, 0.0) // user boost limit untouched
        assertEquals(1.0, s.broadBoostOctaves)
        assertEquals(1.5, s.excursionGuardOctaves, 0.0)
        assertEquals(400.0, s.lowLimitScanHz, 0.0)
        assertEquals(350.0, s.lowLimitMaxHz, 0.0)
        assertTrue(s.protectiveHighPass)
        assertTrue(CorrectionPlanner.forceLimiter(indoor, rescue))
        assertEquals(SmoothingProfile.STRONG, CorrectionPlanner.smoothing(SmoothingProfile.DYNAMIC, indoor, rescue))
        // An explicit Pro smoothing choice is respected (same rule as outdoors).
        val fine = EnvironmentTuning.of(ListeningEnvironment.INDOOR, SmoothingProfile.FINE)
        assertEquals(SmoothingProfile.FINE, CorrectionPlanner.smoothing(SmoothingProfile.FINE, fine, rescue))
        assertFalse(rescue.singlePosition)
    }

    @Test
    fun deviceClassIsBandLimitedGentleAndProtected() {
        val indoor = EnvironmentTuning.of(ListeningEnvironment.INDOOR, SmoothingProfile.DYNAMIC, 9.0)
        val s = CorrectionPlanner.autoEqSettings(10, indoor, device)
        assertEquals(4.0, s.maxBoostDb, 0.0) // capped despite the 9 dB user setting
        assertEquals(12000.0, s.highLimitHz, 0.0)
        assertEquals(2.0, s.excursionGuardOctaves, 0.0)
        assertEquals(1200.0, s.lowLimitScanHz, 0.0)
        assertEquals(900.0, s.lowLimitMaxHz, 0.0)
        assertTrue(s.protectiveHighPass)
        assertTrue(CorrectionPlanner.forceLimiter(indoor, device))
        assertTrue(device.singlePosition)
        assertTrue(device.forcePhoneMic)
        assertEquals(VolumeMath.DEVICE_MEASUREMENT_FRACTION, device.measurementVolumeFraction)
    }

    @Test
    fun requestDefaultsToStandardClass() {
        val r = MeasurementRequest()
        assertEquals(SpeakerClass.STANDARD, r.speakerClass)
        assertEquals(SpeakerClassTuning(), r.classTuning)
        assertFalse(r.forceLimiter)
        assertTrue(MeasurementRequest(speakerClass = SpeakerClass.DEVICE).forceLimiter)
        assertTrue(MeasurementRequest(environment = ListeningEnvironment.OUTDOOR).forceLimiter)
    }
}
