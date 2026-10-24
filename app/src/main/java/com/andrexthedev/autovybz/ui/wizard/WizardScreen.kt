@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.andrexthedev.autovybz.ui.wizard

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import com.andrexthedev.autovybz.ui.components.AdaptiveButtonRow
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.andrexthedev.autovybz.measurement.ListeningEnvironment
import com.andrexthedev.autovybz.measurement.MeasurementProgress
import com.andrexthedev.autovybz.measurement.audio.RouteClassifier
import com.andrexthedev.autovybz.measurement.dsp.MeasurementWarning
import com.andrexthedev.autovybz.measurement.eq.TargetType
import com.andrexthedev.autovybz.measurement.mic.ProfileConfidence
import com.andrexthedev.autovybz.ui.components.AvzCard
import com.andrexthedev.autovybz.ui.components.ChartSeries
import com.andrexthedev.autovybz.ui.components.ExportButton
import com.andrexthedev.autovybz.ui.components.FrequencyResponseChart
import com.andrexthedev.autovybz.ui.components.LevelMeter
import com.andrexthedev.autovybz.ui.components.SectionTitle
import com.andrexthedev.autovybz.ui.share.ResultSharer
import com.andrexthedev.autovybz.ui.theme.AutoVybzColors
import com.bearinmind.equalizer314.R
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun WizardScreen(
    vm: WizardViewModel,
    state: WizardUiState,
    onOpenHelp: () -> Unit,
    onOpenProEq: () -> Unit,
) {
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.onStartClicked(granted)
    }
    val startMeasurement = {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (granted) vm.onStartClicked(true) else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
    // Settings may have been changed in the Pro tab meanwhile.
    LaunchedEffect(Unit) { vm.reloadSettings() }

    // Destructive actions (unsaved result, measured positions) ask first - also for the system back button.
    var confirm by rememberSaveable { mutableStateOf<ConfirmKind?>(null) }
    val measuredSoFar = measuredPositions(state.step)
    BackHandler(enabled = state.step !is WizardStep.Idle && state.step !is WizardStep.Analyzing) {
        when (state.step) {
            is WizardStep.Result -> confirm = ConfirmKind.DISCARD_RESULT
            else -> if (measuredSoFar > 0) confirm = ConfirmKind.CANCEL_SESSION else vm.onCancel()
        }
    }
    confirm?.let { kind ->
        ConfirmDialog(
            kind = kind,
            measured = measuredSoFar,
            onConfirm = { confirm = null; vm.onReset() },
            onDismiss = { confirm = null },
        )
    }
    val cancelSession = { if (measuredSoFar > 0) confirm = ConfirmKind.CANCEL_SESSION else vm.onCancel() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Only animate when the *kind* of step changes (progress updates re-use the same content).
        AnimatedContent(targetState = state.step, contentKey = { it::class }, label = "wizard-step") { shown ->
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                when (val step = shown) {
                    WizardStep.Idle -> IdleStep(state, vm, startMeasurement, onOpenHelp)
                    is WizardStep.RouteBlocked -> RouteBlockedStep(step, onRetry = startMeasurement, onBack = vm::onReset)
                    is WizardStep.ConfirmSafety -> SafetyStep(step, state.environment, vm)
                    is WizardStep.Countdown -> CountdownStep(step, cancelSession)
                    WizardStep.Preparing -> BusyStep(stringResource(R.string.avz_preparing), null)
                    is WizardStep.Measuring -> MeasuringStep(step, cancelSession)
                    is WizardStep.MovePhone -> MovePhoneStep(
                        step, state.environment, vm::onNextPosition, vm::onRepeatLastPosition, vm::onFinishWithMeasured, cancelSession,
                    )
                    WizardStep.Analyzing -> BusyStep(stringResource(R.string.avz_analyzing), null)
                    is WizardStep.Result -> ResultStep(step, state, vm, onMeasureAgain = { confirm = ConfirmKind.DISCARD_RESULT })
                    is WizardStep.Saved -> SavedStep(step, onOpenProEq, vm::onReset)
                    is WizardStep.Error -> ErrorStep(
                        step,
                        onRetry = startMeasurement,
                        onBack = cancelSession,
                        onResume = vm::onNextPosition,
                        onFinish = vm::onFinishWithMeasured,
                        onSettings = {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                                )
                            }
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/* ------------------------------------------------------------------ idle -- */

@Composable
private fun IdleStep(state: WizardUiState, vm: WizardViewModel, onStart: () -> Unit, onOpenHelp: () -> Unit) {
    val focus = LocalFocusManager.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.avz_wizard_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = AutoVybzColors.OnDarkMuted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        BigStartButton(onStart)
    }

    OutlinedTextField(
        value = state.speakerName,
        onValueChange = vm::onSpeakerNameChanged,
        label = { Text(stringResource(R.string.avz_speaker_name_label)) },
        placeholder = { Text(stringResource(R.string.avz_speaker_name_hint)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        colors = avzTextFieldColors(),
        modifier = Modifier.fillMaxWidth().testTag("avz:speakerName"),
    )

    SectionTitle(stringResource(R.string.avz_env_label))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { // wraps instead of splitting words (W10)
        for ((env, label) in listOf(
            ListeningEnvironment.INDOOR to R.string.avz_env_indoor,
            ListeningEnvironment.OUTDOOR to R.string.avz_env_outdoor,
        )) {
            FilterChip(
                selected = state.environment == env,
                onClick = { vm.onEnvironmentChanged(env) },
                label = { Text(stringResource(label)) },
                modifier = Modifier.testTag("avz:env:${env.name}"),
                leadingIcon = {
                    Icon(if (env == ListeningEnvironment.OUTDOOR) Icons.Filled.Star else Icons.Filled.Home, null, Modifier.size(18.dp))
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = AutoVybzColors.GreenContainer,
                    selectedLabelColor = AutoVybzColors.GreenBright,
                    selectedLeadingIconColor = AutoVybzColors.Gold,
                ),
            )
        }
    }
    Text(
        stringResource(if (state.environment == ListeningEnvironment.OUTDOOR) R.string.avz_env_outdoor_desc else R.string.avz_env_indoor_desc),
        style = MaterialTheme.typography.bodySmall,
        color = AutoVybzColors.OnDarkMuted,
    )

    SectionTitle(stringResource(R.string.avz_target_label))
    TargetChooser(state.target, vm::onTargetChanged)

    SectionTitle(stringResource(R.string.avz_positions_label))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { // wraps instead of splitting words (W10)
        for ((count, label) in listOf(1 to R.string.avz_positions_quick, 3 to R.string.avz_positions_precise)) {
            FilterChip(
                selected = state.positions == count,
                onClick = { vm.onPositionsChanged(count) },
                label = { Text(stringResource(label)) },
                modifier = Modifier.testTag("avz:positions:$count"),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = AutoVybzColors.GreenContainer,
                    selectedLabelColor = AutoVybzColors.GreenBright,
                ),
            )
        }
    }
    Text(
        stringResource(if (state.positions == 3) R.string.avz_positions_precise_desc else R.string.avz_positions_quick_desc),
        style = MaterialTheme.typography.bodySmall,
        color = AutoVybzColors.OnDarkMuted,
    )

    AvzCard {
        Text(stringResource(R.string.avz_wizard_before_title), style = MaterialTheme.typography.titleMedium)
        NumberedLine(1, stringResource(R.string.avz_wizard_before_1))
        NumberedLine(2, stringResource(R.string.avz_wizard_before_2))
        NumberedLine(3, stringResource(R.string.avz_wizard_before_3))
        TextButton(onClick = onOpenHelp) {
            Icon(Icons.Filled.Info, contentDescription = null, tint = AutoVybzColors.Gold)
            Text("  " + stringResource(R.string.avz_wizard_help_link), color = AutoVybzColors.Gold)
        }
    }
}

/** The one big round button laypeople need. */
@Composable
private fun BigStartButton(onClick: () -> Unit) {
    Box(
        Modifier.size(212.dp).clip(CircleShape)
            .background(Brush.radialGradient(listOf(AutoVybzColors.Green.copy(alpha = 0.35f), AutoVybzColors.Background))),
        contentAlignment = Alignment.Center,
    ) {
        Button(
            onClick = onClick,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = AutoVybzColors.Green, contentColor = AutoVybzColors.OnGreen),
            border = BorderStroke(3.dp, AutoVybzColors.Gold),
            modifier = Modifier.size(176.dp).testTag("avz:start"),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(48.dp))
                Text(
                    stringResource(R.string.avz_wizard_start),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun TargetChooser(selected: TargetType, onSelect: (TargetType) -> Unit) {
    val items = listOf(
        Triple(TargetType.SMART, R.string.avz_target_smart, R.string.avz_target_smart_desc),
        Triple(TargetType.FLAT, R.string.avz_target_flat, R.string.avz_target_flat_desc),
        Triple(TargetType.BASS_BOOST, R.string.avz_target_bass, R.string.avz_target_bass_desc),
        Triple(TargetType.HARMAN, R.string.avz_target_harman, R.string.avz_target_harman_desc),
        Triple(TargetType.CUSTOM, R.string.avz_target_custom, R.string.avz_target_custom_desc),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for ((type, label, _) in items) {
            FilterChip(
                selected = selected == type,
                onClick = { onSelect(type) },
                label = { Text(stringResource(label)) },
                modifier = Modifier.testTag("avz:target:${type.name}"),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = AutoVybzColors.GreenContainer,
                    selectedLabelColor = AutoVybzColors.GreenBright,
                    labelColor = AutoVybzColors.OnDarkMuted,
                ),
            )
        }
    }
    items.firstOrNull { it.first == selected }?.let {
        Text(stringResource(it.third), style = MaterialTheme.typography.bodySmall, color = AutoVybzColors.OnDarkMuted)
    }
}

@Composable
private fun NumberedLine(n: Int, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(22.dp).clip(CircleShape).background(AutoVybzColors.GreenContainer),
            contentAlignment = Alignment.Center,
        ) { Text(n.toString(), style = MaterialTheme.typography.labelMedium, color = AutoVybzColors.GreenBright) }
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/* --------------------------------------------------------- route blocked -- */

@Composable
private fun RouteBlockedStep(step: WizardStep.RouteBlocked, onRetry: () -> Unit, onBack: () -> Unit) {
    val headphones = step.verdict == RouteClassifier.Verdict.BLOCKED_HEADPHONES
    AvzCard(accent = AutoVybzColors.Error, modifier = Modifier.testTag("avz:blocked")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = AutoVybzColors.Error)
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(if (headphones) R.string.avz_route_headphones_title else R.string.avz_route_builtin_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
        }
        Text(stringResource(if (headphones) R.string.avz_route_headphones_text else R.string.avz_route_builtin_text))
        step.routeName?.let { Text(stringResource(R.string.avz_safety_route, it), color = AutoVybzColors.OnDarkMuted) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onBack, modifier = Modifier.testTag("avz:blocked:back")) { Text(stringResource(R.string.avz_back)) }
            Button(onClick = onRetry, modifier = Modifier.testTag("avz:blocked:retry")) {
                Icon(Icons.Filled.Refresh, contentDescription = null)
                Text("  " + stringResource(R.string.avz_retry))
            }
        }
    }
}

/* ---------------------------------------------------------------- safety -- */

@Composable
private fun SafetyStep(step: WizardStep.ConfirmSafety, environment: ListeningEnvironment, vm: WizardViewModel) {
    var readWarning by remember { mutableStateOf(false) }
    var isSpeaker by remember { mutableStateOf(!step.needsSpeakerConfirmation) }

    AvzCard(accent = AutoVybzColors.Gold) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = AutoVybzColors.Gold)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.avz_safety_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        }
        Text(stringResource(R.string.avz_safety_text))
        Bullet(stringResource(R.string.avz_safety_b1))
        Bullet(stringResource(R.string.avz_safety_b2))
        Bullet(stringResource(R.string.avz_safety_b3))
        Text(stringResource(R.string.avz_safety_effects), style = MaterialTheme.typography.bodySmall, color = AutoVybzColors.OnDarkMuted)
        step.routeName?.let { Text(stringResource(R.string.avz_safety_route, it), color = AutoVybzColors.GreenBright) }
    }

    if (environment == ListeningEnvironment.OUTDOOR) {
        AvzCard(accent = AutoVybzColors.Green) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Star, contentDescription = null, tint = AutoVybzColors.Gold)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.avz_out_check_title), style = MaterialTheme.typography.titleMedium)
            }
            Bullet(stringResource(R.string.avz_out_check_1))
            Bullet(stringResource(R.string.avz_out_check_2))
            Bullet(stringResource(R.string.avz_out_check_3))
            Bullet(stringResource(R.string.avz_out_check_4))
        }
    }

    AvzCard {
        Text(
            stringResource(R.string.avz_volume_label) + "  " + (step.volume * 100).roundToInt() + " %",
            style = MaterialTheme.typography.titleMedium,
        )
        if (step.volumeFixed) {
            Text(stringResource(R.string.avz_volume_fixed), color = AutoVybzColors.OnDarkMuted)
        } else {
            Slider(
                value = step.volume,
                onValueChange = vm::onVolumeChanged,
                modifier = Modifier.testTag("avz:safety:volume"),
                colors = SliderDefaults.colors(
                    thumbColor = AutoVybzColors.Gold,
                    activeTrackColor = AutoVybzColors.Green,
                    inactiveTrackColor = AutoVybzColors.SurfaceHigh,
                ),
            )
        }
    }

    if (step.needsSpeakerConfirmation) {
        CheckRow(isSpeaker, { isSpeaker = it }, stringResource(R.string.avz_safety_confirm_speaker), "avz:safety:speaker")
    }
    CheckRow(readWarning, { readWarning = it }, stringResource(R.string.avz_safety_confirm), "avz:safety:confirm")

    AdaptiveButtonRow(spacing = 12.dp) {
        OutlinedButton(onClick = vm::onCancel, modifier = Modifier.slot(1f).testTag("avz:safety:cancel")) { Text(stringResource(R.string.avz_cancel)) }
        Button(
            onClick = vm::onConfirmStart,
            enabled = readWarning && isSpeaker,
            colors = ButtonDefaults.buttonColors(containerColor = AutoVybzColors.Green),
            modifier = Modifier.slot(1.6f).testTag("avz:safety:start"),
        ) { Text(stringResource(R.string.avz_safety_start)) }
    }
}

