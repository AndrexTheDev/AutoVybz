package com.andrexthedev.autovybz.e2e

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andrexthedev.autovybz.e2e.SimulatedRoomEngine.Mode
import com.bearinmind.equalizer314.R
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Accessibility + layout audit on every screen: touch targets >= 48 dp, every control has a label for
 * TalkBack, every screen has a heading, and no text is clipped (catches long German/Spanish strings and
 * large font scales - run the matrix with font_scale 1.3).
 */
@RunWith(AndroidJUnit4::class)
class AccessibilityE2ETest : E2eTest() {

    private val problems = mutableListOf<String>()

    private fun label(n: SemanticsNode): String {
        val c = n.config
        return (c.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
            c.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            listOfNotNull(c.getOrNull(SemanticsProperties.EditableText)?.text) +
            listOfNotNull(c.getOrNull(SemanticsProperties.StateDescription)))
            .filter { it.isNotBlank() }.joinToString(" / ")
    }

    private fun audit(screen: String, rule: ComposeTestRule = compose) {
        rule.waitForIdle()
        val minPx = with(rule.density) { 48.dp.toPx() } - 1.5f
        val dp = rule.density.density
        for (n in rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()) {
            val b = n.boundsInRoot
            if (b.width <= 0f || b.height <= 0f) continue // off-screen / not laid out
            val l = label(n)
            if (b.width < minPx || b.height < minPx) problems += "$screen: touch target %.0fx%.0f dp '%s'".format(b.width / dp, b.height / dp, l.take(40))
            if (l.isBlank()) problems += "$screen: control without label at $b"
        }
        rule.runOnIdle {
            for (n in rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult)).fetchSemanticsNodes()) {
                val results = mutableListOf<TextLayoutResult>()
                n.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
                if (results.any { it.hasVisualOverflow }) problems += "$screen: clipped text '${label(n).take(60)}'"
            }
        }
        if (rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).fetchSemanticsNodes().isEmpty() &&
            !rule.has("avz:dialog:confirm") && !rule.has("avz:donate:close")
        ) problems += "$screen: no heading"
    }

    private fun expandAllCards() {
        val collapsed = str(R.string.avz_a11y_collapsed)
        repeat(12) {
            val nodes = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, collapsed))
            if (nodes.fetchSemanticsNodes().isEmpty()) return
            nodes[0].let { runCatching { it.performScrollTo() }; it.performClick() }
            compose.waitForIdle()
        }
    }

    @Test
    fun everyScreenMeetsTheBasics() {
        compose.await("avz:start"); audit("wizard idle")
        compose.tap("avz:env:OUTDOOR"); audit("wizard idle outdoor")

        E2e.route = E2e.unknownSpeaker()
        compose.openSafety("INDOOR", 3); audit("safety")
        E2e.engine.hold = CompletableDeferred()
        compose.confirmSafetyAndStart()
        compose.await("avz:measuring"); audit("measuring")
        E2e.engine.hold!!.complete(Unit); E2e.engine.hold = null
        compose.await("avz:move"); audit("move phone")
        compose.tap("avz:move:finishEarly")
        compose.await("avz:result"); audit("result")
        compose.tap("avz:result:again"); compose.await("avz:dialog:confirm"); audit("discard dialog")
        compose.tap("avz:dialog:dismiss")
        compose.tap("avz:result:saveApply"); compose.await("avz:saved"); audit("saved")

        compose.tap("avz:saved:pro"); compose.await("avz:screen:pro")
        expandAllCards(); audit("pro (all cards expanded)")
        compose.tap("avz:tab:HELP"); compose.await("avz:screen:help")
        expandAllCards(); audit("help (all expanded)")
        compose.tap("avz:tab:ABOUT"); compose.await("avz:screen:about"); audit("about")
        compose.tap("avz:about:donate"); compose.await("avz:donate:close"); audit("donate dialog")
        compose.tap("avz:donate:close")

        compose.tap("avz:tab:WIZARD")
        E2e.route = E2e.headphones()
        compose.tap("avz:start"); compose.await("avz:blocked"); audit("route blocked")
        E2e.route = E2e.speaker()
        compose.tap("avz:blocked:back")
        E2e.engine.script.add(Mode.SILENCE)
        compose.openSafety("INDOOR", 1); compose.confirmSafetyAndStart()
        compose.await("avz:error"); audit("error")

        assertTrue("${problems.size} accessibility problems:\n" + problems.joinToString("\n"), problems.isEmpty())
    }
}

