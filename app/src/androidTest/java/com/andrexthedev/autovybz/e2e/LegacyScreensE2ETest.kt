package com.andrexthedev.autovybz.e2e

import android.Manifest
import android.app.Activity
import android.os.Build
import android.content.Intent
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.UiDevice
import com.bearinmind.equalizer314.AudioEffectsPipelineActivity
import com.bearinmind.equalizer314.AudioMiscSettingsActivity
import com.bearinmind.equalizer314.AudioOutputActivity
import com.bearinmind.equalizer314.AutoEqActivity
import com.bearinmind.equalizer314.ChannelInputActivity
import com.bearinmind.equalizer314.ChannelSideEqActivity
import com.bearinmind.equalizer314.ConvertToApoActivity
import com.bearinmind.equalizer314.CustomPresetsActivity
import com.bearinmind.equalizer314.EnvironmentalReverbActivity
import com.bearinmind.equalizer314.ExperimentalActivity
import com.bearinmind.equalizer314.LimiterActivity
import com.bearinmind.equalizer314.MainActivity
import com.bearinmind.equalizer314.MbcActivity
import com.bearinmind.equalizer314.MeasurementSelectActivity
import com.bearinmind.equalizer314.PresetsConversionsActivity
import com.bearinmind.equalizer314.SpectrumControlActivity
import com.bearinmind.equalizer314.TargetCurveActivity
import com.bearinmind.equalizer314.TargetSelectActivity
import com.bearinmind.equalizer314.UiEqModesActivity
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestWatcher
import org.junit.rules.Timeout
import org.junit.runner.Description
import org.junit.runner.RunWith

/**
 * Every inherited Equalizer314 screen (all 19 activities of the Pro area): starts, survives a recreation (rotation,
 * language change) and does not crash - on API 28/30/35 in the device jobs. With `-e avz_shots true` each screen is
 * also photographed (top + scrolled) for the screenshot matrix, so the German / Spanish texts of the inherited UI
 * get the same visual review as the AutoVybz screens. Previously only MainActivity, MbcActivity and LimiterActivity
 * were started (the former LegacyActivitiesE2ETest).
 */
@RunWith(AndroidJUnit4::class)
class LegacyScreensE2ETest {

    private val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        *buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray(),
    )

    private val shots = object : TestWatcher() {
        override fun starting(description: Description) = Shots.begin(description)
    }

    /** Hard limit per screen: a hanging screen fails its own test instead of the whole device job. */
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(Timeout.seconds(90)).around(permissions).around(shots)

    private val inst get() = InstrumentationRegistry.getInstrumentation()

    /** Resumed instance of [cls], read on the main thread - no idle wait (see class comment). */
    private fun resumed(cls: Class<*>): Activity? {
        var a: Activity? = null
        inst.runOnMainSync {
            a = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull { cls.isInstance(it) }
        }
        return a
    }

    private fun awaitResumed(cls: Class<*>, what: String, other: Activity? = null): Activity {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            resumed(cls)?.takeIf { it !== other }?.let { return it }
            Thread.sleep(200)
        }
        throw AssertionError("${cls.simpleName} never reached RESUMED $what (crash, ANR or it finished itself)")
    }

    /**
     * No ActivityScenario here: its lifecycle waits call waitForIdleSync(), which never returns for screens that
     * animate continuously (spectrum previews) - campaign 8 hung all device jobs until their timeout.
     */
    private fun screen(cls: Class<out Activity>) {
        val intent = Intent(inst.targetContext, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        inst.targetContext.startActivity(intent)
        val first = awaitResumed(cls, "after start")
        Thread.sleep(1200) // deferred initialisation (a crash would kill the process here)
        val name = cls.simpleName.removeSuffix("Activity").lowercase()
        Shots.takeRaw("${name}_top", settle = { Thread.sleep(600) })
        if (Shots.enabled) {
            val d = UiDevice.getInstance(inst)
            d.swipe(d.displayWidth / 2, (d.displayHeight * 0.78).toInt(), d.displayWidth / 2, (d.displayHeight * 0.3).toInt(), 25)
            Shots.takeRaw("${name}_scrolled", settle = { Thread.sleep(800) })
        }
        inst.runOnMainSync { first.recreate() }
        val second = awaitResumed(cls, "after recreate", other = first)
        Thread.sleep(500)
        inst.runOnMainSync { second.finish() }
        val deadline = System.currentTimeMillis() + 10_000
        while (resumed(cls) != null && System.currentTimeMillis() < deadline) Thread.sleep(200)
    }

    @Test fun mainEqualizer() = screen(MainActivity::class.java)
    @Test fun multibandCompressor() = screen(MbcActivity::class.java)
    @Test fun limiter() = screen(LimiterActivity::class.java)
    @Test fun audioMiscSettings() = screen(AudioMiscSettingsActivity::class.java)
    @Test fun uiEqModes() = screen(UiEqModesActivity::class.java)
    @Test fun autoEq() = screen(AutoEqActivity::class.java)
    @Test fun targetCurve() = screen(TargetCurveActivity::class.java)
    @Test fun measurementSelect() = screen(MeasurementSelectActivity::class.java)
    @Test fun targetSelect() = screen(TargetSelectActivity::class.java)
    @Test fun spectrumControl() = screen(SpectrumControlActivity::class.java)
    @Test fun experimental() = screen(ExperimentalActivity::class.java)
    @Test fun customPresets() = screen(CustomPresetsActivity::class.java)
    @Test fun channelSideEq() = screen(ChannelSideEqActivity::class.java)
    @Test fun convertToApo() = screen(ConvertToApoActivity::class.java)
    @Test fun presetsConversions() = screen(PresetsConversionsActivity::class.java)
    @Test fun audioEffectsPipeline() = screen(AudioEffectsPipelineActivity::class.java)
    @Test fun audioOutput() = screen(AudioOutputActivity::class.java)
    @Test fun channelInput() = screen(ChannelInputActivity::class.java)
    @Test fun environmentalReverb() = screen(EnvironmentalReverbActivity::class.java)
}
