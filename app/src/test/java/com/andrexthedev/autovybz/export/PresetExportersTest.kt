package com.andrexthedev.autovybz.export

import com.andrexthedev.autovybz.measurement.eq.PeqBand
import com.andrexthedev.autovybz.measurement.eq.PeqType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class PresetExportersTest {

    private val bands = listOf(
        PeqBand(PeqType.LOW_SHELF, 105.0, 3.04, 0.7),
        PeqBand(PeqType.PEAK, 214.4, -3.54, 1.19),
        PeqBand(PeqType.HIGH_SHELF, 9649.0, 2.05, 1.0),
    )

    @Test
    fun apoHighPassHasNoGainTokenAndRoundTrips() {
        val hp = listOf(PeqBand(PeqType.HIGH_PASS, 142.0, 0.0, 0.707))
        val txt = PresetExporters.toApoText(hp, -1.0)
        assertTrue(txt.contains("Filter 1: ON HPQ Fc 142 Hz Q 0.71"))
        assertFalse(txt.contains("Gain"))
        val (_, parsed) = PresetExporters.parseApoText(txt)
        assertEquals(1, parsed.size)
        assertEquals(PeqType.HIGH_PASS, parsed[0].type)
        assertEquals(142.0, parsed[0].frequency, 1e-9)
        assertEquals(0.0, parsed[0].gainDb, 1e-9)
    }

    @Test
    fun apoTextRoundTrip() {
        val txt = PresetExporters.toApoText(bands, -4.3, "AutoVybz test")
        assertTrue(txt.contains("Preamp: -4.3 dB"))
        assertTrue(txt.contains("Filter 2: ON PK Fc 214 Hz Gain -3.5 dB Q 1.19"))
        assertTrue(txt.contains("Filter 1: ON LSC Fc 105 Hz Gain 3.0 dB Q 0.70"))
        val (pre, parsed) = PresetExporters.parseApoText(txt)
        assertEquals(-4.3, pre, 1e-9)
        assertEquals(3, parsed.size)
        assertEquals(PeqType.HIGH_SHELF, parsed[2].type)
        assertEquals(9649.0, parsed[2].frequency, 1e-9)
    }

    @Test
    fun peqJsonIsWellFormed() {
        val json = PresetExporters.toPeqJson(bands, -4.3, "My \"Speaker\"")
        assertTrue(json.contains("\"name\": \"My \\\"Speaker\\\"\""))
        assertTrue(json.contains("{\"type\": \"PK\", \"fc\": 214, \"gain\": -3.5, \"q\": 1.19}"))
        assertEquals(json.count { it == '{' }, json.count { it == '}' })
        assertTrue(PresetExporters.toPeqJson(emptyList(), 0.0, "x").contains("\"filters\": []"))
    }

    @Test
    fun rewCsvHasCommentHeaderAndPairs() {
        val csv = PresetExporters.toRewCsv(doubleArrayOf(20.0, 1000.0), doubleArrayOf(-3.0, 0.0), listOf("Speaker: JBL"))
        val lines = csv.trim().lines()
        assertTrue(lines.takeWhile { it.startsWith("*") }.size == 3)
        assertEquals("20.000,-3.000", lines[3])
        assertEquals("1000.000,0.000", lines[4])
    }

    @Test
    fun presetNamesFollowScheme() {
        val date = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 23) }.time
        assertEquals("AutoVybz_JBL-Flip-6_2026-10-23", PresetNaming.defaultName("  JBL Flip 6 ", date))
        assertEquals("AutoVybz_Speaker_2026-10-23", PresetNaming.defaultName(null, date))
        assertEquals(
            "AutoVybz_Boom_2026-10-23_3",
            PresetNaming.defaultName("Boom", date, listOf("AutoVybz_Boom_2026-10-23", "AutoVybz_Boom_2026-10-23_2")),
        )
        assertEquals("Wohnzimmer-Böse", PresetNaming.sanitize("Wohnzimmer / Böse!"))
        assertTrue(PresetNaming.isValid("AutoVybz_Küche"))
        assertFalse(PresetNaming.isValid("a/b"))
        assertFalse(PresetNaming.isValid("   "))
    }
}
