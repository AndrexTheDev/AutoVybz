package com.andrexthedev.autovybz.e2e

import androidx.compose.ui.semantics.getOrNull
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

    /** Dialogs are separate roots (own window): only compare controls within the same window. */
    private fun rootOf(n: SemanticsNode): Int {
        var x = n
        while (true) x = x.parent ?: return x.id
    }

    private fun audit(screen: String, rule: ComposeTestRule = compose) {
        rule.waitForIdle()
        val minPx = with(rule.density) { 48.dp.toPx() } - 1.5f
        val absMinPx = with(rule.density) { 24.dp.toPx() } - 1.5f
        val dp = rule.density.density
        val clickables = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
            .filter { it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f } // off-screen / not laid out
        for (n in clickables) {
            val b = n.boundsInRoot
            val l = label(n)
            if (l.isBlank()) problems += "$screen: control without label at $b"
            // Text fields: the semantics node is the inner text line, the tappable area is the whole field.
            if (n.config.contains(SemanticsActions.SetText)) continue
            if (b.width >= minPx && b.height >= minPx) continue
            // Smaller visuals are fine when Compose's 48 dp touch expansion has room (Material chips/buttons
            // reserve it via minimumInteractiveComponentSize) - the WCAG 2.5.8 spacing rule: >= 24 dp, and the
            // 48 dp box around the centre overlaps no other control.
            val c = b.center
            val box = androidx.compose.ui.geometry.Rect(c.x - maxOf(b.width, minPx) / 2, c.y - maxOf(b.height, minPx) / 2,
                c.x + maxOf(b.width, minPx) / 2, c.y + maxOf(b.height, minPx) / 2)
            val crowded = clickables.any { o -> o.id != n.id && rootOf(o) == rootOf(n) && o.boundsInRoot.overlaps(box) && !o.boundsInRoot.contains(c) && !b.contains(o.boundsInRoot.center) }
            if (b.width < absMinPx || b.height < absMinPx || crowded)
                problems += "$screen: touch target %.0fx%.0f dp%s '%s'".format(b.width / dp, b.height / dp, if (crowded) " (crowded)" else "", l.take(40))
        }
        // Fetch on the test thread (synchronizing), then query the text layouts on the UI thread.
        val textNodes = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
            .fetchSemanticsNodes()
        // "Clipped" = more lines than allowed or an ellipsis. (hasVisualOverflow from this semantics action reports
        // overflow for every text in Compose 1.6 - first campaign run flagged even the tab label "Pro".)
        val clipped = rule.runOnUiThread {
            textNodes.filter { n ->
                val results = mutableListOf<TextLayoutResult>()
                runCatching { n.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results) }
                results.any { r -> r.multiParagraph.didExceedMaxLines || (r.lineCount > 0 && r.isLineEllipsized(r.lineCount - 1)) }
            }.map { label(it).take(60) }
        }
        clipped.forEach { problems += "$screen: clipped text '$it'" }
        // Words broken mid-word (end-user test W10: "Abbreche|n" at 130 % font in a two-button row). Tokens
        // longer than 20 characters are addresses / URLs and may wrap anywhere.
        val split = rule.runOnUiThread {
            textNodes.mapNotNull { n ->
                val results = mutableListOf<TextLayoutResult>()
                runCatching { n.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results) }
                results.firstNotNullOfOrNull { r -> splitWord(r) }?.let { "${label(n).take(40)}' ($it)" }
            }
        }
        split.forEach { problems += "$screen: word split across lines '$it" }
        if (rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).fetchSemanticsNodes().isEmpty() &&
            !rule.has("avz:dialog:confirm") && !rule.has("avz:donate:close")
        ) problems += "$screen: no heading"
    }

    /** The first word that a soft line break cuts in two, or null. */
    private fun splitWord(r: TextLayoutResult): String? {
        val t = r.layoutInput.text.text
        for (line in 0 until r.lineCount - 1) {
            val end = r.getLineEnd(line, visibleEnd = false)
            if (end <= 0 || end >= t.length || !t[end - 1].isLetter() || !t[end].isLetter()) continue
            var a = end; while (a > 0 && !t[a - 1].isWhitespace()) a--
            var b = end; while (b < t.length && !t[b].isWhitespace()) b++
            if (b - a <= 20) return t.substring(a, b)
        }
        return null
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

        compose.savedToProTab()
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

