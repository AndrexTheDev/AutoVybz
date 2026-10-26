package com.andrexthedev.autovybz.measurement

import com.andrexthedev.autovybz.measurement.audio.VolumeMath
import com.andrexthedev.autovybz.measurement.dsp.SmoothingProfile
import com.andrexthedev.autovybz.measurement.eq.AutoEqSettings
import com.andrexthedev.autovybz.measurement.eq.HIGH_BOOST_CAP_START_HZ
import com.andrexthedev.autovybz.measurement.eq.PreampPlanner
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
                    // Outdoors the preset arms the limiter, so the loudness make-up gets its allowance;
                    // indoors the clipping-safe pre-gain stays untouched (allowance 0).
                    preampHeadroomAllowanceDb =
                        if (tuning.forceLimiter) PreampPlanner.LIMITER_HEADROOM_ALLOWANCE_DB else 0.0,
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
        // Full correction of the resonance peaks, but the limiter is armed -> the make-up may use
        // the limiter's headroom so the rescue does not end up quieter than the broken speaker.
        assertEquals(1.0, s.cutTrust, 0.0)
        assertEquals(PreampPlanner.LIMITER_HEADROOM_ALLOWANCE_DB, s.preampHeadroomAllowanceDb, 0.0)
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
        // Sub-bass protection only: the -6 dB point of a tiny driver is midrange, cutting there would
        // eat musical bass. And the treble boost is capped: above 6 kHz the self-measurement is
        // dominated by the estimated mic profile.
        assertEquals(60.0, s.protectiveHighPassHz ?: -1.0, 0.0)
        assertEquals(2.0, s.highBoostCapDb ?: -1.0, 0.0)
        assertEquals(HIGH_BOOST_CAP_START_HZ, s.highBoostCapStartHz, 0.0)
        assertTrue(CorrectionPlanner.forceLimiter(indoor, device))
        // Gain staging: the near-field excesses are corrected to 75 % and the perceived level loss of
        // those cuts is made up - a self-calibration must not make the phone quieter.
        assertEquals(0.75, s.cutTrust, 0.0)
        // Frequency-dependent cutTrust: below 300 Hz the pressure-zone + chassis-vibration overstatement
        // is far larger than the actual acoustic excess, so cuts there are scaled by only 50 %.
        assertEquals(300.0, s.cutTrustLowHz, 0.0)
        assertEquals(0.5, s.cutTrustLow, 0.0)
        assertTrue(s.loudnessMakeUp)
        assertEquals(PreampPlanner.MAX_MAKE_UP_DB, s.maxMakeUpDb, 0.0)
        assertEquals(PreampPlanner.LIMITER_HEADROOM_ALLOWANCE_DB, s.preampHeadroomAllowanceDb, 0.0)
        // Near field: bass smoothed hard (pressure zone), midrange/treble finer than a room measurement.
        assertEquals(SmoothingProfile.DEVICE, CorrectionPlanner.smoothing(SmoothingProfile.DYNAMIC, indoor, device))
        assertTrue(device.singlePosition)
        assertTrue(device.forcePhoneMic)
        assertEquals(
            VolumeMath.DEVICE_MEASUREMENT_FRACTION.toDouble(),
            device.measurementVolumeFraction?.toDouble() ?: -1.0,
            0.0,
        )
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
