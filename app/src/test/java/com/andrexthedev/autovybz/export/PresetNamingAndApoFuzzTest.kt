package com.andrexthedev.autovybz.export

import com.andrexthedev.autovybz.measurement.eq.PeqBand
import com.andrexthedev.autovybz.measurement.eq.PeqType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone
import kotlin.random.Random

/** Property / fuzz tests for preset naming and the Equalizer APO text format (import + export). */
class PresetNamingAndApoFuzzTest {

    private val nasty = "abcXYZ019 -_.+/\\:*?\"<>|\t\n\r\u0000äöüÄÖÜßéñçø中文😀\u202E"

    private fun randomText(r: Random, max: Int) = String(CharArray(r.nextInt(0, max)) { nasty[r.nextInt(nasty.length)] })

    @Test
    fun sanitizedDeviceNamesAreAlwaysValidAndShort() {
        val r = Random(1)
        repeat(3000) {
            val s = PresetNaming.sanitize(randomText(r, 80))
            // Counted in code points, not chars: non-BMP letters (see the case below) are two chars each.
            assertTrue("'$s'", s.codePointCount(0, s.length) <= PresetNaming.MAX_NAME_CHARS)
            assertTrue("'$s'", s.all { it.isLetterOrDigit() || it in "-.+" })
            assertTrue("'$s'", !s.startsWith("-") && !s.startsWith(".") && !s.endsWith("-") && !s.endsWith("."))
        }
    }

    @Test
    fun nonBmpLettersSurviveSanitizingWithoutBeingSplit() {
        // MATHEMATICAL BOLD CAPITAL S et al.: letters whose code points are outside the BMP. Both surrogate
        // halves used to fail `Char.isLetterOrDigit()`, so such a device name became "Speaker".
        val fraktur = "\uD835\uDC12\uD835\uDC28\uD835\uDC27\uD835\uDC28\uD835\uDC2C"
        assertEquals(fraktur, PresetNaming.sanitize(fraktur))
        assertEquals("AutoVybz_$fraktur", PresetNaming.defaultName(fraktur).substringBeforeLast("_"))

        // A long non-BMP name must be cut between code points: a trailing lone high surrogate would be an
        // unencodable string (and would fail every later file-name use).
        val cut = PresetNaming.sanitize(fraktur.repeat(10))
        assertEquals(PresetNaming.MAX_NAME_CHARS, cut.codePointCount(0, cut.length))
        assertFalse("cut ends inside a surrogate pair: $cut", cut.last().isHighSurrogate())
        // Idempotent, and every code point is still a letter (no half pairs, no filler).
        assertEquals(cut, PresetNaming.sanitize(cut))
        assertTrue(cut, (0 until cut.codePointCount(0, cut.length)).all { Character.isLetter(cut.codePointAt(cut.offsetByCodePoints(0, it))) })
    }

    @Test
    fun defaultNameIsValidUniqueAndFollowsTheScheme() {
        val r = Random(2)
        val date = Calendar.getInstance(TimeZone.getDefault()).apply { set(2026, Calendar.OCTOBER, 24, 12, 0) }.time
        val existing = mutableListOf<String>()
        repeat(500) {
            val device = if (r.nextInt(5) == 0) null else randomText(r, 30)
            val name = PresetNaming.defaultName(device, date, existing)
            assertTrue(name, PresetNaming.isValid(name))
            assertTrue(name, Regex("""AutoVybz_[^_]+_2026-10-24(_\d+)?""").matches(name))
            assertTrue("duplicate $name", name !in existing)
            existing += name
        }
        assertEquals("AutoVybz_Speaker_2026-10-24", PresetNaming.defaultName("  \t ", date))
    }

    @Test
    fun isValidRejectsPathAndControlCharacters() {
        for (bad in listOf("", "   ", "a/b", "a\\b", "x\u0000", "tab\there", "x".repeat(65))) assertTrue(bad, !PresetNaming.isValid(bad))
        for (ok in listOf("Party 🎉", "Küche", "AutoVybz_JBL_2026-10-24", "x".repeat(64))) assertTrue(ok, PresetNaming.isValid(ok))
    }

    @Test
    fun apoExportRoundTripsWithinPrintedPrecision() {
        val r = Random(3)
        val types = listOf(PeqType.PEAK, PeqType.LOW_SHELF, PeqType.HIGH_SHELF)
        repeat(500) {
            val bands = List(r.nextInt(0, 11)) {
                PeqBand(types[r.nextInt(3)], 20.0 * Math.pow(1000.0, r.nextDouble()), r.nextDouble(-12.0, 6.0), r.nextDouble(0.3, 8.0))
            }
            val pre = -r.nextDouble(0.0, 12.0)
            val (p, parsed) = PresetExporters.parseApoText(PresetExporters.toApoText(bands, pre, "fuzz\nline 2"))
            assertEquals(pre, p, 0.051)
            assertEquals(bands.size, parsed.size)
            bands.zip(parsed).forEach { (a, b) ->
                assertEquals(a.type, b.type)
                assertEquals(a.frequency, b.frequency, if (a.frequency >= 100) 0.51 else 0.051)
                assertEquals(a.gainDb, b.gainDb, 0.051)
                assertEquals(a.q, b.q, 0.0051)
            }
        }
    }

    /** Imported files come from anywhere: garbage must never crash and never yield unusable filters. */
    @Test
    fun apoParserSurvivesGarbage() {
        val r = Random(4)
        val fragments = listOf("Preamp:", "Filter", "1:", "ON", "OFF", "PK", "LSC", "HSC", "Fc", "Hz", "Gain", "dB", "Q", ".", "-", "1.2.3",
            "0", "100", "-6.5", "1e9", "NaN", "Infinity", "..", "-.", " ", "\n", "\t", "#", "999999999999999999999")
        repeat(5000) {
            val text = buildString { repeat(r.nextInt(1, 60)) { append(fragments[r.nextInt(fragments.size)]); append(if (r.nextBoolean()) " " else "") } }
            val (pre, bands) = PresetExporters.parseApoText(text)
            assertTrue("preamp $pre from: $text", pre.isFinite() && pre in -60.0..60.0)
            bands.forEach { b ->
                assertTrue("bad band $b from: $text", b.frequency > 0 && b.frequency < 48000 && b.q > 0 && b.q <= 100 && b.gainDb.isFinite() && b.gainDb in -60.0..60.0)
            }
        }
        // Explicit edge cases that used to throw NumberFormatException.
        for (t in listOf("Preamp: . dB", "Preamp: 1.2.3 dB", "Filter 1: ON PK Fc 1.2.3 Hz Gain 1 dB Q 1", "Filter 1: ON PK Fc 100 Hz Gain - dB Q 1",
            "Filter 1: ON PK Fc 0 Hz Gain 3 dB Q 1", "Filter 1: ON PK Fc 100 Hz Gain 3 dB Q 0")) {
            val (_, bands) = PresetExporters.parseApoText(t)
            assertTrue(t, bands.isEmpty())
        }
    }

    @Test
    fun peqJsonEscapesNames() {
        val json = PresetExporters.toPeqJson(listOf(PeqBand(PeqType.PEAK, 100.0, -3.0, 1.0)), -0.5, "Quote\" Back\\ Ctl\u0001 Neu\nZeile")
        assertTrue(json, "Quote\\\"" in json && "Back\\\\" in json && "\\n" in json && "\u0001" !in json)
    }
}
