package com.andrexthedev.autovybz.preset

import com.andrexthedev.autovybz.measurement.Verification
import com.andrexthedev.autovybz.measurement.eq.PeqBand
import com.andrexthedev.autovybz.measurement.eq.PeqType
import com.andrexthedev.autovybz.measurement.eq.TargetType
import com.andrexthedev.autovybz.preset.model.AutoVybzPreset
import com.andrexthedev.autovybz.preset.model.MeasurementSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gradle-only (org.json). */
class AutoVybzPresetCodecTest {
    @Test
    fun internalFormatRoundTrips() {
        val p = AutoVybzPreset(
            "AutoVybz_Test_2026-10-23", 123L, "Google", "Pixel 8", "Boom 3", TargetType.HARMAN, 5,
            listOf(PeqBand(PeqType.PEAK, 250.0, -4.2, 1.5)), -1.0, 60.0, 16000.0,
            MeasurementSnapshot(doubleArrayOf(20.0, 20000.0), doubleArrayOf(1.0, 2.0), doubleArrayOf(1.0, 2.0),
                doubleArrayOf(0.0, 0.0), doubleArrayOf(0.5, 0.5), "x", "desc", "UNPROCESSED", "Oboe", 100.0, 30.0, listOf("LOW_SNR")),
        )
        val back = AutoVybzPresetCodec.fromInternalJson(p.toInternalJson())
        assertEquals(p.name, back.name)
        assertEquals(TargetType.HARMAN, back.target)
        assertEquals(p.bands, back.bands)
        assertEquals("Boom 3", back.speakerName)
        assertEquals(listOf("LOW_SNR"), back.measurement!!.warnings)
        assertEquals(20000.0, back.measurement!!.frequencies[1], 0.0)
    }

    @Test
    fun outdoorContextRoundTripsAndOldFilesDefaultToIndoor() {
        val snap = MeasurementSnapshot(doubleArrayOf(20.0, 20000.0), doubleArrayOf(1.0, 2.0), doubleArrayOf(1.0, 2.0),
            doubleArrayOf(0.0, 0.0), doubleArrayOf(0.5, 0.5), "x", "desc", "UNPROCESSED", "Oboe", 250.0, 22.0,
            listOf("LOW_FREQ_NOISE", "DROPOUT"), environment = "OUTDOOR", positions = 3)
        val p = AutoVybzPreset(
            "AutoVybz_Party_2026-10-24", 1L, "Google", "Pixel 8", "Boom 3", TargetType.SMART, 10,
            listOf(PeqBand(PeqType.PEAK, 120.0, -3.5, 1.0)), -2.5, 60.0, 16000.0, snap,
        )
        val json = p.toInternalJson()
        val back = AutoVybzPresetCodec.fromInternalJson(json).measurement!!
        assertEquals("OUTDOOR", back.environment)
        assertEquals(3, back.positions)
        assertEquals(listOf("LOW_FREQ_NOISE", "DROPOUT"), back.warnings)

        // Files written before the outdoor mode existed have neither field.
        val legacy = json.lines().filterNot { it.contains("\"environment\"") || it.contains("\"positions\"") }
            .joinToString("\n")
        val old = AutoVybzPresetCodec.fromInternalJson(legacy).measurement!!
        assertEquals("INDOOR", old.environment)
        assertEquals(1, old.positions)
    }

