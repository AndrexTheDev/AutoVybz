package com.andrexthedev.autovybz.e2e

import androidx.compose.ui.semantics.getOrNull
import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.anyIntent
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.rule.GrantPermissionRule
import com.andrexthedev.autovybz.ui.AutoVybzActivity
import org.junit.Before
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.rules.TestRule

typealias AvzRule = AndroidComposeTestRule<ActivityScenarioRule<AutoVybzActivity>, AutoVybzActivity>

/** Base class: microphone granted, simulated room installed, fresh app data, AutoVybzActivity launched. */
abstract class E2eTest(
    countdownSeconds: Int = 0,
    grantMicrophone: Boolean = true,
    intro: Boolean = false,
    premiumTrial: Boolean = true,
) {
    val compose: AvzRule = createAndroidComposeRule<AutoVybzActivity>()

    private val permission: TestRule =
        if (grantMicrophone) GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO) else RuleChain.emptyRuleChain()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(permission).around(E2eRule(countdownSeconds, intro, premiumTrial)).around(compose)

    /**
     * Beta campaign API 35: one stuck *system* window (launcher ANR dialog) made every following test fail
     * with "the root of the view hierarchy never had window focus" - eleven tests, one cause, and the message
     * never named the window. This guard waits for the app window, dismisses system dialogs once, and if focus
     * still does not come it fails *here* with the owning window in the message, so the next report says
     * whether an app screen or a system window is at fault.
     */
    @Before
    fun ensureTheAppWindowHasFocus() {
        if (runCatching { waitForFocus(8_000) }.isSuccess) return
        E2e.shell("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS")
        val owner = E2e.focusedWindow()
        try {
            waitForFocus(10_000)
        } catch (e: Throwable) {
            throw AssertionError("no window focus for the app; focus owner: $owner", e)
        }
    }

    private fun waitForFocus(timeoutMs: Long) {
        compose.waitUntil(timeoutMs) {
            var focused = false
            compose.activityRule.scenario.onActivity { focused = it.hasWindowFocus() }
            focused
        }
    }

    fun str(@StringRes id: Int, vararg args: Any): String = E2e.app.getString(id, *args)
    fun shot(name: String) = Shots.take(compose, name)
}

const val TIMEOUT_MS = 60_000L

fun ComposeTestRule.has(tag: String): Boolean = onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

fun ComposeTestRule.hasText(text: String, substring: Boolean = true): Boolean =
    onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

/**
 * waitUntil that survives transient synchronisation errors of the Compose test framework (seen on API 30:
 * "performMeasureAndLayout called during measure layout" thrown from Espresso.onIdle). The condition is
 * retried; only if it never becomes true is the last error reported as the cause.
 */
fun ComposeTestRule.waitRobust(what: String, timeoutMs: Long, condition: () -> Boolean) {
    var last: Throwable? = null
    try {
        waitUntil(timeoutMs) {
            try { condition() } catch (e: RuntimeException) { last = e; false }
        }
    } catch (e: Throwable) {
        val tags = runCatching { visibleTags().toString() }.getOrElse { "unavailable: ${it.message}" }
        throw AssertionError("$what within $timeoutMs ms. Visible tags: $tags", last ?: e)
    }
}

fun ComposeTestRule.await(tag: String, timeoutMs: Long = TIMEOUT_MS) =
    waitRobust("'$tag' did not appear", timeoutMs) { has(tag) }

fun ComposeTestRule.awaitText(text: String, timeoutMs: Long = TIMEOUT_MS) =
    waitRobust("text '$text' did not appear", timeoutMs) { hasText(text) }

fun ComposeTestRule.awaitGone(tag: String, timeoutMs: Long = TIMEOUT_MS) =
    waitRobust("'$tag' did not disappear", timeoutMs) { !has(tag) }

/** Waits for the first of [tags] to appear and returns it. */
fun ComposeTestRule.awaitAny(vararg tags: String, timeoutMs: Long = TIMEOUT_MS): String {
    var found: String? = null
    waitRobust("none of ${tags.toList()} appeared", timeoutMs) { found = tags.firstOrNull { has(it) }; found != null }
    return found!!
}

/**
 * Campaign 16 (API 30): a systemui ANR froze the notification shade and the launcher took the window focus
 * in the middle of a suite. An input event injected into a window that does not own the focus is dropped by
 * the framework, so the click did nothing - and the test then waited a minute for a screen that was never
 * coming, while the button it had "tapped" was clearly visible. The app was fine; the harness assumed the
 * window keeps the focus.
 *
 * So every tap checks first and, when the focus is elsewhere, closes system dialogs and brings the task back
 * (`am start`, the same recovery the shell runner performs between suites) before injecting the event. If the
 * focus still does not come, the failure names the window that has it instead of a mysterious timeout.
 */
