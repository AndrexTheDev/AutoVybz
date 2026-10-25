package com.andrexthedev.autovybz.e2e

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import com.andrexthedev.autovybz.AutoVybzServices
import com.andrexthedev.autovybz.measurement.audio.RouteClassifier
import com.andrexthedev.autovybz.premium.EntitlementStore
import com.andrexthedev.autovybz.ui.intro.IntroGate
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.File

/** Shared state + helpers of the end-to-end suite. */
object E2e {
    const val PKG = "com.andrexthedev.autovybz"

    val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    val app: Context get() = instrumentation.targetContext.applicationContext

    val engine = SimulatedRoomEngine()

    @Volatile var route: RouteClassifier.Decision = speaker()

    fun speaker(name: String = "Test Boombox") =
        RouteClassifier.Decision(RouteClassifier.Verdict.OK, RouteClassifier.Route(RouteClassifier.TYPE_BLUETOOTH_A2DP, name))

    fun unknownSpeaker() =
        RouteClassifier.Decision(RouteClassifier.Verdict.NEEDS_SPEAKER_CONFIRMATION, RouteClassifier.Route(RouteClassifier.TYPE_USB_DEVICE, "USB Audio"))

    fun headphones() =
        RouteClassifier.Decision(RouteClassifier.Verdict.BLOCKED_HEADPHONES, RouteClassifier.Route(RouteClassifier.TYPE_WIRED_HEADPHONES, "Headphones"))

    fun builtinSpeaker() =
        RouteClassifier.Decision(RouteClassifier.Verdict.BLOCKED_BUILTIN_SPEAKER, RouteClassifier.Route(RouteClassifier.TYPE_BUILTIN_SPEAKER, ""))

    fun install(countdownSeconds: Int) {
        AutoVybzServices.engineFactory = { engine }
        AutoVybzServices.routeInspector = { route }
        AutoVybzServices.countdownSeconds = countdownSeconds
    }

    /** Fresh install state: no settings, no calibrations, no Equalizer314 presets. */
    fun resetAppData() {
        for (p in listOf("autovybz_settings", "autovybz_state", "autovybz_sound", "autovybz_premium", "custom_presets")) app.getSharedPreferences(p, Context.MODE_PRIVATE).edit().clear().commit()
        File(app.filesDir, "autovybz").deleteRecursively()
        File(app.cacheDir, "share").deleteRecursively()
    }

    /** Runs a shell command as the shell user and waits for it. */
    fun shell(cmd: String): String {
        val pfd = instrumentation.uiAutomation.executeShellCommand(cmd)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().toString(Charsets.UTF_8) }
    }
}

/**
 * Installs the simulated hardware before the activity starts and restores the real one afterwards.
 * Existing product flows run in the Trial tier by default; [premiumTrial] = false pins the real Free tier.
 */
class E2eRule(
    private val countdownSeconds: Int = 0,
    private val intro: Boolean = false,
    private val premiumTrial: Boolean = true,
) : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            E2e.resetAppData()
            if (premiumTrial) EntitlementStore(E2e.app).startTrial()
            // the brand intro would cover every screen for 4 s; only IntroE2ETest plays it (also without animations)
            IntroGate.enabled = intro
            IntroGate.force = intro
            E2e.engine.reset()
            E2e.route = E2e.speaker()
            E2e.install(countdownSeconds)
            Shots.begin(description)
            try {
                base.evaluate()
            } finally {
                E2e.engine.hold?.complete(Unit)
                AutoVybzServices.reset()
                IntroGate.enabled = false
                IntroGate.force = false
            }
        }
    }
}
