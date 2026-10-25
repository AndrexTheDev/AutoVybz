package com.andrexthedev.autovybz.e2e

import android.content.Context
import android.media.AudioManager
import android.view.WindowManager
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andrexthedev.autovybz.measurement.ListeningEnvironment
import com.andrexthedev.autovybz.measurement.eq.TargetType
import com.andrexthedev.autovybz.settings.AutoVybzSettings
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.roundToInt

/** Cancel / back / confirmation dialogs, configuration changes, window flags, volume handling. */
@RunWith(AndroidJUnit4::class)
class WizardLifecycleE2ETest : E2eTest() {

    private fun windowFlags(): Int {
        var flags = 0
        compose.activityRule.scenario.onActivity { flags = it.window.attributes.flags }
        return flags
    }

    private fun brightness(): Float {
        var b = 0f
        compose.activityRule.scenario.onActivity { b = it.window.attributes.screenBrightness }
        return b
    }

    @Test
    fun cancelWhileMeasuringFirstPosition_returnsHomeWithoutDialog() {
        E2e.engine.hold = CompletableDeferred()
        compose.openSafety("INDOOR", 1)
        compose.confirmSafetyAndStart()
        compose.await("avz:measuring")
        shot("measuring")
        compose.tap("avz:measuring:cancel")
        compose.await("avz:start")
        assertTrue("no dialog for nothing measured yet", !compose.has("avz:dialog:confirm"))
    }

    @Test
    fun backDuringMultiPosition_asksBeforeDiscardingMeasuredPositions() {
        compose.openSafety("INDOOR", 3)
        compose.confirmSafetyAndStart()
        compose.await("avz:move")
        Espresso.pressBack()
        compose.await("avz:dialog:confirm")
        shot("dialog_cancel_session")
        compose.tap("avz:dialog:dismiss")
        compose.await("avz:move")
        Espresso.pressBack()
        compose.tap("avz:dialog:confirm")
        compose.await("avz:start")
    }

    @Test
    fun backOnResult_asksBeforeDiscarding() {
        compose.quickCalibration()
        Espresso.pressBack()
        compose.await("avz:dialog:confirm")
        shot("dialog_discard")
        compose.tap("avz:dialog:confirm")
        compose.await("avz:start")
    }

    @Test
    fun measureAgain_asksBeforeDiscarding_keepStaysOnResult() {
        compose.quickCalibration()
        compose.tap("avz:result:again")
        compose.await("avz:dialog:dismiss")
        compose.tap("avz:dialog:dismiss")
        compose.await("avz:result")
    }

    @Test
    fun resultAndOpenDialog_surviveActivityRecreation() {
        val name = compose.quickCalibration()
        compose.activityRule.scenario.recreate()
        compose.await("avz:result")
        assertEquals(name, compose.editableText("avz:result:name"))
        Espresso.pressBack()
        compose.await("avz:dialog:confirm")
        compose.activityRule.scenario.recreate()
        compose.await("avz:dialog:confirm")
    }

    @Test
    fun measurementSurvivesRecreation() {
        E2e.engine.hold = CompletableDeferred()
        compose.openSafety("INDOOR", 1)
        compose.confirmSafetyAndStart()
        compose.await("avz:measuring")
        compose.activityRule.scenario.recreate()
        compose.await("avz:measuring")
        E2e.engine.hold!!.complete(Unit)
        compose.await("avz:result")
        assertEquals(1, E2e.engine.captures.get())
    }

    @Test
    fun screenStaysOnWhileMeasuring_only() {
        E2e.engine.hold = CompletableDeferred()
        compose.await("avz:start")
        assertEquals(0, windowFlags() and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        compose.openSafety("INDOOR", 1)
        compose.confirmSafetyAndStart()
        compose.await("avz:measuring")
        compose.waitForIdle()
        assertTrue("keep-screen-on while measuring", windowFlags() and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
        E2e.engine.hold!!.complete(Unit)
        compose.await("avz:result")
        compose.waitForIdle()
        assertEquals(0, windowFlags() and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    @Test
    fun outdoor_usesFullBrightness_indoorDoesNot() {
        compose.openSafety("OUTDOOR", 3)
        compose.waitForIdle()
        assertEquals(1f, brightness(), 0.001f)
        compose.tap("avz:safety:cancel")
        compose.await("avz:start")
        compose.waitForIdle()
        assertEquals(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE, brightness(), 0.001f)
        compose.openSafety("INDOOR", 1)
        compose.waitForIdle()
        assertEquals(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE, brightness(), 0.001f)
    }

    @Test
    fun volumeIsSetToAbout70Percent_andRestoredOnCancel() {
        val am = E2e.app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        assumeFalse("fixed-volume device", am.isVolumeFixed)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val before = (max * 0.2).roundToInt().coerceAtLeast(1)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, before, 0)
        compose.openSafety("INDOOR", 1)
        val during = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        assertTrue("volume $during/$max", abs(during.toDouble() / max - 0.7) <= 1.0 / max + 0.01)
        compose.tap("avz:safety:cancel")
        compose.await("avz:start")
        assertEquals("volume restored", before, am.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    @Test
    fun wizardChoices_arePersisted() {
        compose.await("avz:start")
        compose.tap("avz:env:OUTDOOR")
        compose.tap("avz:positions:3")
        compose.tap("avz:target:BASS_BOOST")
        val s = AutoVybzSettings(E2e.app)
        assertEquals(ListeningEnvironment.OUTDOOR, s.environment)
        assertEquals(3, s.positions)
        assertEquals(TargetType.BASS_BOOST, s.target)
        compose.activityRule.scenario.recreate()
        compose.await("avz:start")
        compose.tap("avz:env:INDOOR")
        assertEquals(ListeningEnvironment.INDOOR, AutoVybzSettings(E2e.app).environment)
    }

    @Test
    fun backFromOtherTabs_returnsToWizard() {
        compose.await("avz:start")
        compose.tap("avz:tab:ABOUT")
        compose.await("avz:screen:about")
        Espresso.pressBack()
        compose.await("avz:start")
    }
}

/** With the real 3 s "be quiet" countdown. */
@RunWith(AndroidJUnit4::class)
class CountdownE2ETest : E2eTest(countdownSeconds = 3) {

    @Test
    fun countdownIsShown_andCanBeCancelled() {
        compose.openSafety("INDOOR", 1)
        compose.confirmSafetyAndStart()
        compose.await("avz:countdown")
        shot("countdown")
        compose.tap("avz:countdown:cancel")
        compose.await("avz:start")
        assertEquals("nothing played", 0, E2e.engine.captures.get())
    }

    @Test
    fun countdownLeadsToMeasurement() {
        compose.openSafety("INDOOR", 1)
        compose.confirmSafetyAndStart()
        compose.await("avz:countdown")
        compose.await("avz:result", 90_000)
    }
}
