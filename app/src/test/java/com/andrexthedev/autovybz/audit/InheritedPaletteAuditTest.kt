package com.andrexthedev.autovybz.audit

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Branding: the engine screens inherited from Equalizer314 must not fall back to its neutral-grey look.
 * Every colour literal in their layouts / drawables / colour resources / Kotlin views is slate (or brand accent),
 * produced by tools/branding/retheme_inherited.py. Pure black, pure white and transparent are allowed.
 */
class InheritedPaletteAuditTest {

    private val xmlColour = Regex("""#([0-9A-Fa-f]{8}|[0-9A-Fa-f]{6})\b""")
    private val ktColour = Regex("""0x([0-9A-Fa-f]{8})\b""")

    private fun neutralGrey(hex: String): Boolean {
        val h = hex.uppercase()
        if (h.length == 8 && h.startsWith("00")) return false
        val rgb = h.takeLast(6)
        val (r, g, b) = listOf(0, 2, 4).map { rgb.substring(it, it + 2).toInt(16) }
        if ((r == 0 && g == 0 && b == 0) || (r == 255 && g == 255 && b == 255)) return false
        return maxOf(r, g, b) - minOf(r, g, b) <= 3
    }

    private fun scan(files: List<File>, re: Regex): List<String> = files.flatMap { f ->
        f.readLines().withIndex().flatMap { (i, line) ->
            re.findAll(line).filter { neutralGrey(it.groupValues[1]) }.map { "${f.name}:${i + 1} ${it.value}" }.toList()
        }
    }

    @Test
    fun engineScreensUseTheSlatePalette() {
        val res = listOf("layout", "layout-land", "drawable", "color", "values", "values-night").flatMap { d ->
            ProjectFiles.module("src/main/res/$d").listFiles { f -> f.extension == "xml" }.orEmpty().toList()
        }.filter { it.name !in setOf("strings.xml", "autovybz_strings.xml") }
        val kotlin = ProjectFiles.module("src/main/java/com/bearinmind").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("scan found no files", res.size > 20 && kotlin.size > 20)
        val problems = scan(res, xmlColour) + scan(kotlin, ktColour)
        assertTrue(
            "neutral grey literals (run tools/branding/retheme_inherited.py --write) (${problems.size}):\n  " +
                problems.take(40).joinToString("\n  "),
            problems.isEmpty(),
        )
    }

    @Test
    fun upstreamPurpleAccentIsGone() {
        val kotlin = ProjectFiles.module("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }
        val hits = kotlin.filter { f -> Regex("(?i)BB86FC|6200EE|3700B3").containsMatchIn(f.readText()) }.map { it.name }.toList()
        assertTrue("Material purple left in $hits", hits.isEmpty())
    }

    @Test
    fun selectedFilterWearsTheBrandAccent() {
        for (d in listOf("values", "values-night")) {
            val xml = ProjectFiles.module("src/main/res/$d/colors.xml").readText()
            assertTrue("$d filter_active", "<color name=\"filter_active\">@color/avz_green</color>" in xml)
            assertTrue("$d filter_active_text", "<color name=\"filter_active_text\">@color/avz_on_green</color>" in xml)
        }
    }
}
