package com.andrexthedev.autovybz.preset

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Display-name rules for calibrations. They exist because of a real beta finding: the Sound screen showed
 * the stored identifier `AutoVybz_sdkgphonex8664_2026-10-28` in a narrow hero column, wrapping to three lines
 * and pushing the speaker picker off screen (campaign on `139bea7`, screenshots de-phone / es-small-font130).
 */
class PresetDisplayNameTest {

    @Test
    fun generatedNamesAreReducedToTheDevicePart() {
        assertEquals("JBL-Flip-6", PresetDisplayName.compact("AutoVybz_JBL-Flip-6_2026-10-28"))
        assertEquals("sdkgphonex8664", PresetDisplayName.compact("AutoVybz_sdkgphonex8664_2026-10-28"))
    }

    @Test
    fun theDateAndTheDeduplicationCounterBothFallAway() {
        assertEquals("JBL Flip 6", PresetDisplayName.compact("AutoVybz_JBL Flip 6_2026-10-28_2"))
        assertEquals("Treppe", PresetDisplayName.compact("AutoVybz_Treppe_2024-01-05_13"))
    }

    @Test
    fun handNamedCalibrationsAreNeverShortened() {
        // No generated date -> the user typed this name; it is not ours to trim.
        assertEquals("Gartenparty", PresetDisplayName.compact("Gartenparty"))
        assertEquals("Wohnzimmer (abends)", PresetDisplayName.compact("Wohnzimmer (abends)"))
        // A generated name without a device part keeps its prefix instead of collapsing to an empty chip.
        assertEquals("AutoVybz", PresetDisplayName.compact("AutoVybz_2026-10-28"))
    }

    @Test
    fun emptyOrDegenerateNamesFallBackToTheStoredName() {
        assertEquals("AutoVybz__2026-10-28", PresetDisplayName.compact("AutoVybz__2026-10-28"))
        assertEquals("", PresetDisplayName.compact(""))
    }

    @Test
    fun theStoredSpeakerNameWinsOverThePresetName() {
        assertEquals("Küche", PresetDisplayName.of("Küche", "AutoVybz_x_2026-10-28"))
        assertEquals("Küche", PresetDisplayName.of("  Küche ", "AutoVybz_x_2026-10-28").trim())
        assertEquals("x", PresetDisplayName.of(null, "AutoVybz_x_2026-10-28"))
        assertEquals("x", PresetDisplayName.of("   ", "AutoVybz_x_2026-10-28"))
    }

    @Test
    fun labelsStayUniqueWithinTheSpeakerPicker() {
        val labels = listOf("JBL Flip 6", "JBL Flip 6", "JBL Flip 6", "Küche")
        val times = listOf(1000L, 2000L, 2000L, 3000L)
        val shown = PresetDisplayName.uniqueLabels(labels, times) { "Tag${it / 1000}" }
        assertEquals(listOf("JBL Flip 6 · Tag1", "JBL Flip 6 · Tag2", "JBL Flip 6 (2)", "Küche"), shown)
        assertEquals("every calibration must get its own label", shown.size, shown.toSet().size)
    }

    @Test
    fun aUniqueLabelIsLeftAlone() {
        assertEquals(listOf("Küche", "Bad"), PresetDisplayName.uniqueLabels(listOf("Küche", "Bad"), listOf(1L, 2L)) { "x" })
    }

    @Test
    fun theRealisticCampaignSequenceStaysReadable() {
        val names = listOf("AutoVybz_sdkgphonex8664_2026-10-28", "AutoVybz_JBL Flip 6_2026-10-27")
        val shown = names.map { PresetDisplayName.of(null, it) }
        assertEquals(listOf("sdkgphonex8664", "JBL Flip 6"), shown)
        // 24 characters was the old hero line; the compact form must stay clearly below it.
        assertTrue(shown.all { it.length <= 20 })
    }
}
