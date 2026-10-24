package com.andrexthedev.autovybz.e2e

import android.Manifest
import android.app.Activity
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.bearinmind.equalizer314.LimiterActivity
import com.bearinmind.equalizer314.MainActivity
import com.bearinmind.equalizer314.MbcActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The inherited Equalizer314 screens (Pro area) still start next to the new AutoVybz launcher. */
@RunWith(AndroidJUnit4::class)
class LegacyActivitiesE2ETest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        *buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray(),
    )

    private fun <A : Activity> startsAndStops(cls: Class<A>) {
        ActivityScenario.launch(cls).use { sc ->
            sc.moveToState(Lifecycle.State.RESUMED)
            assertTrue("${cls.simpleName}: ${sc.state}", sc.state.isAtLeast(Lifecycle.State.STARTED))
            Thread.sleep(1500) // let deferred initialisation run (crashes would kill the process here)
            sc.recreate()
            assertTrue("${cls.simpleName} after recreate: ${sc.state}", sc.state.isAtLeast(Lifecycle.State.STARTED))
        }
    }

    @Test fun classicEqualizerScreen() = startsAndStops(MainActivity::class.java)
    @Test fun multibandCompressorScreen() = startsAndStops(MbcActivity::class.java)
    @Test fun limiterScreen() = startsAndStops(LimiterActivity::class.java)
}
