package com.andrexthedev.autovybz.audit

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "Check" run (W6) is only worth the ten seconds it costs if the user can see it afterwards.
 *
 * Round 15 taught the check to travel with the result (share card, APO, REW, `*.avz.json`); this guards the
 * other half - every screen that *lists* a stored calibration has to show the badge, and all of them have to
 * agree on what a verdict is called. The failure mode this prevents is silent: a second `when (verdict)`
 * block that forgets a verdict renders the wrong sentence without anything failing.
 */
class VerificationBadgeUsageAuditTest {

    private fun src(path: String) = ProjectFiles.module("src/main/java/com/andrexthedev/autovybz/$path").readText()

    @Test
    fun everyCalibrationListShowsTheCheck() {
        val home = src("ui/home/SoundScreen.kt")
        assertTrue(
            "the speaker chips must pass the stored report",
            "report = p.measurement?.verification," in home,
        )
        assertTrue("the chip renders the badge", "VerificationBadge(report, iconOnly = true)" in home)

        val pro = src("ui/pro/ProScreen.kt")
        assertTrue(
            "the Pro list must render the badge for a checked calibration",
            "p.measurement?.verification?.let {" in pro && "VerificationBadge(it)" in pro,
        )
    }

    @Test
    fun theVerdictIsWordedInExactlyOnePlace() {
        val badge = src("ui/components/VerificationBadge.kt")
        assertTrue("the shared mapping exists", "fun verificationVerdictLabel(verdict: Verification.Verdict)" in badge)
        // Every verdict is mapped; a new one breaks this test instead of silently rendering nothing.
        for (verdict in listOf("AS_PREDICTED", "BETTER_THAN_PREDICTED", "WEAKER_THAN_PREDICTED", "NO_CHANGE", "UNEXPECTED")) {
            assertTrue("$verdict must be mapped", "Verification.Verdict.$verdict ->" in badge)
        }
        // No second copy of the mapping in the surfaces that show it.
        assertFalse(
            "the wizard card uses the shared mapping",
            "Verification.Verdict.NO_CHANGE -> R.string" in src("ui/wizard/WizardScreen.kt"),
        )
        assertTrue(
            "the wizard card asks the shared helper",
            "stringResource(verificationVerdictLabel(report.verdict))" in src("ui/wizard/WizardScreen.kt"),
        )
    }

    /** "Not checked" is the absence of a badge, not a third state - a null report must render nothing. */
    @Test
    fun anUncheckedCalibrationRendersNoBadge() {
        val badge = src("ui/components/VerificationBadge.kt")
        assertTrue("null returns before drawing", "if (report == null) return" in badge)
        assertFalse(
            "no 'not checked' string may exist - that state is the empty chip / missing line",
            ProjectFiles.strings(ProjectFiles.module("src/main/res/values/autovybz_strings.xml"))
                .keys.any { it.startsWith("avz_check_not") || it.contains("unchecked") },
        )
    }
}
