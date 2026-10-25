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
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.rules.TestRule

typealias AvzRule = AndroidComposeTestRule<ActivityScenarioRule<AutoVybzActivity>, AutoVybzActivity>

/** Base class: microphone granted, simulated room installed, fresh app data, AutoVybzActivity launched. */
abstract class E2eTest(countdownSeconds: Int = 0, grantMicrophone: Boolean = true, intro: Boolean = false) {
    val compose: AvzRule = createAndroidComposeRule<AutoVybzActivity>()

    private val permission: TestRule =
        if (grantMicrophone) GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO) else RuleChain.emptyRuleChain()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(permission).around(E2eRule(countdownSeconds, intro)).around(compose)

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

fun ComposeTestRule.tap(tag: String) {
    await(tag)
    val node = onNodeWithTag(tag)
    runCatching { node.performScrollTo() }
    node.performClick()
    waitForIdle()
}

fun ComposeTestRule.editableText(tag: String): String =
    onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text ?: ""

/** All test tags currently in the tree (diagnostics for failing waits). */
fun ComposeTestRule.visibleTags(): List<String> =
    onAllNodes(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(SemanticsProperties.TestTag), useUnmergedTree = true)
        .fetchSemanticsNodes().mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }.distinct()

// ---- wizard flows -----------------------------------------------------------------------------

fun ComposeTestRule.openSafety(environment: String? = null, positions: Int? = null) {
    await("avz:start")
    environment?.let { tap("avz:env:$it") }
    positions?.let { tap("avz:positions:$it") }
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
