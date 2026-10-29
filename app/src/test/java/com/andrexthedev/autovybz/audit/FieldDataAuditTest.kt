package com.andrexthedev.autovybz.audit

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The field log is the only place where the app writes something *about* the user's measurements, so it
 * gets the same treatment as the data it collects: a rule per property, checked, not remembered.
 *
 * The four questions it exists for are in docs/FIELD_DATA.md. What this test protects is the plumbing in
 * between: that a calibration really records a sample, that the analyser still knows all four questions,
 * and that the record stays a dozen numbers (no curves, no names) while the file stays bounded.
 */
class FieldDataAuditTest {

    private fun src(path: String) = ProjectFiles.module("src/main/java/com/andrexthedev/autovybz/$path").readText()

    @Test
    fun everyCalibrationRecordsOneSample() {
        val vm = src("ui/wizard/WizardViewModel.kt")
        assertTrue("the wizard builds a sample", "FieldLog.sample(" in vm)
        assertTrue("and stores it", "FieldLogStore.record(" in vm)
        // The level the run actually used has to be read before the volume is restored, otherwise every
        // W3 sample would claim 70 %.
        assertTrue(
            "the measured volume is taken before restoring",
            "val measuredFraction = volume.currentFraction" in vm,
        )
        assertTrue(
            "the volume is restored before the record is written",
            vm.indexOf("if (settings.restoreVolumeAfter) volume.restore()") < vm.indexOf("recordFieldSample(outcome"),
        )
        // W11's applied step belongs to the session that took it.
        assertTrue("the level step is reset per session", "levelStepDb = 0.0" in vm)
    }

    @Test
    fun theRecordStaysAnonymousAndBounded() {
        val log = src("measurement/FieldLog.kt")
        assertFalse("FieldLog must stay pure Kotlin (no Android types)", "import android." in log)
        val store = src("measurement/FieldLogStore.kt")
        assertTrue("the file is capped", "MAX_SAMPLES" in store && "takeLast(MAX_SAMPLES)" in store)
        assertTrue("appending never throws into a calibration", "Log.w(TAG" in store)
        assertTrue("nothing is uploaded: no network in the store", "java.net" !in store && "okhttp" !in store)
        // Collection is a switch the user owns, and it defaults to on only because it stays local.
        assertTrue("the switch exists", "var fieldLog: Boolean" in src("settings/AutoVybzSettings.kt"))
    }

    @Test
    fun theStudioCardShowsTheCountBeforeItOffersTheFile() {
        val pro = src("ui/pro/ProScreen.kt")
        assertTrue("the card is in Studio", "FieldDataCard()" in pro)
        val card = src("ui/pro/FieldDataCard.kt")
        for (tag in listOf("avz:field:toggle", "avz:field:count", "avz:field:export", "avz:field:delete")) {
            assertTrue("$tag must exist", tag in card)
        }
        assertTrue(
            "export is disabled while there is nothing to export",
            "enabled = count > 0" in card,
        )
        // A beta tester must be able to hand over *their* round only, not every calibration the phone made.
        assertTrue("the card can mark the start of a round", "avz:field:mark" in card)
        assertTrue("the export honours the mark", "FieldLogStore.text(context, mark)" in card)
        assertTrue("and the card says how many are in it", "avz:field:since" in card)
    }

    @Test
    fun theAnalyserKnowsAllFourQuestions() {
        val analyser = ProjectFiles.root("tools/fielddata/analyze.py").readText()
        for (question in listOf("W3", "W4", "W5", "W11")) {
            assertTrue("$question must be analysed", "## $question" in analyser)
        }
        // A threshold that can move without a decision is not a pre-registered rule.
        assertTrue("the analyser refuses to act on a handful of samples", "MIN_SAMPLES" in analyser)
        assertTrue("and it checks the app's constants", "def check()" in analyser)
        assertTrue(
            "the report names the field the app writes",
            "thdPercent" in analyser && "rt60S" in analyser && "deviations" in analyser,
        )
    }
}
