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
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.sp
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
import com.andrexthedev.autovybz.ui.components.VybzIcons
import com.andrexthedev.autovybz.ui.home.SoundScreen
import com.andrexthedev.autovybz.ui.intro.IntroGate
import com.andrexthedev.autovybz.ui.intro.IntroOverlay
import com.andrexthedev.autovybz.preset.AutoVybzPresetStore
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
 * AutoVybz entry point (launcher). New screens are Jetpack Compose; the engine
 * screens (parametric EQ, multiband compressor, limiter, ...) stay classic
 * Views and are opened from the Studio tab.
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
        // Cold start: brand intro (silent, skippable). Not after rotation / process restore.
        val playIntro = savedInstanceState == null && IntroGate.shouldPlay()
        // First start (no calibration yet) opens the calibration, later starts open the everyday Sound screen.
        // One small directory listing; done before the first frame so the start tab never flips.
        val startTab = if (AutoVybzPresetStore.existingNames(this).isEmpty()) Tab.WIZARD else Tab.HOME
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
                var intro by remember { mutableStateOf(playIntro) }
                AutoVybzApp(
                    startTab = startTab,
                    wizardContent = { openHelp, openSound ->
                        WizardScreen(
                            vm = wizard,
                            state = state,
                            onOpenHelp = openHelp,
                            onOpenSound = openSound,
                            onOpenProEq = { startActivity(Intent(this, MainActivity::class.java)) },
                        )
                    },
                )
                if (intro) IntroOverlay(onFinished = { intro = false })
            }
        }
    }
}

/** Test tags stay `avz:tab:<NAME>` (PRO is the Studio tab). */
private enum class Tab(val label: Int, val icon: ImageVector) {
    HOME(R.string.avz_nav_home, VybzIcons.Sound),
    WIZARD(R.string.avz_nav_wizard, VybzIcons.Calibrate),
    PRO(R.string.avz_nav_pro, VybzIcons.Studio),
    HELP(R.string.avz_nav_help, VybzIcons.Help),
    ABOUT(R.string.avz_nav_about, VybzIcons.Info),
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AutoVybzApp(startTab: Tab, wizardContent: @Composable (openHelp: () -> Unit, openSound: () -> Unit) -> Unit) {
    var tabIndex by rememberSaveable { mutableIntStateOf(startTab.ordinal) }
    val tab = Tab.values()[tabIndex]
    // System back on the other tabs returns to the start tab instead of closing the app.
    BackHandler(enabled = tab != startTab) { tabIndex = startTab.ordinal }
    // Landscape phones (and split screen) have little height: header + bottom bar would eat half of it.
    // There the navigation moves to a side rail and the header drops its subtitle.
    val heightDp = LocalConfiguration.current.screenHeightDp
    val compact = heightDp < 480
    // Small phones (720x1280 ≈ 640 dp): the tagline wrapped to two lines and pushed the content down.
    val compactHeader = heightDp < 700
    val imeVisible = WindowInsets.isImeVisible
    Scaffold(
        containerColor = AutoVybzColors.Background,
        topBar = {
            // Landscape with the keyboard open: only ~40 % of the height is left above the keyboard. The header goes
            // (the rail keeps the navigation reachable), the field being edited gets the whole strip.
            if (compact && imeVisible) Spacer(Modifier.windowInsetsTopHeight(WindowInsets.safeDrawing))
            else AutoVybzHeader(subtitle = stringResource(R.string.avz_tagline), compact = compactHeader)
        },
        bottomBar = {
            if (!compact) NavigationBar(containerColor = AutoVybzColors.BackgroundDeep) {
                for (t in Tab.values()) {
                    NavigationBarItem(
                        selected = t == tab,
                        onClick = { tabIndex = t.ordinal },
                        modifier = Modifier.testTag("avz:tab:${t.name}"),
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(stringResource(t.label), style = navLabel(), maxLines = 1) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = AutoVybzColors.OnGreen,
                            selectedTextColor = AutoVybzColors.Green,
                            indicatorColor = AutoVybzColors.Green,
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
                        label = { Text(stringResource(t.label), style = navLabel(), maxLines = 1) },
                        colors = NavigationRailItemDefaults.colors(
                            selectedIconColor = AutoVybzColors.OnGreen,
                            selectedTextColor = AutoVybzColors.Green,
                            indicatorColor = AutoVybzColors.Green,
                            unselectedIconColor = AutoVybzColors.OnDarkMuted,
                            unselectedTextColor = AutoVybzColors.OnDarkMuted,
                        ),
                    )
                }
            }
            Box(Modifier.weight(1f).fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    when (tab) {
                        Tab.HOME -> SoundScreen(onCalibrate = { tabIndex = Tab.WIZARD.ordinal })
                        Tab.WIZARD -> wizardContent({ tabIndex = Tab.HELP.ordinal }, { tabIndex = Tab.HOME.ordinal })
                        Tab.PRO -> ProScreen()
                        Tab.HELP -> HelpScreen()
                        Tab.ABOUT -> AboutScreen()
                    }
                }
            }
        }
    }
}

/**
 * Tab labels: 11 sp without extra tracking - five tabs on a 360 dp phone leave 72 dp each, and "Einmessen" must fit
 * there at 130 % font size (67 dp measured with Outfit Medium; with the default label style it needed 78 dp).
 */
@Composable
private fun navLabel() = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, letterSpacing = 0.sp)
