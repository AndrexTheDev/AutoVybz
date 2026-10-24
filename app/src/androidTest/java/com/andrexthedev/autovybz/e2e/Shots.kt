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
     * Landscape matrix runs: the screenshot is the ground truth. Campaigns 4-6 showed that neither the runner's
     * `settings put system user_rotation` nor a single UiAutomation freeze is reliable on the API 35 emulator (the
     * system restores the rotation when the UiAutomation connection is re-created, so it got lost between tests).
     * All three mechanisms are requested from inside the test (shell identity) until the screen really is landscape.
     */
    private fun applyOrientation() {
        if (args.getString("avz_config") == null || !landscape) return
        ensureLandscape()
    }

    private fun isLandscapeNow(): Boolean {
        val bmp = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return false
        return (bmp.width > bmp.height).also { bmp.recycle() }
    }

    private fun shell(cmd: String): String =
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd).let { pfd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText().trim() }
        }

    /** Returns false when the screen could not be turned within [timeoutMs]. */
    private fun ensureLandscape(timeoutMs: Long = 15_000): Boolean {
        val inst = InstrumentationRegistry.getInstrumentation()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!isLandscapeNow()) {
            if (System.currentTimeMillis() > deadline) return false
            inst.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_90)
            shell("settings put system accelerometer_rotation 0")
            shell("settings put system user_rotation 1")
            shell("cmd window user-rotation lock 1")
            inst.waitForIdleSync()
            Thread.sleep(1_000)
        }
        inst.waitForIdleSync()
        Thread.sleep(500)
        return true
    }

    private fun rotationDiagnostics(): String {
        val d = InstrumentationRegistry.getInstrumentation().targetContext
            .getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(android.view.Display.DEFAULT_DISPLAY)
        return "display.rotation=${d.rotation}, user_rotation=${shell("settings get system user_rotation")}, " +
            "accelerometer_rotation=${shell("settings get system accelerometer_rotation")}, wm: ${shell("cmd window user-rotation")}"
    }

    fun take(rule: ComposeTestRule, name: String) {
        if (!enabled) return
        rule.waitForIdle()
        Thread.sleep(400) // let ripples / animations settle (and the frame reach the screen)
        if (landscape && !isLandscapeNow()) {
            // Rotation lost mid-test: turn back (the wizard state lives in the ViewModel and survives the recreation).
            check(ensureLandscape()) { "config $config expects landscape but the screen stayed portrait (${rotationDiagnostics()})" }
            rule.waitForIdle()
        }
        val bmp = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        check(!landscape || bmp.width > bmp.height) { "config $config expects landscape but the screen is ${bmp.width}x${bmp.height} (${rotationDiagnostics()})" }
        val scaled = Bitmap.createScaledBitmap(bmp, bmp.width / 2, bmp.height / 2, true)
        val dir = File(E2e.app.filesDir, "shots/$config").apply { mkdirs() }
        File(dir, "%s_%02d_%s.jpg".format(prefix, ++counter, name)).outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 72, it) }
        if (scaled !== bmp) scaled.recycle()
        bmp.recycle()
    }
}