@Composable
private fun Bullet(text: String) {
    Row {
        Text("•  ", color = AutoVybzColors.Gold)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CheckRow(checked: Boolean, onChange: (Boolean) -> Unit, text: String, tag: String) {
    // The whole row toggles (bigger touch target, TalkBack reads text + state together).
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            colors = CheckboxDefaults.colors(checkedColor = AutoVybzColors.Green, checkmarkColor = AutoVybzColors.OnGreen),
            modifier = Modifier.padding(12.dp),
        )
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
    }
}

/* ------------------------------------------------------------- measuring -- */

@Composable
private fun MeasuringStep(step: WizardStep.Measuring, onCancel: () -> Unit) {
    val phaseText = when (step.phase) {
        MeasurementProgress.Phase.SILENCE -> R.string.avz_phase_silence
        MeasurementProgress.Phase.PINK_NOISE -> R.string.avz_phase_pink
        MeasurementProgress.Phase.SWEEP -> R.string.avz_phase_sweep
        MeasurementProgress.Phase.DECAY -> R.string.avz_phase_decay
    }
    Column(Modifier.fillMaxWidth().testTag("avz:measuring"), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(24.dp))
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { step.fraction },
                modifier = Modifier.size(180.dp),
                color = AutoVybzColors.Green,
                trackColor = AutoVybzColors.SurfaceHigh,
                strokeWidth = 10.dp,
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val secondsLeft = ((1f - step.fraction) * 11f).roundToInt().coerceAtLeast(0)
                Text("$secondsLeft s", style = MaterialTheme.typography.displaySmall, color = AutoVybzColors.Gold)
                Text(stringResource(R.string.avz_measuring_title), color = AutoVybzColors.OnDarkMuted)
            }
        }
        Spacer(Modifier.height(20.dp))
        if (step.totalPositions > 1) {
            Text(
                stringResource(R.string.avz_position_of, step.position, step.totalPositions),
                style = MaterialTheme.typography.labelLarge,
                color = AutoVybzColors.Gold,
            )
        }
        Text(stringResource(phaseText), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.avz_measuring_hint), color = AutoVybzColors.OnDarkMuted, textAlign = TextAlign.Center)
    }
    AvzCard {
        Text(stringResource(R.string.avz_input_level), style = MaterialTheme.typography.titleSmall)
        LevelMeter(step.inputPeak)
        Text(stringResource(R.string.avz_eq_paused), style = MaterialTheme.typography.bodySmall, color = AutoVybzColors.OnDarkMuted)
    }
    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().testTag("avz:measuring:cancel")) { Text(stringResource(R.string.avz_cancel)) }
}

