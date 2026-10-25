package com.andrexthedev.autovybz.measurement.mic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** USB-mic calibration files (miniDSP UMIK, Dayton, REW exports) are user input: fuzz the parser. */
class CalibrationParserFuzzTest {

    @Test
    fun parsesAllCommonLayouts() {
        val seps = listOf("\t", " ", ",", ";", "  ", ", ")
        val r = Random(11)
        repeat(200) {
            val sep = seps[r.nextInt(seps.size)]
            val eol = if (r.nextBoolean()) "\r\n" else "\n"
            val pts = (0 until r.nextInt(2, 200)).map { i -> 20.0 * Math.pow(1.03, i.toDouble()) to r.nextDouble(-10.0, 10.0) }
            val text = buildString {
                append("\"Sens Factor =-1.37dB, SERNO: 7000000\"").append(eol)
                append("* comment").append(eol).append(eol)
                pts.forEach { (f, db) -> append("%.3f".format(java.util.Locale.US, f)).append(sep).append("%.3f".format(java.util.Locale.US, db)); if (r.nextBoolean()) append(sep).append("0.0"); append(eol) }
            }
            val cal = CalibrationFileParser.parse(text)
            assertEquals(-1.37, cal.sensitivityDb!!, 1e-9)
            assertEquals(pts.size, cal.curve.frequencies.size)
            pts.zip(cal.curve.levelsDb.toList()).forEach { (p, l) -> assertEquals(p.second, l, 0.0006) }
        }
    }

    @Test
    fun garbageNeverCrashesAndAcceptedCurvesAreUsable() {
        val r = Random(12)
        val tokens = listOf("1", "20", "20000", "-3.5", "0", "-0", ".", "..", "1e3", "NaN", "Infinity", "-1e400", "1e400", "abc", "\"", "*",
            "Sens", "Factor", "=", "dB", ",", ";", "\t", " ", "\n", "\r\n", "1,5", "61", "-61", "1.2.3")
        var accepted = 0
        repeat(5000) {
            // Half free-form token soup, half line-structured "<a><sep><b>" rows built from the same tokens.
            val text = if (r.nextBoolean()) buildString { repeat(r.nextInt(1, 80)) { append(tokens[r.nextInt(tokens.size)]) } }
            else buildString {
                repeat(r.nextInt(1, 30)) {
                    append(tokens[r.nextInt(tokens.size)]).append(listOf(" ", "\t", ",", ";")[r.nextInt(4)]).append(tokens[r.nextInt(tokens.size)])
                    append(if (r.nextBoolean()) "\n" else "\r\n")
                }
            }
            val result = runCatching { CalibrationFileParser.parse(text) }
            result.exceptionOrNull()?.let { e -> assertTrue("unexpected ${e::class.simpleName} for: $text", e is IllegalArgumentException) }
            result.getOrNull()?.let { cal ->
                accepted++
                assertTrue(cal.curve.frequencies.all { it > 0 && it.isFinite() })
                for (f in listOf(10.0, 20.0, 1000.0, 20000.0, 40000.0)) assertTrue("levelAt($f) for: $text", cal.curve.levelAt(f).isFinite())
                cal.sensitivityDb?.let { assertTrue(it.isFinite()) }
            }
        }
        assertTrue("fuzzer never produced a valid file", accepted > 50)
    }

    @Test
    fun duplicateFrequenciesDoNotProduceNaN() {
        val cal = CalibrationFileParser.parse("100 1\n100 3\n1000 0\n1000 0\n")
        for (f in listOf(50.0, 100.0, 500.0, 1000.0, 5000.0)) assertTrue(cal.curve.levelAt(f).isFinite())
    }
}