fun ComposeTestRule.tap(tag: String) {
    await(tag)
    val node = onNodeWithTag(tag)
    runCatching { node.performScrollTo() }
    (this as? AvzRule)?.ensureWindowFocus()
    node.performClick()
    waitForIdle()
}

/** Throws when the app window cannot get the focus back; logs when the recovery had to run. */
private fun AvzRule.ensureWindowFocus() {
    fun activityFocused(): Boolean = runCatching {
        var f = false
        activityRule.scenario.onActivity { f = it.hasWindowFocus() }
        f
    }.getOrDefault(true) // no activity (rule-less suites): never interfere
    /**
     * A dialog of our own (the delete confirmation, for instance) also makes `hasWindowFocus()` false - its
     * *own* window owns the focus, which is exactly as it should be. Only a foreign window is our problem,
     * so the cheap check is followed by one look at who actually holds the focus.
     */
    fun focused(): Boolean = activityFocused() || E2e.focusedWindow().contains(E2e.PKG)
    if (focused()) return
    android.util.Log.w("AvzE2E", "tap(): the app window has no focus - asking for it back")
    E2e.shell("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS")
    E2e.shell("am start -n ${E2e.PKG}/com.andrexthedev.autovybz.ui.AutoVybzActivity")
    val deadline = System.currentTimeMillis() + 10_000
    while (System.currentTimeMillis() < deadline) {
        runCatching { waitForIdle() }
        if (focused()) return
        Thread.sleep(200)
    }
    throw AssertionError("the app window never got the focus back; focus owner: ${E2e.focusedWindow()}")
}

fun ComposeTestRule.editableText(tag: String): String =
    onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text ?: ""

/** All test tags currently in the tree (diagnostics for failing waits). */
fun ComposeTestRule.visibleTags(): List<String> =
    onAllNodes(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(SemanticsProperties.TestTag), useUnmergedTree = true)
        .fetchSemanticsNodes().mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }.distinct()

// ---- wizard flows -----------------------------------------------------------------------------

fun ComposeTestRule.openSafety(environment: String? = null, positions: Int? = null, subwoofer: Int? = null) {
    await("avz:start")
    environment?.let { tap("avz:env:$it") }
    positions?.let { tap("avz:positions:$it") }
    // W8: the subwoofer chips live on the same start screen and are optional - 0 means "no sub".
    subwoofer?.let { tap("avz:sub:$it") }
    tap("avz:start")
    await("avz:safety:start")
}

fun ComposeTestRule.confirmSafetyAndStart() {
    if (has("avz:safety:speaker")) tap("avz:safety:speaker")
    tap("avz:safety:confirm")
    tap("avz:safety:start")
    // Slow emulators (API 28) occasionally swallowed this tap: the start button was still disabled because the
    // confirm tick had not been recomposed yet. Retry once - logged, and only if the screen really did not move on.
    val moved = runCatching { waitUntil(8_000) { !has("avz:safety:start") }; true }.getOrDefault(false)
    if (!moved) {
        val disabled = onNodeWithTag("avz:safety:start").fetchSemanticsNode().config.contains(SemanticsProperties.Disabled)
        android.util.Log.w("AvzE2E", "safety start did not proceed (button disabled=$disabled) - retrying once")
        if (disabled) {
            ensureChecked("avz:safety:speaker")
            ensureChecked("avz:safety:confirm")
        }
        tap("avz:safety:start")
    }
}

/** Taps a checkbox/switch only if it is present and reports "off" (a second tap would untick it). */
private fun ComposeTestRule.ensureChecked(tag: String) {
    if (!has(tag)) return
    val state = onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ToggleableState)
    if (state == androidx.compose.ui.state.ToggleableState.Off) tap(tag)
}

/** Answers every "move the phone" screen with continue/finish until the result appears. */
fun ComposeTestRule.measureToResult(maxSteps: Int = 8) {
    repeat(maxSteps) {
        when (awaitAny("avz:result", "avz:move:continue", "avz:move:finish", "avz:error")) {
            "avz:result" -> return
            "avz:move:continue" -> tap("avz:move:continue")
            "avz:move:finish" -> tap("avz:move:finish")
            "avz:error" -> throw AssertionError("unexpected error screen: ${visibleTags()}")
        }
    }
    await("avz:result")
}

fun ComposeTestRule.quickCalibration(environment: String = "INDOOR"): String {
    openSafety(environment, positions = 1)
    confirmSafetyAndStart()
    measureToResult()
    return editableText("avz:result:name")
}

/** Records outgoing intents and stubs them (no other app / activity is really opened). */
inline fun withIntents(block: () -> Unit) {
    Intents.init()
    try {
        intending(anyIntent()).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
        block()
    } finally {
        Intents.release()
    }
}

/** Saved screen -> Studio tab (tag PRO). (avz:saved:pro opens the classic parametric EQ activity for fine-tuning instead.) */
fun ComposeTestRule.savedToProTab() {
    tap("avz:saved:done")
    tap("avz:tab:PRO")
    await("avz:screen:pro")
}
