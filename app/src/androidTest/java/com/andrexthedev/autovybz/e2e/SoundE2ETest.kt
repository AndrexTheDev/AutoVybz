package com.andrexthedev.autovybz.e2e

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andrexthedev.autovybz.preset.AutoVybzPresetStore
import com.andrexthedev.autovybz.sound.SoundStyle
import com.andrexthedev.autovybz.sound.VybzSound
import com.bearinmind.equalizer314.state.EqPreferencesManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Sound screen (speaker profiles + styles) and Studio fine-tuning, verified where it matters: in the engine state
 * (band count, pre-gain) that the system-wide EQ plays.
 */
@RunWith(AndroidJUnit4::class)
class SoundE2ETest : E2eTest() {

    private fun bands(): Int = EqPreferencesManager(E2e.app).getSavedSlots()?.size ?: 0
    private fun preamp(): Float = EqPreferencesManager(E2e.app).getPreampGain()

    private fun awaitBands(n: Int, what: String) = compose.waitRobust("$what: expected $n bands, engine has ${bands()}", 15_000) { bands() == n }

    @Test
    fun firstStart_opensCalibration_andSoundExplainsThreeSteps() {
        compose.await("avz:start") // no calibration yet -> calibration tab
        compose.tap("avz:tab:HOME")
        compose.await("avz:screen:home")
        compose.await("avz:home:steps")
        shot("home_first_run")
        compose.tap("avz:home:calibrate")
        compose.await("avz:start")
    }

    /**
     * Deleting the speaker that is audible right now must also change what is audible. The store only cleared
     * the "in use" marker, so the deleted calibration's bands stayed in the engine until the next unrelated
     * style tap - while the Sound screen already showed "Keine".
     */
    @Test
    fun deletingTheActiveSpeaker_fallsBackToTheStyleImmediately() {
        val name = compose.quickCalibration()
        compose.tap("avz:result:saveApply")
        compose.await("avz:saved")
        val cal = AutoVybzPresetStore.loadFull(E2e.app, name)!!.bands.size

        compose.tap("avz:tab:HOME")
        compose.await("avz:screen:home")
        compose.tap("avz:style:HIPHOP_RNB")
        val style = SoundStyle.HIPHOP_RNB.bandsAt(SoundStyle.DEFAULT_INTENSITY).size
        awaitBands(cal + style, "calibration + style before deleting")

        compose.tap("avz:tab:PRO")
        compose.await("avz:pro:cal:$name")
        compose.tap("avz:pro:delete:$name")
        compose.await("avz:dialog:confirm")
        compose.tap("avz:dialog:confirm")
        compose.awaitGone("avz:pro:cal:$name")

        awaitBands(style, "only the style is left after deleting the active speaker")
        assertNull(VybzSound.activeCalibrationName(E2e.app))
        assertTrue(AutoVybzPresetStore.calibrationNames(E2e.app).isEmpty())
        // The style itself is untouched by the deletion.
        assertEquals(SoundStyle.HIPHOP_RNB, VybzSound.selection(E2e.app).style)
    }

    @Test
    fun deletingAnInactiveSpeaker_keepsTheAudibleCalibration() {
        val keep = compose.quickCalibration()
        compose.tap("avz:result:saveApply")
        compose.await("avz:saved")
        // A second calibration (premium is granted in the E2E default setup) ...
        compose.tap("avz:saved:done")
        compose.await("avz:start")
        val other = compose.quickCalibration()
        compose.tap("avz:result:saveApply")
        compose.await("avz:saved")
        assertEquals(other, AutoVybzPresetStore.lastAppliedName(E2e.app))

        // ... deleting the *other*, inactive one must not touch the audible state.
        val keptBands = AutoVybzPresetStore.loadFull(E2e.app, other)!!.bands.size
        compose.tap("avz:tab:PRO")
        compose.await("avz:pro:cal:$keep")
        compose.tap("avz:pro:delete:$keep")
        compose.await("avz:dialog:confirm")
        compose.tap("avz:dialog:confirm")
        compose.awaitGone("avz:pro:cal:$keep")
        assertEquals(other, AutoVybzPresetStore.lastAppliedName(E2e.app))
        awaitBands(keptBands, "the audible calibration is unchanged")
    }

    @Test
    fun styleWithoutCalibration_isAudibleAndCannotClip() {
        compose.tap("avz:tab:HOME")
        compose.tap("avz:style:HIPHOP_RNB")
        // Wait for the app's *own* model first: a tap the (slow, occasionally unfocused) emulator swallowed
        // would otherwise be read off a stale engine, and the band count of a previous test can happen to
        // match the style's count - the pre-gain then still carries the old value.
        compose.waitRobust("style tap did not apply", 10_000) {
            VybzSound.selection(E2e.app).style == SoundStyle.HIPHOP_RNB
        }
        awaitBands(SoundStyle.HIPHOP_RNB.bandsAt(SoundStyle.DEFAULT_INTENSITY).size, "style only")
        assertEquals(SoundStyle.HIPHOP_RNB, VybzSound.selection(E2e.app).style)
        // Bands and pre-gain reach the engine in two steps, so the pre-gain is waited for as well.
        compose.waitRobust("bass boost needs a negative pre-gain, was ${preamp()}", 15_000) { preamp() < 0f }
        compose.await("avz:home:intensity")
        compose.await("avz:home:curve")
    }

    @Test
    fun styleLayersOnTheCalibration_andSurvivesSpeakerSwitching() {
        val name = compose.quickCalibration()
        compose.tap("avz:result:saveApply")
        compose.await("avz:saved")
        val cal = AutoVybzPresetStore.loadFull(E2e.app, name)!!.bands.size
        awaitBands(cal, "calibration applied")
        compose.tap("avz:saved:sound") // the saved screen leads straight to the styles
        compose.await("avz:screen:home")
        compose.await("avz:home:speaker:$name")
        compose.tap("avz:style:REGGAE_DANCEHALL")
        awaitBands(cal + SoundStyle.REGGAE_DANCEHALL.bands.size, "calibration + style")
        shot("home_style")

        // "Without calibration": style only - but the speaker profile is remembered
        compose.tap("avz:home:speaker:none")
        awaitBands(SoundStyle.REGGAE_DANCEHALL.bands.size, "style only")
        assertEquals(name, AutoVybzPresetStore.lastAppliedName(E2e.app))

        compose.tap("avz:home:speaker:$name")
        awaitBands(cal + SoundStyle.REGGAE_DANCEHALL.bands.size, "back to the speaker")

        // Pure = calibration exactly as measured
        compose.tap("avz:style:NEUTRAL")
        awaitBands(cal, "neutral style")
        assertEquals(AutoVybzPresetStore.loadFull(E2e.app, name)!!.preampDb.toFloat(), preamp(), 0.001f)
    }

    @Test
    fun studioBassAndTreble_addShelvesAndReset() {
        compose.tap("avz:tab:PRO")
        compose.await("avz:screen:pro")
        compose.onNodeWithTag("avz:studio:bass").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(4f) }
        awaitBands(1, "bass shelf")
        assertTrue("bass +4 dB needs negative pre-gain", preamp() < 0f)
        compose.onNodeWithTag("avz:studio:treble").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(-2f) }
        awaitBands(2, "bass + treble shelves")
        assertEquals(4.0, VybzSound.selection(E2e.app).bassDb, 0.01)
        assertEquals(-2.0, VybzSound.selection(E2e.app).trebleDb, 0.01)
        shot("studio_tone")
        compose.tap("avz:studio:tone_reset")
        awaitBands(0, "reset")
        assertEquals(0f, preamp(), 0.001f)
    }
}
