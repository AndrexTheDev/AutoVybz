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
import com.andrexthedev.autovybz.sound.SoundStyle
import com.andrexthedev.autovybz.sound.VybzSound
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
        val all = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
            .filter { it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f } // off-screen / not laid out
        // With a dialog open only the dialog can be operated (the scrim blocks the screen behind it).
        val dialogRoot = all.firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag).let { t -> t == "avz:dialog:confirm" || t == "avz:donate:close" } }?.let(::rootOf)
        val clickables = if (dialogRoot == null) all else all.filter { rootOf(it) == dialogRoot }
        for (n in clickables) {
            val b = n.boundsInRoot
            val l = label(n)
            if (l.isBlank()) problems += "$screen: control without label at $b"
            // Text fields: the semantics node is the inner text line, the tappable area is the whole field.
            if (n.config.contains(SemanticsActions.SetText)) continue
            // Size = laid-out size; boundsInRoot is clipped by the scroll viewport (a chip half under the bottom bar
            // measured "110x12 dp" in campaign 3). Partially visible controls are judged when fully on screen.
            val w = n.size.width.toFloat(); val h = n.size.height.toFloat()
            if (w >= minPx && h >= minPx) continue
            if (b.width < w - 1.5f || b.height < h - 1.5f) continue
            // Smaller visuals are fine when Compose's 48 dp touch expansion has room (Material chips/buttons
            // reserve it via minimumInteractiveComponentSize) - the WCAG 2.5.8 spacing rule: >= 24 dp, and the
            // 48 dp box around the centre overlaps no other control.
            val c = b.center
            val box = androidx.compose.ui.geometry.Rect(c.x - maxOf(w, minPx) / 2, c.y - maxOf(h, minPx) / 2,
                c.x + maxOf(w, minPx) / 2, c.y + maxOf(h, minPx) / 2)
            // Same window and same scroll container: a control that happens to sit at the viewport edge next to
            // the fixed navigation bar is a scroll-position artefact (campaign 5: chip "0 dp" above the tab "Pro").
            val neighbour = clickables.firstOrNull { o -> o.id != n.id && rootOf(o) == rootOf(n) && scrollParentOf(o) == scrollParentOf(n) &&
                o.boundsInRoot.overlaps(box) && !o.boundsInRoot.contains(c) && !b.contains(o.boundsInRoot.center) }
            if (w < absMinPx || h < absMinPx || neighbour != null)
                problems += "$screen: touch target %.0fx%.0f dp%s '%s'".format(w / dp, h / dp,
                    neighbour?.let { " (crowded by '${label(it).take(30)}' %.0f dp away)".format(gap(b, it.boundsInRoot) / dp) } ?: "", l.take(40))
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

    /** Id of the nearest scrollable ancestor, or -1 for fixed UI (header, navigation). */
    private fun scrollParentOf(n: SemanticsNode): Int {
        var x = n.parent
        while (x != null) {
            if (x.config.contains(SemanticsProperties.VerticalScrollAxisRange) || x.config.contains(SemanticsProperties.HorizontalScrollAxisRange)) return x.id
            x = x.parent
        }
        return -1
    }

    /** Smallest edge distance between two rectangles (0 when they touch or overlap). */
    private fun gap(a: androidx.compose.ui.geometry.Rect, b: androidx.compose.ui.geometry.Rect): Float {
        val dx = maxOf(0f, maxOf(a.left, b.left) - minOf(a.right, b.right))
        val dy = maxOf(0f, maxOf(a.top, b.top) - minOf(a.bottom, b.bottom))
        return maxOf(dx, dy)
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

    /**
     * Selects a style and proves it landed in the app state before waiting for the intensity card.
     *
     * Campaign 15 (API 28 emulator) failed here with only "'avz:home:intensity' did not appear within
     * 60000 ms" - which reads like a missing UI element but can equally be a tap that never reached the chip.
     * Checking `VybzSound.selection()` names the culprit, and one documented retry absorbs the slow-emulator
     * case without weakening the audit.
     */
    private fun selectStyle(style: SoundStyle) {
        compose.tap("avz:style:${style.name}")
        val applied = runCatching { compose.waitUntil(5_000) { VybzSound.selection(E2e.app).style == style }; true }
            .getOrDefault(false)
        if (applied) return
        android.util.Log.w("AvzE2E", "style ${style.name} was not selected by the first tap - retrying once")
        compose.tap("avz:style:${style.name}")
        compose.waitRobust("style ${style.name} selected", 10_000) { VybzSound.selection(E2e.app).style == style }
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
        expandAllCards(); audit("studio (all cards expanded)")
        compose.tap("avz:tab:HOME"); compose.await("avz:screen:home"); audit("sound")
        selectStyle(SoundStyle.LIVE)
        compose.await("avz:home:intensity"); audit("sound (style selected)")
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

