package com.andrexthedev.autovybz.preset

import com.andrexthedev.autovybz.measurement.eq.PeqBand
import com.andrexthedev.autovybz.measurement.eq.PeqType
import com.andrexthedev.autovybz.measurement.eq.TargetType
import com.andrexthedev.autovybz.preset.model.AutoVybzPreset
import com.andrexthedev.autovybz.preset.model.MeasurementSnapshot
import org.junit.Assert.assertEquals
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
}
