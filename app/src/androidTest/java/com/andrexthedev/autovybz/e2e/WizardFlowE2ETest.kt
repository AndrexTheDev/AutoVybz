package com.andrexthedev.autovybz.e2e

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextReplacement
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andrexthedev.autovybz.e2e.SimulatedRoomEngine.Mode
import com.andrexthedev.autovybz.measurement.eq.PeqType
import com.andrexthedev.autovybz.measurement.eq.TargetType
import com.andrexthedev.autovybz.preset.AutoVybzPresetStore
import com.bearinmind.equalizer314.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Happy paths of the 1-click wizard (indoor / outdoor, 1 or 3 positions, 5/10 bands, naming). */
@RunWith(AndroidJUnit4::class)
class WizardFlowE2ETest : E2eTest() {

    @Test
    fun quickIndoorCalibration_saveAndApply_showsUpInPro() {
        compose.openSafety("INDOOR", positions = 1)
        shot("safety")
        compose.confirmSafetyAndStart()
        compose.measureToResult()
        shot("result")
        val name = compose.editableText("avz:result:name")
        assertTrue(name, name.startsWith("AutoVybz_"))
        assertTrue(name, Regex(""".*_\d{4}-\d{2}-\d{2}(_\d+)?""").matches(name))

        compose.tap("avz:result:saveApply")
        compose.await("avz:saved")
        shot("saved")
        assertTrue(name in AutoVybzPresetStore.existingNames(E2e.app))
        assertEquals(name, AutoVybzPresetStore.lastAppliedName(E2e.app))
        assertEquals(1, E2e.engine.captures.get())

        // "Open in Pro EQ" hands over to the classic Equalizer314 parametric EQ for fine-tuning.
        withIntents {
            compose.tap("avz:saved:pro")
            intended(hasComponent(com.bearinmind.equalizer314.MainActivity::class.java.name))
        }
        compose.savedToProTab()
        compose.await("avz:pro:cal:$name")
        shot("pro_list")
    }

    @Test
    fun preciseCalibration_measuresThreePositions() {
        compose.openSafety("INDOOR", positions = 3)
        compose.confirmSafetyAndStart()
        compose.await("avz:move:continue")
        shot("move_1of3")
        compose.tap("avz:move:continue")
        compose.await("avz:move:continue")
        compose.tap("avz:move:continue")
        compose.await("avz:result")
        assertEquals(3, E2e.engine.captures.get())
        assertTrue("averaging info", compose.hasText(str(R.string.avz_result_positions, 3, "0.0").substringBefore("·").trim()))
    }

    @Test
    fun outdoorWindyPosition_canBeRepeated() {
        E2e.engine.script.addAll(listOf(Mode.NORMAL, Mode.WINDY, Mode.NORMAL, Mode.NORMAL))
        compose.openSafety("OUTDOOR", positions = 3)
        compose.confirmSafetyAndStart()
        compose.tap("avz:move:continue")
        compose.await("avz:move:repeat")
        shot("move_wind_warning")
        compose.tap("avz:move:repeat")
        compose.await("avz:move:continue")
        compose.tap("avz:move:continue")
        compose.await("avz:result")
        assertEquals("the windy position was measured again", 4, E2e.engine.captures.get())
    }

    @Test
    fun disturbedLastPosition_offersRepeatBeforeAveraging() {
        E2e.engine.script.addAll(listOf(Mode.NORMAL, Mode.NORMAL, Mode.DROPOUT))
        compose.openSafety("INDOOR", positions = 3)
        compose.confirmSafetyAndStart()
        compose.tap("avz:move:continue")
        compose.tap("avz:move:continue")
        compose.await("avz:move:finish")
        compose.await("avz:move:repeat")
        shot("last_position_disturbed")
        compose.tap("avz:move:finish")
        compose.await("avz:result")
    }

    @Test
    fun multiPosition_canFinishEarly() {
        compose.openSafety("INDOOR", positions = 3)
        compose.confirmSafetyAndStart()
        compose.tap("avz:move:continue")
        compose.await("avz:move:finishEarly")
        compose.tap("avz:move:finishEarly")
        compose.await("avz:result")
        assertEquals(2, E2e.engine.captures.get())
    }

