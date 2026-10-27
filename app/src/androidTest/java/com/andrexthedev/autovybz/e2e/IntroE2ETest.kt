package com.andrexthedev.autovybz.e2e

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andrexthedev.autovybz.ui.intro.IntroGate
import com.andrexthedev.autovybz.ui.intro.IntroOverlay
import com.andrexthedev.autovybz.ui.theme.AutoVybzTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Brand intro on a real cold start: shown once, leaves by itself, never again after rotation. */
@RunWith(AndroidJUnit4::class)
class IntroE2ETest : E2eTest(intro = true) {

    @Test
    fun coldStartPlaysTheIntroOnceAndHandsOverToTheApp() {
        compose.waitRobust("intro was never shown", TIMEOUT_MS) { IntroGate.shownCount.get() >= 1 }
        val shown = IntroGate.shownCount.get()
        // 8.2 s clip, hard limit 11 s - generous margin for slow emulators
        compose.awaitGone("avz:intro", timeoutMs = 20_000)
        compose.await("avz:start") // fresh install -> calibration tab
        compose.activityRule.scenario.recreate()
        compose.await("avz:start")
        assertFalse("intro replayed after rotation", compose.has("avz:intro"))
        assertEquals("intro replayed after rotation", shown, IntroGate.shownCount.get())
    }
}

/** The overlay in isolation with a controlled clock: skip button, tap anywhere, safety timeout. */
@RunWith(AndroidJUnit4::class)
class IntroOverlayTest {
    @get:Rule val compose = createComposeRule()

    private fun show(): () -> Boolean {
        var done = false
        compose.mainClock.autoAdvance = false
        compose.setContent { AutoVybzTheme { IntroOverlay(onFinished = { done = true }) } }
        compose.mainClock.advanceTimeByFrame()
        return { done }
    }

    @Test
    fun skipButtonEndsTheIntroAfterTheFade() {
        val done = show()
        compose.onNodeWithTag("avz:intro:skip").performClick()
        assertFalse("must fade, not cut", done())
        compose.mainClock.advanceTimeBy(IntroGate.FADE_MS + 100L)
        assertTrue(done())
    }

    @Test
    fun tapAnywhereSkips() {
        val done = show()
        compose.onNodeWithTag("avz:intro").performClick()
        compose.mainClock.advanceTimeBy(IntroGate.FADE_MS + 100L)
        assertTrue(done())
    }

    @Test
    fun aStuckPlayerCannotTrapTheUser() {
        val done = show()
        compose.mainClock.advanceTimeBy(IntroGate.MAX_DURATION_MS + IntroGate.FADE_MS + 200L)
        assertTrue("intro must end at the latest after ${IntroGate.MAX_DURATION_MS} ms", done())
    }
}