/* ------------------------------------------------------------- countdown -- */

@Composable
private fun CountdownStep(step: WizardStep.Countdown, onCancel: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 32.dp).testTag("avz:countdown"), horizontalAlignment = Alignment.CenterHorizontally) {
        if (step.totalPositions > 1) {
            Text(
                stringResource(R.string.avz_position_of, step.position, step.totalPositions),
                style = MaterialTheme.typography.labelLarge,
                color = AutoVybzColors.Gold,
            )
            Spacer(Modifier.height(12.dp))
        }
        Box(
            Modifier.size(160.dp).clip(CircleShape).background(AutoVybzColors.GreenContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                step.secondsLeft.toString(),
                style = MaterialTheme.typography.displaySmall.copy(fontSize = 72.sp, lineHeight = 80.sp),
                color = AutoVybzColors.Gold,
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.avz_countdown_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.avz_countdown_hint), color = AutoVybzColors.OnDarkMuted, textAlign = TextAlign.Center)
    }
    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().testTag("avz:countdown:cancel")) { Text(stringResource(R.string.avz_cancel)) }
}

/** Between two positions of a multi-position calibration. */
@Composable
private fun MovePhoneStep(
    step: WizardStep.MovePhone,
    environment: ListeningEnvironment,
    onContinue: () -> Unit,
    onRepeat: () -> Unit,
    onFinish: () -> Unit,
    onCancel: () -> Unit,
) {
    val outdoor = environment == ListeningEnvironment.OUTDOOR
    val disturbed = WizardViewModel.isDisturbed(step.lastWarnings)
    val dotsDescription = stringResource(R.string.avz_a11y_positions, step.completed, step.total)
    Column(Modifier.fillMaxWidth().padding(top = 16.dp).testTag("avz:move"), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.semantics { contentDescription = dotsDescription }) {
            for (i in 1..step.total) {
                Box(
                    Modifier.size(18.dp).clip(CircleShape)
                        .background(if (i <= step.completed) AutoVybzColors.Green else AutoVybzColors.SurfaceHigh),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Icon(Icons.Filled.LocationOn, contentDescription = null, tint = AutoVybzColors.Gold, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.avz_move_title, step.completed, step.total),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
    }
    if (step.completed < step.total) {
        AvzCard {
            Text(stringResource(if (outdoor) R.string.avz_move_text_outdoor else R.string.avz_move_text), style = MaterialTheme.typography.bodyLarge)
            Bullet(stringResource(if (outdoor) R.string.avz_move_tip_1_outdoor else R.string.avz_move_tip_1))
            Bullet(stringResource(R.string.avz_move_tip_2))
        }
    }
    if (disturbed) {
        Row(
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(AutoVybzColors.GoldContainer).padding(12.dp),
        ) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = AutoVybzColors.Gold)
            Spacer(Modifier.width(10.dp))
            Column {
                for (w in step.lastWarnings.filter { it != MeasurementWarning.HIGH_LATENCY }) {
                    Text(warningText(w, 0.0), style = MaterialTheme.typography.bodyMedium)
                }
                Text(stringResource(R.string.avz_move_warn), style = MaterialTheme.typography.bodySmall, color = AutoVybzColors.OnDarkMuted)
            }
        }
        OutlinedButton(onClick = onRepeat, modifier = Modifier.fillMaxWidth().testTag("avz:move:repeat"), border = BorderStroke(1.dp, AutoVybzColors.Gold)) {
            Icon(Icons.Filled.Refresh, contentDescription = null, tint = AutoVybzColors.Gold)
            Text("  " + stringResource(R.string.avz_move_repeat, step.completed), color = AutoVybzColors.Gold)
        }
    }
    val allMeasured = step.completed >= step.total
    Button(
        onClick = if (allMeasured) onFinish else onContinue,
        colors = ButtonDefaults.buttonColors(containerColor = AutoVybzColors.Green),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(if (allMeasured) "avz:move:finish" else "avz:move:continue"),
    ) {
        Icon(if (allMeasured) Icons.Filled.CheckCircle else Icons.Filled.PlayArrow, contentDescription = null)
        Text(
            "  " + if (allMeasured) LocalContext.current.resources.getQuantityString(R.plurals.avz_move_finish, step.completed, step.completed)
            else stringResource(R.string.avz_move_continue, step.completed + 1),
            style = MaterialTheme.typography.titleMedium,
        )
    }
    AdaptiveButtonRow(spacing = 8.dp) {
        OutlinedButton(onClick = onCancel, modifier = Modifier.slot(1f).testTag("avz:move:cancel")) { Text(stringResource(R.string.avz_cancel)) }
        if (!allMeasured) {
            OutlinedButton(onClick = onFinish, modifier = Modifier.slot(1.6f).testTag("avz:move:finishEarly")) {
                Text(LocalContext.current.resources.getQuantityString(R.plurals.avz_move_finish, step.completed, step.completed), textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun BusyStep(title: String, subtitle: String?) {
    Column(Modifier.fillMaxWidth().padding(top = 48.dp).testTag("avz:busy"), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = AutoVybzColors.Gold, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        subtitle?.let { Text(it, color = AutoVybzColors.OnDarkMuted) }
    }
}

/* ---------------------------------------------------------------- result -- */

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ResultStep(step: WizardStep.Result, state: WizardUiState, vm: WizardViewModel, onMeasureAgain: () -> Unit) {
    val o = step.outcome
    val e = o.autoEq
    val r = o.result
    val focus = LocalFocusManager.current

    Text(
        stringResource(R.string.avz_result_title),
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.testTag("avz:result").semantics { heading() },
    )
    AvzCard {
        FrequencyResponseChart(
            description = stringResource(R.string.avz_a11y_chart, fmt1(e.rmsErrorBeforeDb), fmt1(e.rmsErrorAfterDb)),
            frequencies = e.frequencies,
            series = listOf(
                ChartSeries(e.measuredDb, AutoVybzColors.CurveMeasured, label = stringResource(R.string.avz_legend_measured)),
                ChartSeries(e.targetDb, AutoVybzColors.CurveTarget, width = 2f, dashed = true, label = stringResource(R.string.avz_legend_target)),
                ChartSeries(e.predictedDb, AutoVybzColors.CurveCorrected, width = 3f, label = stringResource(R.string.avz_legend_corrected)),
            ),
        )
        Text(
            LocalContext.current.resources.getQuantityString(
                R.plurals.avz_result_summary,
                e.bands.size,
                e.bands.size,
                fmt1(e.preampDb),
                fmt1(e.rmsErrorBeforeDb),
                fmt1(e.rmsErrorAfterDb),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = AutoVybzColors.OnDarkMuted,
        )
        if (o.positions > 1) {
            Text(
                stringResource(R.string.avz_result_positions, o.positions, fmt1(o.positionSpreadDb ?: 0.0)),
                style = MaterialTheme.typography.bodyMedium,
                color = AutoVybzColors.GreenBright,
            )
        }
    }

    if ((o.positionSpreadDb ?: 0.0) > HIGH_POSITION_SPREAD_DB) {
        Row(
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(AutoVybzColors.SurfaceHigh).padding(12.dp),
        ) {
            Icon(Icons.Filled.Info, contentDescription = null, tint = AutoVybzColors.Gold)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.avz_result_spread_high), style = MaterialTheme.typography.bodyMedium)
        }
    }

    // Warnings
    for (w in r.warnings) {
        val text = warningText(w, r.latencyMs)
        val severe = w in SEVERE_WARNINGS
        Row(
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                .background(if (severe) AutoVybzColors.GoldContainer else AutoVybzColors.SurfaceHigh).padding(12.dp),
        ) {
            Icon(if (severe) Icons.Filled.Warning else Icons.Filled.Info, contentDescription = null, tint = AutoVybzColors.Gold)
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }

    if (e.noiseGatedRanges.isNotEmpty()) {
        Row(
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(AutoVybzColors.SurfaceHigh).padding(12.dp),
        ) {
            Icon(Icons.Filled.Info, contentDescription = null, tint = AutoVybzColors.Gold)
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.avz_result_gated, e.noiseGatedRanges.joinToString(", ") { formatRange(it.first, it.second) }),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    if (o.request.environment == ListeningEnvironment.OUTDOOR) {
        AvzCard(accent = AutoVybzColors.Green) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Star, contentDescription = null, tint = AutoVybzColors.Gold)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.avz_out_result_title), style = MaterialTheme.typography.titleMedium)
            }
            Text(stringResource(R.string.avz_out_result_loudness, fmt1(e.preampDb)), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.avz_out_result_limiter), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.avz_out_party_tip), style = MaterialTheme.typography.bodyMedium, color = AutoVybzColors.GreenBright)
            if (o.positions < 2) {
                Text(stringResource(R.string.avz_out_single_position), style = MaterialTheme.typography.bodySmall, color = AutoVybzColors.Gold)
            }
        }
    }

    SectionTitle(stringResource(R.string.avz_target_label))
    TargetChooser(state.target, vm::onTargetChanged)
    SectionTitle(stringResource(R.string.avz_result_precision))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { // wraps instead of splitting words (W10)
        for ((count, label) in listOf(5 to R.string.avz_bands_5, 10 to R.string.avz_bands_10)) {
            FilterChip(
                selected = state.bandCount == count,
                onClick = { vm.onBandCountChanged(count) },
                label = { Text(stringResource(label)) },
                modifier = Modifier.testTag("avz:bands:$count"),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = AutoVybzColors.GreenContainer,
                    selectedLabelColor = AutoVybzColors.GreenBright,
                ),
            )
        }
    }

    val nameInView = remember { BringIntoViewRequester() }
    var nameFocused by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = step.presetName,
        onValueChange = vm::onPresetNameChanged,
        label = { Text(stringResource(R.string.avz_preset_name)) },
        isError = !step.nameValid,
        supportingText = when {
            !step.nameValid -> ({ Text(stringResource(R.string.avz_preset_name_invalid)) })
            step.nameExists -> ({ Text(stringResource(R.string.avz_preset_name_exists), color = AutoVybzColors.Gold) })
            else -> null
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        colors = avzTextFieldColors(),
        modifier = Modifier.fillMaxWidth().bringIntoViewRequester(nameInView)
            .onFocusChanged { nameFocused = it.isFocused }.testTag("avz:result:name"),
    )
    // Edge-to-edge: the keyboard shrinks the content via imePadding, but nothing scrolled the field back into
    // view, and the focused-field auto-scroll would only reveal the text line. Bring the whole field incl. the
    // error / "already exists" hint above the keyboard whenever the keyboard height changes (end-user test W5).
    val hint = !step.nameValid || step.nameExists
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(hint, nameFocused, imeBottom) {
        if (hint || (nameFocused && imeBottom > 0)) runCatching { nameInView.bringIntoView() }
    }

    Button(
        onClick = { vm.onSave(apply = true) },
        enabled = step.nameValid,
        colors = ButtonDefaults.buttonColors(containerColor = AutoVybzColors.Green),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("avz:result:saveApply"),
    ) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null)
        Text("  " + stringResource(R.string.avz_save_apply), style = MaterialTheme.typography.titleMedium)
    }
    AdaptiveButtonRow(spacing = 8.dp) {
        OutlinedButton(
            onClick = { vm.onSave(apply = false) },
            enabled = step.nameValid,
            modifier = Modifier.slot(1f).testTag("avz:result:saveOnly"),
        ) { Text(stringResource(R.string.avz_save_only), textAlign = TextAlign.Center) }
        OutlinedButton(onClick = onMeasureAgain, modifier = Modifier.slot(1f).testTag("avz:result:again")) {
            Text(stringResource(R.string.avz_measure_again), textAlign = TextAlign.Center)
        }
    }
    AdaptiveButtonRow(spacing = 8.dp) {
        ExportButton(preset = vm::currentPreset, modifier = Modifier.slot(1f).testTag("avz:export"), fillWidth = true)
        ShareResultButton(o, step.presetName, Modifier.slot(1f).testTag("avz:share"))
    }

    // Details for the curious
    com.andrexthedev.autovybz.ui.components.CollapsibleCard(title = stringResource(R.string.avz_result_details)) {
        Text(stringResource(R.string.avz_detail_mic, o.micDescription), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.avz_detail_engine, "${o.capture.engineName}, FFT ${o.fftEngine}, ${o.capture.sourceUsed.name}"), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.avz_detail_range, e.lowLimitHz.roundToInt(), e.highLimitHz.roundToInt()), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.avz_detail_snr, r.medianSnrDb.roundToInt()), style = MaterialTheme.typography.bodySmall)
        if (o.micProfile?.confidence == ProfileConfidence.ESTIMATED) {
            Text(stringResource(R.string.avz_mic_estimated_note), style = MaterialTheme.typography.bodySmall, color = AutoVybzColors.Gold)
        }
        Text(stringResource(R.string.avz_detail_bands), style = MaterialTheme.typography.titleSmall)
        for (b in e.bands) {
            Text(
                String.format(Locale.US, "%-4s %7.1f Hz  %+5.1f dB  Q %.2f", b.type.apoCode, b.frequency, b.gainDb, b.q),
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                color = AutoVybzColors.OnDarkMuted,
            )
        }
    }
}

