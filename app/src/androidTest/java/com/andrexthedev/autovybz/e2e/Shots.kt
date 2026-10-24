package com.andrexthedev.autovybz.e2e

import android.app.UiAutomation
import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.runner.Description
import java.io.File

/**
 * Screenshots for the (temporary) screenshot matrix. Only active with `-e avz_shots true`; the
 * configuration name (`-e avz_config de-phone-font130`) becomes the folder. Files go to the app's
 * private files dir and are pulled with `run-as` by tools/e2e/run_e2e.sh.
 */
object Shots {
    private val args get() = InstrumentationRegistry.getArguments()
    val enabled: Boolean get() = args.getString("avz_shots") == "true"
    private val config: String get() = args.getString("avz_config") ?: "default"

    private var prefix = "test"
    private var counter = 0

    fun begin(d: Description) {
        prefix = (d.methodName ?: "test").replace(Regex("[^A-Za-z0-9_]"), "_")
        counter = 0
        applyOrientation()
    }

    private val landscape: Boolean get() = "landscape" in config

    /**
     * Matrix runs freeze the rotation through UiAutomation before the activity starts: `settings put system
     * user_rotation` from the runner was silently ignored in one campaign (de-landscape came out portrait).
     */
    private fun applyOrientation() {
        if (args.getString("avz_config") == null) return
        val inst = InstrumentationRegistry.getInstrumentation()
        inst.uiAutomation.setRotation(if (landscape) UiAutomation.ROTATION_FREEZE_90 else UiAutomation.ROTATION_FREEZE_0)
        inst.waitForIdleSync()
        Thread.sleep(500)
    }

    fun take(rule: ComposeTestRule, name: String) {
        if (!enabled) return
        rule.waitForIdle()
        Thread.sleep(400) // let ripples / animations settle (and the frame reach the screen)
        val bmp = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        check(!landscape || bmp.width > bmp.height) { "config $config expects landscape but the screen is ${bmp.width}x${bmp.height}" }
        val scaled = Bitmap.createScaledBitmap(bmp, bmp.width / 2, bmp.height / 2, true)
        val dir = File(E2e.app.filesDir, "shots/$config").apply { mkdirs() }
        File(dir, "%s_%02d_%s.jpg".format(prefix, ++counter, name)).outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 72, it) }
        if (scaled !== bmp) scaled.recycle()
        bmp.recycle()
    }
}
