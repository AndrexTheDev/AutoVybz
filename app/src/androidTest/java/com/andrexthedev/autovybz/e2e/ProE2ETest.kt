package com.andrexthedev.autovybz.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andrexthedev.autovybz.preset.AutoVybzPresetStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Pro area: saved calibrations (apply, delete with confirmation). */
@RunWith(AndroidJUnit4::class)
class ProE2ETest : E2eTest() {

    private fun saveOneCalibration(): String {
        val name = compose.quickCalibration()
        compose.tap("avz:result:saveOnly")
        compose.await("avz:saved")
        return name
    }

    @Test
    fun calibration_canBeAppliedFromPro() {
        val name = saveOneCalibration()
        assertEquals(null, AutoVybzPresetStore.lastAppliedName(E2e.app))
        compose.tap("avz:saved:pro")
        compose.tap("avz:pro:apply:$name")
        compose.waitUntil(10_000) { AutoVybzPresetStore.lastAppliedName(E2e.app) == name }
        // Also in the Equalizer314 preset pool, so the classic engine can load it.
        assertTrue(name in AutoVybzPresetStore.existingNames(E2e.app))
    }

    @Test
    fun deleteAsksForConfirmation() {
        val name = saveOneCalibration()
        compose.tap("avz:saved:pro")
        compose.await("avz:pro:cal:$name")
        compose.tap("avz:pro:delete:$name")
        compose.await("avz:dialog:confirm")
        shot("pro_delete_dialog")
        compose.tap("avz:dialog:dismiss")
        compose.await("avz:pro:cal:$name")
        assertTrue(AutoVybzPresetStore.listCalibrations(E2e.app).any { it.name == name })

        compose.tap("avz:pro:delete:$name")
        compose.tap("avz:dialog:confirm")
        compose.awaitGone("avz:pro:cal:$name")
        assertFalse(AutoVybzPresetStore.listCalibrations(E2e.app).any { it.name == name })
        assertFalse(name in AutoVybzPresetStore.existingNames(E2e.app))
    }

    @Test
    fun wizardPicksUpSettingsChangedElsewhere() {
        compose.await("avz:start")
        compose.tap("avz:tab:PRO")
        compose.await("avz:screen:pro")
        // The Pro tab edits the same preferences; simulate a change and come back.
        com.andrexthedev.autovybz.settings.AutoVybzSettings(E2e.app).positions = 3
        compose.tap("avz:tab:WIZARD")
        compose.await("avz:start")
        compose.tap("avz:start")
        compose.await("avz:safety:start")
        compose.confirmSafetyAndStart()
        compose.await("avz:move:continue") // 3-position mode is active
    }
}
