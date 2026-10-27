package com.andrexthedev.autovybz.e2e

import android.content.Context
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andrexthedev.autovybz.preset.AutoVybzPresetStore
import com.andrexthedev.autovybz.premium.EntitlementPolicy
import com.andrexthedev.autovybz.premium.EntitlementStore
import com.andrexthedev.autovybz.premium.EntitlementTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real Free tier (no pre-granted trial): limits, paywall, post-result trial and Alpha TXID activation. */
@RunWith(AndroidJUnit4::class)
class PremiumE2ETest : E2eTest(premiumTrial = false) {

    @Test
    fun freeLimits_openPaywallAndStudioExpertSettingsStayLocked() {
        compose.await("avz:start")
        compose.onNodeWithTag("avz:sticky-footer").assertIsDisplayed()
        assertEquals(EntitlementTier.FREE, EntitlementPolicy.effectiveTier(EntitlementStore(E2e.app).load()))

        compose.tap("avz:positions:3")
        compose.await("avz:premium")
        compose.await("avz:premium:trial")
        shot("premium_paywall_free")
        compose.tap("avz:premium:close")
        compose.awaitGone("avz:premium")

        compose.tap("avz:tab:PRO")
        compose.await("avz:screen:pro")
        compose.await("avz:premium:locked-settings")
        compose.tap("avz:premium:locked-settings:open")
        compose.await("avz:premium")
        compose.tap("avz:premium:close")
    }

    /**
     * Regression guard for the scope of the Free profile limit: a preset created in the inherited
     * Equalizer314 screens shares the `custom_presets` pool but is *not* an AutoVybz calibration, so it must
     * not consume the single Free calibration slot. Counting the pool disabled the save button (with the
     * "Free saves one speaker profile" hint) although the Sound screen had no profile at all.
     */
    @Test
    fun studioPresetsDoNotConsumeTheFreeCalibration() {
        E2e.app.getSharedPreferences("custom_presets", Context.MODE_PRIVATE).edit()
            .putStringSet("preset_names", setOf("StudioOnly"))
            .apply()
        assertTrue("StudioOnly" in AutoVybzPresetStore.existingNames(E2e.app))
        assertTrue(AutoVybzPresetStore.calibrationNames(E2e.app).isEmpty())
        assertTrue(AutoVybzPresetStore.isFirstCalibration(E2e.app))

        val name = compose.quickCalibration()
        compose.onNodeWithTag("avz:result:premium").assertDoesNotExist() // no "limit reached" CTA
        compose.onNodeWithTag("avz:result:saveApply").assertIsEnabled()

        compose.tap("avz:result:saveApply")
        compose.await("avz:saved")
        // The calibration index (the source of the limit and of the start-tab decision) knows the new profile.
        assertEquals(setOf(name), AutoVybzPresetStore.calibrationNames(E2e.app))
        assertFalse(AutoVybzPresetStore.isFirstCalibration(E2e.app))
        // The studio preset is still there - saving a calibration must not touch foreign pool entries.
        assertTrue("StudioOnly" in AutoVybzPresetStore.existingNames(E2e.app))
    }

    @Test
    fun firstFreeResult_startsTrialAndUnlocksThreePositions() {
        compose.quickCalibration()
        compose.await("avz:result:saveApply")
        compose.waitUntil(10_000) { EntitlementPolicy.effectiveTier(EntitlementStore(E2e.app).load()) == EntitlementTier.TRIAL }

        compose.tap("avz:result:again")
        compose.await("avz:dialog:confirm")
        compose.tap("avz:dialog:confirm")
        compose.await("avz:start")
        compose.tap("avz:positions:3")
        compose.tap("avz:start")
        compose.await("avz:safety:start")
        compose.confirmSafetyAndStart()
        compose.await("avz:move:continue")
    }

    @Test
    fun alphaCryptoActivation_andResetForDeviceTesting() {
        compose.await("avz:start")
        compose.tap("avz:sticky-footer")
        compose.await("avz:premium")
        compose.tap("avz:premium:plan:MONTHLY")
        compose.tap("avz:premium:coin:BTC")
        compose.onNodeWithTag("avz:premium:txid").performTextReplacement("a".repeat(64))
        compose.tap("avz:premium:activate")
        compose.waitUntil(10_000) { EntitlementPolicy.effectiveTier(EntitlementStore(E2e.app).load()) == EntitlementTier.PRO }
        compose.awaitGone("avz:premium")
        compose.tap("avz:sticky-footer")
        compose.await("avz:premium:reset")

        compose.tap("avz:premium:reset")
        compose.waitUntil(10_000) { EntitlementPolicy.effectiveTier(EntitlementStore(E2e.app).load()) == EntitlementTier.FREE }
        compose.await("avz:premium:trial")
        compose.tap("avz:premium:close")
    }
}
