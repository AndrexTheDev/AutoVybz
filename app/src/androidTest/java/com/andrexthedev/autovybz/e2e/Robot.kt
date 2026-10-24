package com.andrexthedev.autovybz.e2e

import androidx.compose.ui.semantics.getOrNull
import android.Manifest
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
abstract class E2eTest(countdownSeconds: Int = 0, grantMicrophone: Boolean = true) {
    val compose: AvzRule = createAndroidComposeRule<AutoVybzActivity>()

    private val permission: TestRule =
        if (grantMicrophone) GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO) else RuleChain.emptyRuleChain()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(permission).around(E2eRule(countdownSeconds)).around(compose)

    fun str(@StringRes id: Int, vararg args: Any): String = E2e.app.getString(id, *args)
    fun shot(name: String) = Shots.take(compose, name)
}

const val TIMEOUT_MS = 60_000L

fun ComposeTestRule.has(tag: String): Boolean = onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

fun ComposeTestRule.hasText(text: String, substring: Boolean = true): Boolean =
    onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

fun ComposeTestRule.await(tag: String, timeoutMs: Long = TIMEOUT_MS) {
    try {
        waitUntil(timeoutMs) { has(tag) }
    } catch (e: Throwable) {
        throw AssertionError("'$tag' did not appear within $timeoutMs ms. Visible tags: ${visibleTags()}", e)
    }
}

fun ComposeTestRule.awaitText(text: String, timeoutMs: Long = TIMEOUT_MS) {
    try {
        waitUntil(timeoutMs) { hasText(text) }
    } catch (e: Throwable) {
        throw AssertionError("text '$text' did not appear within $timeoutMs ms. Visible tags: ${visibleTags()}", e)
    }
}

fun ComposeTestRule.awaitGone(tag: String, timeoutMs: Long = TIMEOUT_MS) = waitUntil(timeoutMs) { !has(tag) }

/** Waits for the first of [tags] to appear and returns it. */
fun ComposeTestRule.awaitAny(vararg tags: String, timeoutMs: Long = TIMEOUT_MS): String {
    var found: String? = null
    try {
        waitUntil(timeoutMs) { found = tags.firstOrNull { has(it) }; found != null }
    } catch (e: Throwable) {
        throw AssertionError("none of ${tags.toList()} appeared. Visible tags: ${visibleTags()}", e)
    }
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