/* ----------------------------------------------------------------- saved -- */

@Composable
private fun SavedStep(step: WizardStep.Saved, onOpenProEq: () -> Unit, onDone: () -> Unit) {
    val outcome = step.outcome
    Column(Modifier.fillMaxWidth().padding(top = 32.dp).testTag("avz:saved"), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = AutoVybzColors.GreenBright, modifier = Modifier.size(96.dp))
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.avz_saved_title), style = MaterialTheme.typography.headlineMedium, color = AutoVybzColors.Gold, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(if (step.applied) R.string.avz_saved_applied else R.string.avz_saved_only, step.presetName),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth().testTag("avz:saved:done"), colors = ButtonDefaults.buttonColors(containerColor = AutoVybzColors.Green)) {
            Text(stringResource(R.string.avz_done))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onOpenProEq, modifier = Modifier.fillMaxWidth().testTag("avz:saved:pro")) { Text(stringResource(R.string.avz_open_pro_eq)) }
        if (outcome != null) {
            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(R.string.avz_share_invite),
                style = MaterialTheme.typography.bodyMedium,
                color = AutoVybzColors.OnDarkMuted,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            ShareResultButton(outcome, step.presetName, Modifier.fillMaxWidth())
        }
    }
}

/** Renders the result card and opens the system share sheet. */
@Composable
private fun ShareResultButton(outcome: com.andrexthedev.autovybz.measurement.MeasurementOutcome, presetName: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    OutlinedButton(
        onClick = { ResultSharer.share(context, outcome, presetName) },
        border = BorderStroke(1.dp, AutoVybzColors.Gold),
        modifier = modifier,
    ) {
        Icon(Icons.Filled.Share, contentDescription = null, tint = AutoVybzColors.Gold, modifier = Modifier.size(18.dp))
        Text("  " + stringResource(R.string.avz_share_result), color = AutoVybzColors.Gold)
    }
}

