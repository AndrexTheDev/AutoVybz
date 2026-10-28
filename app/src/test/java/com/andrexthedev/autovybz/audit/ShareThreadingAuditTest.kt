package com.andrexthedev.autovybz.audit

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The share card is the only place in AutoVybz that encodes a bitmap: 1080 x 1350 px at quality 100, plus a
 * cache clean-up. Doing that inside `onClick` blocks the main thread for as long as the encode takes - on the
 * software-rendered API 30 emulator long enough to produce "Input dispatching timed out" ANRs under monkey
 * (campaigns of 139bea7 and d251336). This audit keeps the work on Dispatchers.IO.
 */
class ShareThreadingAuditTest {

    private val sharer = ProjectFiles.module(
        "src/main/java/com/andrexthedev/autovybz/ui/share/ResultSharer.kt",
    ).readText()

    private val wizard = ProjectFiles.module(
        "src/main/java/com/andrexthedev/autovybz/ui/wizard/WizardScreen.kt",
    ).readText()

    @Test
    fun theCardIsRenderedOffTheMainThread() {
        assertTrue("share must be a suspend function", "suspend fun share(" in sharer)
        assertTrue(
            "the bitmap/PNG work must run on Dispatchers.IO",
            "withContext(Dispatchers.IO) { writeCard(" in sharer,
        )
        // The remaining body (FileProvider uri, chooser intent) is cheap and must stay on the caller's thread,
        // otherwise startActivity would run off main.
        // Only the rest of share(), not writeCard() further down the file (which is where the render call lives).
        val body = sharer
            .substringAfter("withContext(Dispatchers.IO) { writeCard(context, outcome, presetName) }")
            .substringBefore("private fun writeCard")
        assertTrue("the chooser must still be started from the caller", "context.startActivity(chooser)" in body)
        assertFalse("no second, blocking encode", "ResultCardRenderer.render" in body)
    }

    @Test
    fun theShareButtonLaunchesACoroutineInsteadOfBlockingTheClickHandler() {
        val button = wizard.substringAfter("private fun ShareResultButton(").substringBefore("\n}\n")
        assertTrue("the button needs a scope", "rememberCoroutineScope()" in button)
        assertTrue("the click must launch, not call the renderer directly", "scope.launch { ResultSharer.share(" in button)
        assertFalse(
            "a direct call in onClick would block the main thread again",
            "onClick = { ResultSharer.share(" in button,
        )
    }
}
