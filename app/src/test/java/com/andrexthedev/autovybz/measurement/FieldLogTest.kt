package com.andrexthedev.autovybz.measurement

import com.andrexthedev.autovybz.measurement.dsp.HarmonicDistortion
import com.andrexthedev.autovybz.measurement.dsp.MeasurementWarning
import com.andrexthedev.autovybz.measurement.eq.TargetType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

/**
 * The record that carries the four "needs a device" questions out of the app (docs/FIELD_DATA.md).
 *
 * Two properties are tested harder than the rest, because both fail silently in production:
 * the line must round-trip (the analyser reads it months later, from a file nobody re-generates), and
 * it must contain *nothing* beyond the documented keys - a curve or a preset name sneaking into the
 * record would turn an anonymous sample into a description of the user's room.
 */
class FieldLogTest {

    private fun sample(
        thdPercent: Double? = 2.5,
        micClipped: Boolean = false,
        rt60S: Double? = 0.55,
        warnings: List<MeasurementWarning> = emptyList(),
    ) = FieldLog.sample(
        createdAtMillis = 1_700_000_000_000L,
        device = "TestCorp TestPhone",
        speakerClass = SpeakerClass.DEVICE,
        environment = ListeningEnvironment.INDOOR,
        target = TargetType.HARMAN,
        positions = 3,
        positionDeviationsDb = listOf(1.0, 1.4, 4.5),
        rejectedPositions = 1,
        spreadDb = 1.7,
        thdPercent = thdPercent,
        micClipped = micClipped,
        inputPeak = 0.62,
        rt60S = rt60S,
        volumeFraction = 0.5,
        levelStepDb = 3.0,
        warnings = warnings,
    )

    @Test
    fun aSampleSurvivesTheRoundTrip() {
        val before = sample(warnings = listOf(MeasurementWarning.SPEAKER_DISTORTION))
        val after = requireNotNull(FieldLog.fromJson(FieldLog.toJson(before)))
        assertEquals(before, after)
        // …including the parts that are legitimately absent on a quick single-position run.
        val minimal = FieldLog.sample(
            createdAtMillis = 42L, device = "", speakerClass = SpeakerClass.STANDARD,
            environment = ListeningEnvironment.OUTDOOR, target = TargetType.SMART,
            positions = 1, positionDeviationsDb = emptyList(), rejectedPositions = 0, spreadDb = null,
            thdPercent = null, micClipped = false, inputPeak = 0.1, rt60S = null,
            volumeFraction = 0.7, levelStepDb = 0.0, warnings = emptyList(),
        )
        assertEquals(minimal, FieldLog.fromJson(FieldLog.toJson(minimal)))
    }

    @Test
    fun oneLineIsOneJsonObjectWithExactlyTheDocumentedKeys() {
        val o = JSONObject(FieldLog.toJson(sample()))
        val keys = o.keys().asSequence().toList()
        assertEquals(FieldLog.KEYS.sorted(), keys.sorted())
        assertFalse("a sample is one line", FieldLog.toJson(sample()).contains('\n'))

        // No curve, no filter, no name: the record must stay a dozen numbers about a measurement.
        val forbidden = listOf(
            "name", "rawDb", "smoothedDb", "frequencies", "bands", "preamp", "speakerName", "recording",
        )
        for (key in forbidden) assertFalse("the sample must not contain $key", key in keys)
    }

    @Test
    fun aLineThisBuildDoesNotUnderstandIsSkippedNotRepaired() {
        assertNull(FieldLog.fromJson(""))
        assertNull(FieldLog.fromJson("   "))
        assertNull(FieldLog.fromJson("not json at all"))
        assertNull(FieldLog.fromJson("""{"schema":2,"at":1700000000000}"""))
        assertNull(FieldLog.fromJson("""{"schema":1}"""))
        assertNull(FieldLog.fromJson("""{"schema":1,"at":0}"""))
        // A truncated line (app killed mid-write) must not take the rest of the file with it.
        val full = FieldLog.toJson(sample())
        assertNull(FieldLog.fromJson(full.substring(0, full.length / 2)))
    }

    @Test
    fun theLevelBoundTellsTheMicrophoneApartFromTheSpeaker() {
        assertEquals(FieldLog.LevelBound.NONE, sample(thdPercent = 1.0).levelBound)
        assertEquals(FieldLog.LevelBound.MIC_CLIPPING, sample(thdPercent = 1.0, micClipped = true).levelBound)
        assertEquals(
            FieldLog.LevelBound.SPEAKER_THD,
            sample(thdPercent = HarmonicDistortion.THD_WARN_PERCENT + 0.5).levelBound,
        )
        assertEquals(
            FieldLog.LevelBound.BOTH,
            sample(thdPercent = HarmonicDistortion.THD_WARN_PERCENT + 0.5, micClipped = true).levelBound,
        )
        // The warning is the same fact as the flag when the capture layer is the one that noticed it.
        assertEquals(
            FieldLog.LevelBound.MIC_CLIPPING,
            sample(thdPercent = null, warnings = listOf(MeasurementWarning.CLIPPING)).levelBound,
        )
        // An unmeasurable THD is not a distortion finding.
        assertEquals(FieldLog.LevelBound.NONE, sample(thdPercent = null).levelBound)
    }

    @Test
    fun theRecordKnowsWhetherTheLevelWasTurnedDown() {
        assertTrue(sample().levelWasLowered)
        val atPlannedLevel = FieldLog.sample(
            createdAtMillis = 1L, device = "", speakerClass = SpeakerClass.STANDARD,
            environment = ListeningEnvironment.INDOOR, target = TargetType.SMART, positions = 1,
            positionDeviationsDb = emptyList(), rejectedPositions = 0, spreadDb = null, thdPercent = 1.0,
            micClipped = false, inputPeak = 0.3, rt60S = 0.4, volumeFraction = 0.7, levelStepDb = 0.0,
            warnings = emptyList(),
        )
        assertFalse(atPlannedLevel.levelWasLowered)
    }

    @Test
    fun anExportFromAMarkContainsExactlyTheLinesAfterIt() {
        val old = sample().copy(createdAtMillis = 1_000L, device = "before")
        val new1 = sample().copy(createdAtMillis = 2_000L, device = "after-1")
        val new2 = sample().copy(createdAtMillis = 3_000L, device = "after-2")
        val log = listOf(old, new1, new2).joinToString("\n") { FieldLog.toJson(it) } + "\n" +
            "not a sample at all\n" + "\n"

        val since = FieldLog.linesSince(log, 2_000L)
        assertTrue("the sample at the mark counts", "after-1" in since)
        assertTrue("later samples count", "after-2" in since)
        assertFalse("earlier samples do not", "before" in since)
        assertFalse("unparsable lines never leave the phone", "not a sample" in since)
        assertEquals(2, since.trim().lines().size)

        // No mark, no filter: everything the file holds.
        assertEquals(FieldLog.toJson(old), FieldLog.linesSince(FieldLog.toJson(old), 0L).trim())
    }

    /** The exported column names are what `tools/fielddata/analyze.py` reads; keep them discoverable. */
    @Test
    fun theKeysTheAnalyserNeedsAreStable() {
        val needed = listOf("thdPercent", "rt60S", "deviations", "volume", "levelStepDb", "micClipped", "speakerClass")
        for (key in needed) {
            assertTrue("$key must be part of the schema", key in FieldLog.KEYS)
        }
        assertEquals(FieldLog.DOCUMENTED_VOLUME, 0.7, 1e-9)
    }
}