/* ----------------------------------------------------------------- error -- */

@Composable
private fun ErrorStep(
    step: WizardStep.Error,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onResume: () -> Unit,
    onFinish: () -> Unit,
    onSettings: () -> Unit,
) {
    AvzCard(accent = AutoVybzColors.Error, modifier = Modifier.testTag("avz:error")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = AutoVybzColors.Error)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.avz_err_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        }
        val detail = step.detail ?: "-"
        Text(
            when (step.error) {
                WizardError.PERMISSION_DENIED -> stringResource(R.string.avz_err_permission)
                WizardError.NO_SIGNAL -> stringResource(R.string.avz_err_no_signal)
                WizardError.CAPTURE_FAILED -> stringResource(R.string.avz_err_capture, detail)
                WizardError.ROUTE_CHANGED -> stringResource(R.string.avz_err_route_changed)
                WizardError.INTERRUPTED -> stringResource(R.string.avz_err_interrupted)
                WizardError.UNKNOWN -> stringResource(R.string.avz_err_unknown, detail)
            },
            modifier = Modifier.testTag("avz:error:${step.error.name}"),
        )
        if (step.error == WizardError.PERMISSION_DENIED) {
            Text(stringResource(R.string.avz_permission_rationale), style = MaterialTheme.typography.bodySmall, color = AutoVybzColors.OnDarkMuted)
        }
        val resume = step.resumePosition
        if (resume != null) {
            // Multi-position: the positions measured before stay - only this one has to be repeated.
            Text(
                stringResource(R.string.avz_err_resume_hint, step.measuredPositions),
                style = MaterialTheme.typography.bodyMedium,
                color = AutoVybzColors.GreenBright,
            )
            Button(
                onClick = onResume,
                colors = ButtonDefaults.buttonColors(containerColor = AutoVybzColors.Green),
                modifier = Modifier.fillMaxWidth().testTag("avz:error:resume"),
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null)
                Text("  " + stringResource(R.string.avz_err_resume_retry, resume))
            }
            OutlinedButton(onClick = onFinish, modifier = Modifier.fillMaxWidth().testTag("avz:error:finish")) {
                Text(LocalContext.current.resources.getQuantityString(R.plurals.avz_move_finish, step.measuredPositions, step.measuredPositions), textAlign = TextAlign.Center)
            }
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth().testTag("avz:error:back")) { Text(stringResource(R.string.avz_cancel)) }
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = onBack, modifier = Modifier.testTag("avz:error:back")) { Text(stringResource(R.string.avz_back)) }
                if (step.error == WizardError.PERMISSION_DENIED) {
                    OutlinedButton(onClick = onSettings, modifier = Modifier.testTag("avz:error:settings")) { Text(stringResource(R.string.avz_err_permission_settings)) }
                }
                Button(onClick = onRetry, modifier = Modifier.testTag("avz:error:retry")) { Text(stringResource(R.string.avz_retry)) }
            }
        }
    }
}

