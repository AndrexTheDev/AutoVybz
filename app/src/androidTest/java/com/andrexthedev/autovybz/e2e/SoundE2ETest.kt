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

    @Test
    fun styleWithoutCalibration_isAudibleAndCannotClip() {
        compose.tap("avz:tab:HOME")
        compose.tap("avz:style:HIPHOP_RNB")
        awaitBands(SoundStyle.HIPHOP_RNB.bandsAt(SoundStyle.DEFAULT_INTENSITY).size, "style only")
        assertEquals(SoundStyle.HIPHOP_RNB, VybzSound.selection(E2e.app).style)
        assertTrue("bass boost needs negative pre-gain, was ${preamp()}", preamp() < 0f)
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
