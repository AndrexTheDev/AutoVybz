package com.andrexthedev.autovybz.audit

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for the beta finding of campaign `139bea7` (screenshots de-phone / es-small-font130 and the
 * accessibility suite on API 28/30/35): every place that *shows* a calibration to the user rendered the raw
 * stored identifier `AutoVybz_<device>_<yyyy-MM-dd>`, which wrapped to three lines in the Sound hero and was
 * reported as "clipped text" by `AccessibilityE2ETest.everyScreenMeetsTheBasics`.
 *
 * The rule checked here: user-visible calibration names come from [com.andrexthedev.autovybz.preset.PresetDisplayName],
 * while stored identifiers stay the key for files, test tags and the engine.
 */
class PresetDisplayNameUsageAuditTest {

    private fun src(path: String) = ProjectFiles.module("src/main/java/com/andrexthedev/autovybz/$path").readText()

    @Test
    fun everyUserVisibleCalibrationNameGoesThroughTheDisplayHelper() {
        val home = src("ui/home/SoundScreen.kt")
        assertTrue(
            "Sound hero / speaker picker must show the display name",
            "PresetDisplayName.of(p.speakerName, p.name)" in home,
        )
        assertFalse(
            "the raw preset name must not be rendered directly any more",
            "?: p.name" in home,
        )

        val pro = src("ui/pro/ProScreen.kt")
        assertTrue("Pro list title must be readable", "val label = PresetDisplayName.of(" in pro)
        assertTrue("the applied toast must use the same label", "avz_applied_toast, label)" in pro)

        val share = src("ui/share/ResultCardRenderer.kt")
        assertTrue(
            "the share card falls back to the display name, not the file identifier",
            "ifEmpty { PresetDisplayName.compact(presetName) }" in share,
        )
    }

    @Test
    fun storedIdentifiersStayTheKeyForFilesTagsAndEngineCalls() {
        // Shortening is display only: the tags that the instrumented suites tap and the names handed to the
        // engine must remain the stored identifiers.
        val home = src("ui/home/SoundScreen.kt")
        assertTrue("speaker chips keep the stored name as test tag", "avz:home:speaker:\${p.name}" in home)
        assertTrue("engine call keeps the stored name", "VybzSound.useProfile(context, p?.name)" in home)

        val pro = src("ui/pro/ProScreen.kt")
        assertTrue("apply keeps the stored name", "VybzSound.useProfile(context, p.name)" in pro)
        assertTrue("delete keeps the stored name", "toDelete = p.name" in pro)
        assertTrue("export keeps the stored name", "preset = { p }" in pro)
    }

    @Test
    fun theHelperIsPureAndUnitTested() {
        val helper = src("preset/PresetDisplayName.kt")
        assertFalse("no Android imports - the rules must be unit-testable", "android." in helper)
        assertFalse("no Compose imports", "androidx.compose" in helper)
        assertTrue(
            "the unit test must exist next to the helper",
            ProjectFiles.module("src/test/java/com/andrexthedev/autovybz/preset/PresetDisplayNameTest.kt").exists(),
        )
    }
}