/* ---------------------------------------------------------------- dialog -- */

internal enum class ConfirmKind { DISCARD_RESULT, CANCEL_SESSION }

/** Positions already measured in the running session (0 = nothing would be lost by cancelling). */
private fun measuredPositions(step: WizardStep): Int = when (step) {
    is WizardStep.MovePhone -> step.completed
    is WizardStep.Error -> step.measuredPositions
    is WizardStep.Measuring -> step.position - 1
    is WizardStep.Countdown -> step.position - 1
    else -> 0
}

@Composable
private fun ConfirmDialog(kind: ConfirmKind, measured: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AutoVybzColors.Surface,
        icon = { Icon(Icons.Filled.Warning, contentDescription = null, tint = AutoVybzColors.Gold) },
        title = {
            Text(stringResource(if (kind == ConfirmKind.DISCARD_RESULT) R.string.avz_discard_title else R.string.avz_cancel_session_title))
        },
        text = {
            Text(
                if (kind == ConfirmKind.DISCARD_RESULT) stringResource(R.string.avz_discard_text)
                else stringResource(R.string.avz_cancel_session_text, measured),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("avz:dialog:confirm")) {
                Text(
                    stringResource(if (kind == ConfirmKind.DISCARD_RESULT) R.string.avz_discard_confirm else R.string.avz_cancel_session_confirm),
                    color = AutoVybzColors.Error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("avz:dialog:dismiss")) {
                Text(
                    stringResource(if (kind == ConfirmKind.DISCARD_RESULT) R.string.avz_discard_keep else R.string.avz_cancel_session_keep),
                    color = AutoVybzColors.GreenBright,
                )
            }
        },
    )
}

