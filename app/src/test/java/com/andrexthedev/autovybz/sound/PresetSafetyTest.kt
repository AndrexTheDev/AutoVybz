package com.andrexthedev.autovybz.sound

import com.andrexthedev.autovybz.measurement.ListeningEnvironment
import com.andrexthedev.autovybz.measurement.SpeakerClass
import com.andrexthedev.autovybz.preset.model.MeasurementSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The limiter rule has to be identical in the wizard (saving a calibration) and in the Sound/Pro screens
 * (re-applying one). These cases are the ones that used to differ: everything except "outdoors" lost its
 * limiter as soon as a style was switched.
 */
class PresetSafetyTest {

    private fun snapshot(environment: ListeningEnvironment = ListeningEnvironment.INDOOR, speakerClass: SpeakerClass = SpeakerClass.STANDARD) =
        MeasurementSnapshot(
            frequencies = DoubleArray(2) { 100.0 * it + 100.0 },
            rawDb = DoubleArray(2),
            smoothedDb = DoubleArray(2),
            targetDb = DoubleArray(2),
            predictedDb = DoubleArray(2),
            micProfileId = "test",
            micDescription = "test",
            inputSource = "UNPROCESSED",
            engine = "test",
            latencyMs = 0.0,
            medianSnrDb = 30.0,
            warnings = emptyList(),
            environment = environment.name,
            positions = 1,
            speakerClass = speakerClass.name,
        )

    @Test
    fun userSettingArmsTheLimiterForAnything() {
        assertTrue(PresetSafety.requiresLimiter(true, null))
        assertTrue(PresetSafety.requiresLimiter(true, snapshot()))
    }

    @Test
    fun plainIndoorCalibrationFollowsTheUserSetting() {
        assertFalse(PresetSafety.requiresLimiter(false, snapshot()))
        assertFalse(PresetSafety.isAlwaysProtected(snapshot()))
    }

    @Test
    fun outdoorCalibrationIsAlwaysProtected() {
        val outdoor = snapshot(environment = ListeningEnvironment.OUTDOOR)
        assertTrue(PresetSafety.isAlwaysProtected(outdoor))
        assertTrue(PresetSafety.requiresLimiter(false, outdoor))
    }

    @Test
    fun rescueAndDeviceCalibrationsAreAlwaysProtected() {
        // Both classes run the speaker near its limits by design (forceLimiter in SpeakerClassTuning).
        for (cls in listOf(SpeakerClass.RESCUE, SpeakerClass.DEVICE)) {
            val s = snapshot(speakerClass = cls)
            assertTrue("$cls must always be protected", PresetSafety.isAlwaysProtected(s))
            assertTrue(PresetSafety.requiresLimiter(false, s))
        }
    }

    @Test
    fun outdoorDeviceCalibrationStaysProtected() {
        val s = snapshot(environment = ListeningEnvironment.OUTDOOR, speakerClass = SpeakerClass.DEVICE)
        assertTrue(PresetSafety.requiresLimiter(false, s))
    }

    @Test
    fun unknownOrMissingClassFallsBackToTheEnvironmentRule() {
        // Presets written by older versions / hand-edited files: an unknown class must not crash or arm
        // the limiter out of nowhere.
        val unknown = snapshotWith(environment = "INDOOR", speakerClass = "SOMETHING_NEW")
        assertFalse(PresetSafety.isAlwaysProtected(unknown))
        assertFalse(PresetSafety.requiresLimiter(false, unknown))
        assertTrue(PresetSafety.isAlwaysProtected(snapshotWith(environment = "OUTDOOR", speakerClass = "SOMETHING_NEW")))
        assertFalse(PresetSafety.isAlwaysProtected(null))
    }

    /** Raw string fields, as an old or hand-edited preset would carry them. */
    private fun snapshotWith(environment: String, speakerClass: String) = MeasurementSnapshot(
        frequencies = DoubleArray(2) { 100.0 * it + 100.0 },
        rawDb = DoubleArray(2), smoothedDb = DoubleArray(2), targetDb = DoubleArray(2), predictedDb = DoubleArray(2),
        micProfileId = "", micDescription = "", inputSource = "", engine = "",
        latencyMs = 0.0, medianSnrDb = 0.0, warnings = emptyList(),
        environment = environment, positions = 1, speakerClass = speakerClass,
    )
}
