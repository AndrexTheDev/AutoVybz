package com.andrexthedev.autovybz.e2e

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import android.accessibilityservice.AccessibilityService
import androidx.compose.ui.test.performImeAction
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andrexthedev.autovybz.AutoVybzServices
import com.andrexthedev.autovybz.e2e.SimulatedRoomEngine.Mode
import com.bearinmind.equalizer314.R
import kotlinx.coroutines.CompletableDeferred
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * Walks through every screen state for the temporary screenshot matrix (locales x sizes x font scale x
 * orientation, see tools/e2e/run_e2e.sh). Also a smoke test of all states when run without screenshots.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ScreenshotTourE2ETest : E2eTest() {

    /**
     * System back without Espresso: Espresso waits for window focus, which the matrix runs (rotation frozen through
     * UiAutomation) did not get in campaign 5. The global action goes through the input pipeline like a real key.
     */
    private fun systemBack() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        compose.waitForIdle()
    }

    private fun scrollTo(tag: String) = runCatching { compose.onNodeWithTag(tag).performScrollTo() }

    private fun expandAll() {
        val collapsed = str(R.string.avz_a11y_collapsed)
        repeat(12) {
            val nodes = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, collapsed))
            if (nodes.fetchSemanticsNodes().isEmpty()) return
            nodes[0].let { runCatching { it.performScrollTo() }; it.performClick() }
            compose.waitForIdle()
        }
    }

    /**
     * One shot per expanded card (W8: four swipes after expandAll() only re-shot the list end). Walks from the
     * last card upwards: the minimal performScrollTo then puts each header at the top with its content below.
     */
    private fun shootEachExpanded(prefix: String) {
        val expanded = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, str(R.string.avz_a11y_expanded))
        val count = compose.onAllNodes(expanded).fetchSemanticsNodes().size
        for (i in count - 1 downTo 0) {
            runCatching { compose.onAllNodes(expanded)[i].performScrollTo() }
            shot("${prefix}_expanded_${i + 1}")
        }
    }

    @Test fun t01_wizardIdle() {
        compose.await("avz:start"); shot("idle_top")
        compose.onNodeWithTag("avz:speakerName").performTextReplacement("JBL Flip 6")
        compose.onNodeWithTag("avz:speakerName").performImeAction() // "Done" clears focus -> keyboard closes (W2)
        compose.tap("avz:env:OUTDOOR"); shot("idle_outdoor")
        scrollTo("avz:target:CUSTOM"); shot("idle_targets")
        compose.onNodeWithText(str(R.string.avz_wizard_help_link), substring = true).performScrollTo(); shot("idle_bottom")
    }

    @Test fun t02_safety() {
        compose.openSafety("INDOOR", 1); shot("safety_speaker")
        compose.tap("avz:safety:cancel")
        E2e.route = E2e.unknownSpeaker()
        compose.openSafety("OUTDOOR", 3); shot("safety_unknown_outdoor")
        compose.tap("avz:safety:confirm"); compose.tap("avz:safety:speaker"); shot("safety_checked")
    }

    @Test fun t03_countdownMeasuringMove() {
        AutoVybzServices.countdownSeconds = 3
        E2e.engine.hold = CompletableDeferred()
        E2e.engine.script.addAll(listOf(Mode.NORMAL, Mode.WINDY, Mode.NORMAL, Mode.NORMAL))
        compose.openSafety("OUTDOOR", 3); compose.confirmSafetyAndStart()
        compose.await("avz:countdown"); shot("countdown")
        compose.await("avz:measuring"); shot("measuring")
        E2e.engine.hold!!.complete(Unit); E2e.engine.hold = null
        AutoVybzServices.countdownSeconds = 0
        compose.await("avz:move:continue"); shot("move_ok")
        compose.tap("avz:move:continue")
        compose.await("avz:move:repeat"); shot("move_warning")
        compose.tap("avz:move:repeat")
        compose.await("avz:move:continue"); compose.tap("avz:move:continue")
        compose.await("avz:result"); compose.awaitGone("avz:busy"); shot("result_outdoor_top") // W3: AnimatedContent overlap
    }

    @Test fun t04_resultSavedDialogs() {
        compose.quickCalibration(); compose.awaitGone("avz:busy"); shot("result_top")
        scrollTo("avz:bands:5"); shot("result_targets")
        scrollTo("avz:export:open"); shot("result_actions")
        compose.tap("avz:export:open"); shot("export_menu"); systemBack()
        compose.tap("avz:result:again"); compose.await("avz:dialog:confirm"); shot("dialog_discard")
        compose.tap("avz:dialog:dismiss")
        compose.onNodeWithTag("avz:result:name").performTextReplacement("Party/Garten"); scrollTo("avz:result:name"); shot("result_name_invalid")
        compose.onNodeWithTag("avz:result:name").performTextReplacement("Garten Party")
        compose.tap("avz:result:saveApply"); compose.await("avz:saved"); shot("saved")
    }

    @Test fun t05_errors() {
        E2e.engine.script.add(Mode.SILENCE)
        compose.openSafety("INDOOR", 1); compose.confirmSafetyAndStart()
        compose.await("avz:error:NO_SIGNAL"); shot("error_no_signal")
        compose.tap("avz:error:back")
        E2e.engine.script.addAll(listOf(Mode.NORMAL, Mode.ROUTE_CHANGE))
        compose.openSafety("INDOOR", 3); compose.confirmSafetyAndStart()
        compose.tap("avz:move:continue")
        compose.await("avz:error:resume"); shot("error_route_changed_resumable")
        systemBack(); compose.await("avz:dialog:confirm"); shot("dialog_cancel_session")
        compose.tap("avz:dialog:confirm")
        E2e.route = E2e.headphones()
        compose.tap("avz:start"); compose.await("avz:blocked"); shot("blocked_headphones")
        compose.tap("avz:blocked:back")
        E2e.route = E2e.builtinSpeaker()
        compose.tap("avz:start"); compose.await("avz:blocked"); shot("blocked_builtin")
    }

    @Test fun t06_pro() {
        compose.quickCalibration()
        compose.tap("avz:result:saveOnly"); compose.await("avz:saved")
        compose.savedToProTab(); shot("pro_top")
        val name = AutoVybzPresetStoreNames.first()
        scrollTo("avz:pro:cal:$name"); shot("pro_calibrations")
        compose.tap("avz:pro:delete:$name"); compose.await("avz:dialog:confirm"); shot("pro_delete_dialog")
        compose.tap("avz:dialog:dismiss")
        expandAll()
        shootEachExpanded("pro")
    }

    @Test fun t07_helpAbout() {
        compose.tap("avz:tab:HELP"); compose.await("avz:screen:help"); shot("help_top")
        expandAll()
        shootEachExpanded("help")
        compose.tap("avz:tab:ABOUT"); compose.await("avz:screen:about"); shot("about_top")
        compose.onRoot().performTouchInputSwipeUp(); shot("about_bottom")
        compose.tap("avz:about:donate"); compose.await("avz:donate:close"); shot("donate")
        compose.tap("avz:donate:copy:BTC"); shot("donate_copied")
    }

    @Test fun t08_soundStyles() {
        compose.quickCalibration()
        compose.tap("avz:result:saveApply"); compose.await("avz:saved")
        compose.tap("avz:saved:sound"); compose.await("avz:screen:home"); shot("sound_top")
        compose.tap("avz:style:ELECTRONIC"); scrollTo("avz:style:ELECTRONIC"); shot("sound_styles")
        scrollTo("avz:home:curve"); shot("sound_curve")
        compose.tap("avz:tab:PRO"); compose.await("avz:screen:pro"); shot("studio_top")
    }
}

private val AutoVybzPresetStoreNames: List<String>
    get() = com.andrexthedev.autovybz.preset.AutoVybzPresetStore.listCalibrations(E2e.app).map { it.name }

private fun androidx.compose.ui.test.SemanticsNodeInteraction.performTouchInputSwipeUp() {
    performTouchInput { swipeUp(startY = bottom * 0.85f, endY = bottom * 0.2f, durationMillis = 300) }
}