/* ------------------------------------------------------------------ util -- */

@Composable
internal fun avzTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = AutoVybzColors.Green,
    unfocusedBorderColor = AutoVybzColors.Outline,
    focusedLabelColor = AutoVybzColors.GreenBright,
    cursorColor = AutoVybzColors.Gold,
)

@Composable
private fun warningText(w: MeasurementWarning, latencyMs: Double): String = when (w) {
    MeasurementWarning.CLIPPING -> stringResource(R.string.avz_warn_clipping)
    MeasurementWarning.LOW_SNR -> stringResource(R.string.avz_warn_low_snr)
    MeasurementWarning.SWEEP_NOISE_MISMATCH -> stringResource(R.string.avz_warn_mismatch)
    MeasurementWarning.HIGH_LATENCY -> stringResource(R.string.avz_warn_latency, latencyMs.roundToInt())
    MeasurementWarning.LOW_FREQ_NOISE -> stringResource(R.string.avz_warn_lf_noise)
    MeasurementWarning.NOISE_UNSTEADY -> stringResource(R.string.avz_warn_unsteady)
    MeasurementWarning.DROPOUT -> stringResource(R.string.avz_warn_dropout)
    MeasurementWarning.AUDIO_GLITCH -> stringResource(R.string.avz_warn_glitch)
}

/** Warnings that make the result questionable (highlighted in gold). */
private val SEVERE_WARNINGS = setOf(
    MeasurementWarning.CLIPPING, MeasurementWarning.LOW_SNR, MeasurementWarning.DROPOUT, MeasurementWarning.AUDIO_GLITCH,
)

/** "41–320 Hz" / "3.0–8.0 kHz". */
private fun formatRange(lo: Double, hi: Double): String {
    fun f(v: Double) = if (v >= 1000.0) String.format(Locale.getDefault(), "%.1f", v / 1000.0) else v.roundToInt().toString()
    val unit = if (hi >= 1000.0) "kHz" else "Hz"
    return if (lo >= 1000.0 || hi < 1000.0) "${f(lo)}–${f(hi)} $unit" else "${f(lo)} Hz–${f(hi)} kHz"
}

/** Above this RMS disagreement between positions the result gets a gentle hint. */
private const val HIGH_POSITION_SPREAD_DB = 4.0

private fun fmt1(v: Double) = String.format(Locale.getDefault(), "%.1f", v)
