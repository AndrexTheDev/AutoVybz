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
}