    @Test
    fun resultRefit_fiveBandsAndOtherTarget_areSaved() {
        val name = compose.quickCalibration()
        compose.tap("avz:bands:5")
        compose.tap("avz:target:BASS_BOOST")
        compose.waitForIdle()
        Thread.sleep(1500) // refit runs in the background
        compose.tap("avz:result:saveOnly")
        compose.await("avz:saved")
        val preset = AutoVybzPresetStore.loadFull(E2e.app, name)!!
        assertTrue("bands ${preset.bands.size}", preset.bands.size in 1..5)
        assertEquals(TargetType.BASS_BOOST, preset.target)
        assertTrue("anti-clipping preamp", preset.preampDb <= 0.0)
        assertEquals("save only must not activate", null, AutoVybzPresetStore.lastAppliedName(E2e.app))
    }

    @Test
    fun invalidAndDuplicateNames_areHandled() {
        val first = compose.quickCalibration()
        compose.tap("avz:result:saveOnly")
        compose.await("avz:saved")
        compose.tap("avz:saved:done")
        compose.quickCalibration()
        val nameField = compose.onNodeWithTag("avz:result:name")

        nameField.performTextReplacement("Party/Garten")
        compose.onNodeWithTag("avz:result:saveApply").assertIsNotEnabled()
        shot("name_invalid")
        nameField.performTextReplacement("   ")
        compose.onNodeWithTag("avz:result:saveOnly").assertIsNotEnabled()

        nameField.performTextReplacement(first)
        compose.awaitText(str(R.string.avz_preset_name_exists))
        shot("name_exists")
        compose.onNodeWithTag("avz:result:saveOnly").assertIsEnabled()

        nameField.performTextReplacement("Küche 🎉")
        compose.tap("avz:result:saveOnly")
        compose.await("avz:saved")
        assertTrue(AutoVybzPresetStore.existingNames(E2e.app).containsAll(listOf(first, "Küche 🎉")))
    }

    @Test
    fun audioGlitch_isReportedOnTheResult() {
        E2e.engine.script.add(Mode.GLITCH)
        compose.quickCalibration()
        compose.awaitText(str(R.string.avz_warn_glitch).take(24))
        shot("glitch_warning")
    }

    @Test
    fun speakerName_isUsedForThePresetName() {
        compose.await("avz:speakerName")
        compose.onNodeWithTag("avz:speakerName").performTextReplacement("JBL Flip 6")
        val name = compose.quickCalibration()
        assertTrue(name, name.startsWith("AutoVybz_JBL-Flip-6_"))
    }

    @Test
    fun rescueCalibration_addsProtectiveHighPass() {
        // The normal simulated speaker plays almost full-range and (correctly) gets no
        // protective pass - rescue needs the dreadful cheap box (no bass below ~260 Hz).
        E2e.engine.script.add(Mode.CHEAP_SPEAKER)
        compose.await("avz:start")
        compose.tap("avz:class:RESCUE")
        compose.openSafety()
        compose.confirmSafetyAndStart()
        compose.measureToResult()
        shot("rescue_result")
        compose.tap("avz:result:saveApply")
        compose.await("avz:saved")
        val name = AutoVybzPresetStore.lastAppliedName(E2e.app)!!
        val full = AutoVybzPresetStore.loadFull(E2e.app, name)!!
        assertEquals("RESCUE", full.measurement!!.speakerClass)
        assertTrue("protective high pass in ${full.bands}", full.bands.any { it.type == PeqType.HIGH_PASS })
    }

    @Test
    fun deviceSpeakerCalibration_usesBuiltinRoute() {
        // The inverted route logic: the built-in speaker blocks normal mode but is required here.
        E2e.route = E2e.builtinSpeaker()
        compose.await("avz:start")
        compose.tap("avz:class:DEVICE")
        compose.openSafety()
        compose.confirmSafetyAndStart()
        compose.measureToResult()
        shot("device_result")
        assertEquals(1, E2e.engine.captures.get()) // fixed geometry: exactly one position
        compose.tap("avz:result:saveApply")
        compose.await("avz:saved")
        val name = AutoVybzPresetStore.lastAppliedName(E2e.app)!!
        val full = AutoVybzPresetStore.loadFull(E2e.app, name)!!
        assertEquals("DEVICE", full.measurement!!.speakerClass)
    }
}