    @Test
    fun speakerClassAndHighPassRoundTripAndOldFilesDefaultToStandard() {
        val snap = MeasurementSnapshot(doubleArrayOf(20.0, 20000.0), doubleArrayOf(1.0, 2.0), doubleArrayOf(1.0, 2.0),
            doubleArrayOf(0.0, 0.0), doubleArrayOf(0.5, 0.5), "x", "desc", "UNPROCESSED", "Oboe", 5.0, 40.0,
            emptyList(), speakerClass = "DEVICE")
        val p = AutoVybzPreset(
            "AutoVybz_Device_2026-10-25", 1L, "Google", "Pixel 8", "Device speakers", TargetType.HARMAN, 10,
            listOf(PeqBand(PeqType.HIGH_PASS, 480.0, 0.0, 0.707), PeqBand(PeqType.PEAK, 3100.0, -4.0, 1.2)),
            -1.5, 600.0, 12000.0, snap,
        )
        val json = p.toInternalJson()
        val back = AutoVybzPresetCodec.fromInternalJson(json)
        assertEquals("DEVICE", back.measurement!!.speakerClass)
        assertEquals(p.bands, back.bands) // the protective high pass survives the round trip

        // Files written before speaker classes existed have no such field.
        val legacy = json.lines().filterNot { it.contains("\"speakerClass\"") }.joinToString("\n")
        assertEquals("STANDARD", AutoVybzPresetCodec.fromInternalJson(legacy).measurement!!.speakerClass)
    }

    /**
     * The "Check" run belongs to the calibration, so it has to survive the file: a share card, an APO export
     * or a re-imported preset can then say the result was measured twice - and with which numbers.
     */
    @Test
    fun theVerificationReportTravelsWithThePreset() {
        val report = Verification.Report(
            correlation = 0.97, measuredChangeDb = 2.4, predictedChangeDb = 2.5, predictionErrorDb = 0.3,
            residualBeforeDb = 3.1, residualAfterDb = 0.7, residualPredictedDb = 0.6,
            measuredImprovementDb = 2.4, predictedImprovementDb = 2.5,
            judgeLowHz = 71.0, judgeHighHz = 16000.0, verdict = Verification.Verdict.AS_PREDICTED,
        )
        val p = AutoVybzPreset(
            "AutoVybz_Checked_2026-10-29", 9L, "Google", "Pixel 8", "Boom 3", TargetType.SMART, 10,
            listOf(PeqBand(PeqType.PEAK, 120.0, -3.0, 1.0)), -1.5, 60.0, 16000.0,
            MeasurementSnapshot(
                doubleArrayOf(20.0, 20000.0), doubleArrayOf(1.0, 2.0), doubleArrayOf(1.0, 2.0),
                doubleArrayOf(0.0, 0.0), doubleArrayOf(0.5, 0.5), "x", "desc", "UNPROCESSED", "Oboe", 5.0, 40.0,
                emptyList(), verification = report,
            ),
        )
        val back = AutoVybzPresetCodec.fromInternalJson(p.toInternalJson()).measurement!!.verification
        assertEquals(report, back)

        // A calibration the user never checked carries no report - and its JSON is byte-for-byte the shape
        // every file written before this field existed has, so those keep loading.
        val unchecked = p.copy(measurement = p.measurement!!.withVerification(null))
        val uncheckedJson = unchecked.toInternalJson()
        assertTrue("an unchecked preset must not mention the check", "verification" !in uncheckedJson)
        assertNull(AutoVybzPresetCodec.fromInternalJson(uncheckedJson).measurement!!.verification)
    }

    /** A verdict from a newer build is dropped, the numbers are not: the UI words the verdict, not the file. */
    @Test
    fun anUnknownVerdictDegradesToNoCheck() {
        val json = AutoVybzPreset(
            "AutoVybz_Future_2027-01-01", 1L, "Google", "Pixel 9", null, TargetType.SMART, 5,
            emptyList(), 0.0, 60.0, 16000.0,
            MeasurementSnapshot(
                doubleArrayOf(20.0, 20000.0), doubleArrayOf(0.0, 0.0), doubleArrayOf(0.0, 0.0),
                doubleArrayOf(0.0, 0.0), doubleArrayOf(0.0, 0.0), "x", "desc", "UNPROCESSED", "Oboe", 5.0, 40.0,
                emptyList(),
                verification = Verification.Report(
                    0.9, 1.0, 1.0, 0.1, 1.0, 0.2, 0.2, 0.8, 0.8, 70.0, 16000.0, Verification.Verdict.NO_CHANGE,
                ),
            ),
        ).toInternalJson().replace("\"NO_CHANGE\"", "\"SOMETHING_NEW\"")
        assertNull(AutoVybzPresetCodec.fromInternalJson(json).measurement!!.verification)
    }
}
