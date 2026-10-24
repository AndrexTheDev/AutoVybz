package com.andrexthedev.autovybz.ui

import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.testTag
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.ui.platform.LocalConfiguration
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.andrexthedev.autovybz.measurement.ListeningEnvironment
import com.andrexthedev.autovybz.measurement.audio.EqBypass
import com.andrexthedev.autovybz.ui.about.AboutScreen
import com.andrexthedev.autovybz.ui.components.AutoVybzHeader
import com.andrexthedev.autovybz.ui.help.HelpScreen
import com.andrexthedev.autovybz.ui.pro.ProScreen
import com.andrexthedev.autovybz.ui.theme.AutoVybzColors
import com.andrexthedev.autovybz.ui.theme.AutoVybzTheme
import com.andrexthedev.autovybz.ui.wizard.WizardScreen
import com.andrexthedev.autovybz.ui.wizard.WizardStep
import com.andrexthedev.autovybz.ui.wizard.WizardViewModel
import com.bearinmind.equalizer314.MainActivity
import com.bearinmind.equalizer314.R

/**
 * AutoVybz entry point (launcher). New screens are Jetpack Compose; the
 * inherited Equalizer314 screens (10-band PEQ, MBC, limiter, ...) stay classic
 * Views and are opened from the Pro tab.
 */
class AutoVybzActivity : ComponentActivity() {

    private val wizard: WizardViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Android 15+ forces edge-to-edge for targetSdk >= 35. Opt in on every version so the layout is identical
        // from API 28 to 36; the header, navigation and IME insets are handled explicitly in Compose.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // AutoVybz replaced MainActivity as launcher, so it takes over the "restore EQ on app open" fallback.
        if (savedInstanceState == null) EqBypass.startIfPersistedOn(this)
        setContent {
            AutoVybzTheme {
                val state by wizard.state.collectAsState()
                // Keep the screen on while measuring - a screen-off would stop audio on some OEMs.
                val busy = state.step is WizardStep.Countdown || state.step is WizardStep.Measuring ||
                    state.step is WizardStep.Preparing || state.step is WizardStep.Analyzing || state.step is WizardStep.MovePhone
                LaunchedEffect(busy) {
                    if (busy) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                // Sunlight mode: full screen brightness while calibrating outdoors (only for this window,
                // the system setting is untouched and restored automatically).
                val sunlight = state.environment == ListeningEnvironment.OUTDOOR && state.step.let {
                    it is WizardStep.ConfirmSafety || it is WizardStep.Countdown || it is WizardStep.Preparing || it is WizardStep.Measuring ||
                        it is WizardStep.MovePhone || it is WizardStep.Analyzing || it is WizardStep.Result
                }
                LaunchedEffect(sunlight) {
                    window.attributes = window.attributes.apply {
                        screenBrightness = if (sunlight) 1f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    }
                }
                AutoVybzApp(
                    wizardContent = { openHelp ->
                        WizardScreen(
                            vm = wizard,
                            state = state,
                            onOpenHelp = openHelp,
                            onOpenProEq = { startActivity(Intent(this, MainActivity::class.java)) },
                        )
                    },
                )
            }
        }
    }
}

private enum class Tab(val label: Int, val icon: ImageVector) {
    WIZARD(R.string.avz_nav_wizard, Icons.Filled.PlayArrow),
    PRO(R.string.avz_nav_pro, Icons.Filled.Build),
    HELP(R.string.avz_nav_help, Icons.Filled.Info),
    ABOUT(R.string.avz_nav_about, Icons.Filled.Favorite),
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AutoVybzApp(wizardContent: @Composable (openHelp: () -> Unit) -> Unit) {
    var tabIndex by rememberSaveable { mutableIntStateOf(Tab.WIZARD.ordinal) }
    val tab = Tab.values()[tabIndex]
    // System back on Pro / Help / About returns to the wizard instead of closing the app.
    BackHandler(enabled = tab != Tab.WIZARD) { tabIndex = Tab.WIZARD.ordinal }
    // Landscape phones (and split screen) have little height: header + bottom bar would eat half of it.
    // There the navigation moves to a side rail and the header drops its subtitle.
    val compact = LocalConfiguration.current.screenHeightDp < 480
    Scaffold(
        containerColor = AutoVybzColors.Background,
        topBar = { AutoVybzHeader(subtitle = stringResource(R.string.avz_tagline), compact = compact) },
        bottomBar = {
            if (!compact) NavigationBar(containerColor = AutoVybzColors.BackgroundDeep) {
                for (t in Tab.values()) {
                    NavigationBarItem(
                        selected = t == tab,
                        onClick = { tabIndex = t.ordinal },
                        modifier = Modifier.testTag("avz:tab:${t.name}"),
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(stringResource(t.label)) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = AutoVybzColors.Gold,
                            selectedTextColor = AutoVybzColors.Gold,
                            indicatorColor = AutoVybzColors.GreenContainer,
                            unselectedIconColor = AutoVybzColors.OnDarkMuted,
                            unselectedTextColor = AutoVybzColors.OnDarkMuted,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        // consume the bar paddings first so imePadding only adds the part of the keyboard above the nav bar
        Row(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            if (compact) NavigationRail(containerColor = AutoVybzColors.BackgroundDeep) {
                for (t in Tab.values()) {
                    NavigationRailItem(
                        selected = t == tab,
                        onClick = { tabIndex = t.ordinal },
                        modifier = Modifier.testTag("avz:tab:${t.name}"),
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(stringResource(t.label)) },
                        colors = NavigationRailItemDefaults.colors(
                            selectedIconColor = AutoVybzColors.Gold,
                            selectedTextColor = AutoVybzColors.Gold,
                            indicatorColor = AutoVybzColors.GreenContainer,
                            unselectedIconColor = AutoVybzColors.OnDarkMuted,
                            unselectedTextColor = AutoVybzColors.OnDarkMuted,
                        ),
                    )
                }
            }
            Box(Modifier.weight(1f).fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    when (tab) {
                        Tab.WIZARD -> wizardContent { tabIndex = Tab.HELP.ordinal }
                        Tab.PRO -> ProScreen()
                        Tab.HELP -> HelpScreen()
                        Tab.ABOUT -> AboutScreen()
                    }
                }
            }
        }
    }
}
