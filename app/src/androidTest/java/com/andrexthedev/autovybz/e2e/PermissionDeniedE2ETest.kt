package com.andrexthedev.autovybz.e2e

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.anyIntent
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/**
 * Runs in its own instrumentation pass *before* the microphone was ever granted
 * (tools/e2e/run_e2e.sh revokes it first). Drives the real system permission dialog with UiAutomator.
 */
@RunWith(AndroidJUnit4::class)
class PermissionDeniedE2ETest : E2eTest(grantMicrophone = false) {

    @Test
    fun deniedMicrophone_explainsAndLinksToAppSettings() {
        assumeTrue(
            "microphone already granted - run via tools/e2e/run_e2e.sh",
            ContextCompat.checkSelfPermission(E2e.app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED,
        )
        compose.tap("avz:start")
        val device = UiDevice.getInstance(E2e.instrumentation)
        val deny = device.wait(Until.findObject(By.res(Pattern.compile(".*:id/permission_deny(_and_dont_ask_again)?_button"))), 20_000)
        assertNotNull("system permission dialog did not appear", deny)
        shot("system_permission_dialog")
        deny!!.click()
        compose.await("avz:error:PERMISSION_DENIED")
        shot("error_permission_denied")
        assertEquals(0, E2e.engine.captures.get())

        Intents.init()
        try {
            intending(anyIntent()).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
            compose.tap("avz:error:settings")
            intended(hasAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS))
            val i = Intents.getIntents().last { it.action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS }
            assertEquals("package:${E2e.PKG}", i.dataString)
        } finally {
            Intents.release()
        }
    }
}
