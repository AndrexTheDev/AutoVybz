package com.andrexthedev.autovybz.measurement.mic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MicProfilesTest {

    private fun curve(vararg p: Pair<Double, Double>) = ResponseCurve(p.toList())

    private fun profile(id: String, manu: String?, models: List<String>, conf: ProfileConfidence = ProfileConfidence.ESTIMATED) =
        MicProfile(
            id, id, manu, models.map { Regex(it, RegexOption.IGNORE_CASE) }, conf,
            mapOf(
                InputSource.UNPROCESSED to curve(20.0 to -5.0, 1000.0 to 0.0, 10000.0 to 3.0),
                InputSource.VOICE_RECOGNITION to curve(20.0 to -14.0, 200.0 to 0.0, 10000.0 to 3.0),
            ),
        )

    private val default = profile("default", null, emptyList())
    private val db = listOf(
        profile("samsung-generic", "samsung", emptyList()),
        profile("samsung-s", "samsung", listOf("SM-S9\\d\\d.*")),
        profile("samsung-s-measured", "samsung", listOf("SM-S921B"), ProfileConfidence.MEASURED),
        profile("xiaomi-generic", "xiaomi", emptyList()),
    )

    @Test
    fun manufacturerNormalisation() {
        // Mirror of normalise() in tools/micdb/generate_mic_profiles.py.
        val cases = mapOf(
            "Samsung Electronics" to "samsung",
            "POCO" to "xiaomi",
            "Redmi" to "xiaomi",
            "XIAOMI" to "xiaomi",
            "HMD Global Oy" to "hmd global",
            "TECNO MOBILE LIMITED" to "tecno",
            "Infinix Mobility Limited" to "infinix",
            "itel" to "itel",
            "Black Shark" to "blackshark",
            "BLACKSHARK" to "blackshark",
            "Bullitt Group Limited" to "cat",
            "Amazon" to "amazon",
            "TCL Communication" to "tcl",
            "Gigaset Communications" to "gigaset",
            "Sony" to "sony",
            " Google " to "google",
            "DOOGEE" to "doogee",
        )
        for ((raw, expected) in cases) {
            assertEquals(raw, expected, MicProfileMatcher.normaliseManufacturer(raw))
        }
    }

    @Test
    fun matcherPrefersModelThenManufacturerThenDefault() {
        assertEquals("samsung-s-measured", MicProfileMatcher.match(db, default, "samsung", "SM-S921B").id)
        assertEquals("samsung-s", MicProfileMatcher.match(db, default, "Samsung", "SM-S928U").id)
        assertEquals("samsung-generic", MicProfileMatcher.match(db, default, "samsung", "SM-A546B").id)
        assertEquals("xiaomi-generic", MicProfileMatcher.match(db, default, "POCO", "23049PCD8G").id)
        assertEquals("default", MicProfileMatcher.match(db, default, "Acme", "X1").id)
    }

    @Test
    fun compensationInterpolatesAndFallsBack() {
        val p = db[0]
        assertEquals(-5.0, p.levelAt(20.0, InputSource.UNPROCESSED), 1e-9)
        assertEquals(-14.0, p.levelAt(20.0, InputSource.VOICE_RECOGNITION), 1e-9)
        // MIC has no curve -> falls back to UNPROCESSED
        assertEquals(-5.0, p.levelAt(20.0, InputSource.MIC), 1e-9)
        val comp = MicCompensation.forPhone(p, InputSource.UNPROCESSED, curve(20.0 to 1.0, 20000.0 to 1.0))
        assertEquals(-4.0, comp(20.0), 1e-9)
        assertEquals(1.0, comp(1000.0), 1e-9)
        assertTrue(comp.description.contains("user offset"))
        assertEquals(0.0, MicCompensation.NONE(500.0), 0.0)
    }

    @Test
    fun parsesUmikStyleCalibrationFile() {
        val txt = """
            "Sens Factor =-1.378dB, SERNO: 7012345"
            * comment
            10.054	-1.8361	0
            20.0, -0.9 , 1.2
            1000;0.00
            20000  2.5
            garbage line
        """.trimIndent()
        val cal = CalibrationFileParser.parse(txt)
        assertEquals(-1.378, cal.sensitivityDb!!, 1e-9)
        assertEquals(4, cal.curve.frequencies.size)
        assertEquals(2.5, cal.curve.levelAt(20000.0), 1e-9)
        val usb = MicCompensation.forUsbMic(cal.curve)
        assertEquals(0.0, usb(1000.0), 1e-9)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsFileWithoutData() {
        CalibrationFileParser.parse("just text\nno numbers")
    }
}
