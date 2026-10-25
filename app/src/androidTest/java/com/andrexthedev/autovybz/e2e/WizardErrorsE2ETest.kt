package com.andrexthedev.autovybz.e2e

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andrexthedev.autovybz.e2e.SimulatedRoomEngine.Mode
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Every failure mode of the measurement and how the wizard recovers from it. */
@RunWith(AndroidJUnit4::class)
class WizardErrorsE2ETest : E2eTest() {

    @Test
    fun silentMicrophone_showsNoSignal_andRetryWorks() {
        E2e.engine.script.add(Mode.SILENCE)
        compose.openSafety("INDOOR", 1)
        compose.confirmSafetyAndStart()
        compose.await("avz:error:NO_SIGNAL")
        shot("error_no_signal")
        compose.tap("avz:error:retry")
        // Retry starts over at the safety step (volume may have changed).
        when (compose.awaitAny("avz:safety:start", "avz:result", "avz:measuring")) {
            "avz:safety:start" -> compose.confirmSafetyAndStart()
        }
        compose.measureToResult()
    }

    @Test
    fun driverError_showsCaptureFailed_andBackReturnsHome() {
        E2e.engine.script.add(Mode.FAIL)
        compose.openSafety("INDOOR", 1)
        compose.confirmSafetyAndStart()
        compose.await("avz:error:CAPTURE_FAILED")
        shot("error_capture_failed")
        compose.tap("avz:error:back")
        compose.await("avz:start")
    }

    @Test
    fun routeChangeDuringCapture_showsRouteChanged() {
        E2e.engine.script.add(Mode.ROUTE_CHANGE)
        compose.openSafety("INDOOR", 1)
        compose.confirmSafetyAndStart()
        compose.await("avz:error:ROUTE_CHANGED")
        shot("error_route_changed")
    }

    @Test
    fun failureAtPosition2_keepsPosition1_andResumes() {
        E2e.engine.script.addAll(listOf(Mode.NORMAL, Mode.FAIL, Mode.NORMAL, Mode.NORMAL))
        compose.openSafety("INDOOR", 3)
        compose.confirmSafetyAndStart()
        compose.tap("avz:move:continue")
        compose.await("avz:error:CAPTURE_FAILED")
        compose.await("avz:error:resume")
        compose.await("avz:error:finish")
        shot("error_resumable")
        compose.tap("avz:error:resume")
        compose.measureToResult()
        assertEquals(4, E2e.engine.captures.get())
    }

    @Test
    fun failureAtLastPosition_canFinishWithMeasuredPositions() {
        E2e.engine.script.addAll(listOf(Mode.NORMAL, Mode.NORMAL, Mode.FAIL))
        compose.openSafety("INDOOR", 3)
        compose.confirmSafetyAndStart()
        compose.tap("avz:move:continue")
        compose.tap("avz:move:continue")
        compose.await("avz:error:finish")
        compose.tap("avz:error:finish")
        compose.await("avz:result")
    }

    @Test
    fun resume_rechecksTheRoute_headphonesAreBlocked() {
        E2e.engine.script.addAll(listOf(Mode.NORMAL, Mode.FAIL))
        compose.openSafety("INDOOR", 3)
        compose.confirmSafetyAndStart()
        compose.tap("avz:move:continue")
        compose.await("avz:error:resume")
        E2e.route = E2e.headphones() // user plugged in headphones meanwhile
        compose.tap("avz:error:resume")
        compose.await("avz:blocked")
        assertEquals("no stimulus may go to headphones", 2, E2e.engine.captures.get())
    }

    @Test
    fun headphones_areBlocked_untilASpeakerIsConnected() {
        E2e.route = E2e.headphones()
        compose.await("avz:start")
        compose.tap("avz:start")
        compose.await("avz:blocked")
        shot("blocked_headphones")
        E2e.route = E2e.speaker()
        compose.tap("avz:blocked:retry")
        compose.await("avz:safety:start")
    }

    @Test
    fun builtinSpeaker_isBlocked() {
        E2e.route = E2e.builtinSpeaker()
        compose.await("avz:start")
        compose.tap("avz:start")
        compose.await("avz:blocked")
        shot("blocked_builtin")
        compose.tap("avz:blocked:back")
        compose.await("avz:start")
        assertEquals(0, E2e.engine.captures.get())
    }

    @Test
    fun unknownOutput_requiresExplicitSpeakerConfirmation() {
        E2e.route = E2e.unknownSpeaker()
        compose.openSafety("INDOOR", 1)
        compose.await("avz:safety:speaker")
        compose.onNodeWithTag("avz:safety:start").assertIsNotEnabled()
        compose.tap("avz:safety:confirm")
        compose.onNodeWithTag("avz:safety:start").assertIsNotEnabled()
        shot("safety_unknown_route")
        compose.tap("avz:safety:speaker")
        compose.onNodeWithTag("avz:safety:start").assertIsEnabled()
        compose.tap("avz:safety:cancel")
        compose.await("avz:start")
        assertEquals(0, E2e.engine.captures.get())
    }

    /** A real audio-focus steal (like an incoming call) must interrupt the capture. */
    @Test
    fun audioFocusLoss_interruptsTheMeasurement() {
        assumeTrue(Build.VERSION.SDK_INT >= 26)
        E2e.engine.hold = CompletableDeferred()
        compose.openSafety("INDOOR", 1)
        compose.confirmSafetyAndStart()
        compose.await("avz:measuring")
        val am = E2e.app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val thief = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).build())
            .setOnAudioFocusChangeListener { }
            .build()
        try {
            am.requestAudioFocus(thief)
            compose.await("avz:error:INTERRUPTED")
            shot("error_interrupted")
        } finally {
            am.abandonAudioFocusRequest(thief)
        }
    }
}
