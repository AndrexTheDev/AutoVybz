package com.andrexthedev.autovybz.audit

import com.andrexthedev.autovybz.ui.theme.AutoVybzPalette as P
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * WCAG 2.1 contrast audit of every foreground/background pair the AutoVybz UI uses (1.4.3 text >= 4.5:1,
 * 1.4.11 non-text UI components and chart lines >= 3:1), plus the XML <-> Compose palette mirror.
 */
class ColorContrastAuditTest {

    private fun luminance(argb: Long): Double {
        fun ch(shift: Int): Double {
            val c = ((argb shr shift) and 0xFF) / 255.0
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * ch(16) + 0.7152 * ch(8) + 0.0722 * ch(0)
    }

    fun contrast(a: Long, b: Long): Double {
        val la = luminance(a); val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private val backgrounds = mapOf(
        "BACKGROUND" to P.BACKGROUND, "BACKGROUND_DEEP" to P.BACKGROUND_DEEP,
        "SURFACE" to P.SURFACE, "SURFACE_HIGH" to P.SURFACE_HIGH,
    )

    /** Text colours used on all neutral surfaces. */
    private val textColours = mapOf(
        "ON_DARK" to P.ON_DARK, "ON_DARK_MUTED" to P.ON_DARK_MUTED, "GOLD" to P.GOLD,
        "GREEN_BRIGHT" to P.GREEN_BRIGHT, "ERROR" to P.ERROR,
    )

    /** Text on coloured containers / buttons. */
    private val textOnColour = listOf(
        Triple("ON_GREEN on GREEN (primary buttons)", P.ON_GREEN, P.GREEN),
        Triple("BACKGROUND on GOLD (secondary buttons)", P.BACKGROUND, P.GOLD),
        Triple("ON_DARK on GREEN_CONTAINER", P.ON_DARK, P.GREEN_CONTAINER),
        Triple("ON_DARK_MUTED on GREEN_CONTAINER", P.ON_DARK_MUTED, P.GREEN_CONTAINER),
        Triple("GREEN_BRIGHT on GREEN_CONTAINER (countdown, badges)", P.GREEN_BRIGHT, P.GREEN_CONTAINER),
        Triple("GOLD on GOLD_CONTAINER (warnings)", P.GOLD, P.GOLD_CONTAINER),
        Triple("ON_GREEN on GOLD (style badges)", P.ON_GREEN, P.GOLD),
        Triple("GREEN on GREEN_CONTAINER (selected chips / nav)", P.GREEN, P.GREEN_CONTAINER),
        Triple("ON_DARK on GOLD_CONTAINER", P.ON_DARK, P.GOLD_CONTAINER),
    )

    private val nonText = listOf(
        "OUTLINE" to P.OUTLINE, "GREEN" to P.GREEN, "GREEN_BRIGHT" to P.GREEN_BRIGHT, "GOLD" to P.GOLD,
        "CURVE_MEASURED" to P.CURVE_MEASURED,
    )

    @Test
    fun formulaMatchesWcagReferenceValues() {
        assertEquals(21.0, contrast(0xFF000000, 0xFFFFFFFF), 0.01)
        assertEquals(1.0, contrast(0xFF777777, 0xFF777777), 1e-9)
        assertEquals(4.48, contrast(0xFF777777, 0xFFFFFFFF), 0.01)
    }

    @Test
    fun allTextReachesAA() {
        val problems = mutableListOf<String>()
        for ((fn, f) in textColours) for ((bn, b) in backgrounds) {
            val c = contrast(f, b); if (c < 4.5) problems += "$fn on $bn = %.2f".format(c)
        }
        for ((name, f, b) in textOnColour) { val c = contrast(f, b); if (c < 4.5) problems += "$name = %.2f".format(c) }
        assertTrue("WCAG 1.4.3 failures:\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun primaryTextIsAAA() {
        for ((bn, b) in backgrounds) assertTrue("ON_DARK on $bn", contrast(P.ON_DARK, b) >= 7.0)
    }

    @Test
    fun uiComponentsAndChartLinesReach3to1() {
        val problems = mutableListOf<String>()
        for ((n, f) in nonText) for ((bn, b) in backgrounds) {
            val c = contrast(f, b); if (c < 3.0) problems += "$n on $bn = %.2f".format(c)
        }
        assertTrue("WCAG 1.4.11 failures:\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun gridStaysSubtle() {
        // Grid lines must not compete with the curves (but stay visible).
        val c = contrast(P.GRID, P.SURFACE)
        assertTrue("grid contrast $c", c in 1.1..2.0)
    }

    @Test
    fun brandColoursAreTheSpecifiedOnes() {
        // measured from the brand material: intro-video backdrop, logo outer ring, logo inner ring
        assertEquals(0xFF1E262D, P.BACKGROUND)
        assertEquals(0xFF1EED61, P.GREEN)
        assertEquals(0xFFFCD207, P.GOLD)
    }

    @Test
    fun slateRampIsMonotonicAndContainsTheCorePalette() {
        val l = P.SLATE_RAMP.map { luminance(it) }
        for (i in 1 until l.size) assertTrue("ramp step $i not lighter", l[i] > l[i - 1])
        for (c in listOf(P.BACKGROUND, P.BACKGROUND_DEEP, P.SURFACE, P.SURFACE_HIGH, P.ON_DARK, P.ON_DARK_MUTED)) {
            assertTrue("0x%08X missing in SLATE_RAMP".format(c), P.SLATE_RAMP.contains(c))
        }
    }

    @Test
    fun xmlPaletteMirrorsComposePalette() {
        val doc = ProjectFiles.xml(ProjectFiles.module("src/main/res/values/autovybz_colors.xml"))
        val list = doc.getElementsByTagName("color")
        val byConst = mapOf(
            "BACKGROUND" to P.BACKGROUND, "BACKGROUND_DEEP" to P.BACKGROUND_DEEP, "SURFACE" to P.SURFACE,
            "SURFACE_HIGH" to P.SURFACE_HIGH, "OUTLINE" to P.OUTLINE, "GREEN" to P.GREEN, "GREEN_BRIGHT" to P.GREEN_BRIGHT,
            "GREEN_CONTAINER" to P.GREEN_CONTAINER, "GOLD" to P.GOLD, "GOLD_CONTAINER" to P.GOLD_CONTAINER,
            "ON_DARK" to P.ON_DARK, "ON_DARK_MUTED" to P.ON_DARK_MUTED, "ON_GREEN" to P.ON_GREEN, "ERROR" to P.ERROR,
            "GRID" to P.GRID, "CURVE_MEASURED" to P.CURVE_MEASURED,
        )
        val seen = mutableSetOf<String>()
        for (i in 0 until list.length) {
            val e = list.item(i) as org.w3c.dom.Element
            val name = e.getAttribute("name")
            if (!name.startsWith("avz_")) continue
            val key = name.removePrefix("avz_").uppercase()
            val expected = byConst[key] ?: error("$name has no AutoVybzPalette constant")
            val value = e.textContent.trim().removePrefix("#").toLong(16)
            assertEquals("$name mirror", expected, value)
            seen += key
        }
        assertEquals("palette constants missing in XML", byConst.keys, seen)
    }
}
