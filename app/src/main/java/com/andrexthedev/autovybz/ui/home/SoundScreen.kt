@file:OptIn(ExperimentalLayoutApi::class)

package com.andrexthedev.autovybz.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.andrexthedev.autovybz.measurement.dsp.FrequencyGrid
import com.andrexthedev.autovybz.measurement.eq.BiquadMath
import com.andrexthedev.autovybz.preset.AutoVybzPresetStore
import com.andrexthedev.autovybz.preset.model.AutoVybzPreset
import com.andrexthedev.autovybz.sound.SoundComposer
import com.andrexthedev.autovybz.sound.SoundSelection
import com.andrexthedev.autovybz.sound.SoundStyle
import com.andrexthedev.autovybz.sound.StyleGroup
import com.andrexthedev.autovybz.sound.VybzSound
import com.andrexthedev.autovybz.ui.components.AvzCard
import com.andrexthedev.autovybz.ui.components.ChartSeries
import com.andrexthedev.autovybz.ui.components.FrequencyResponseChart
import com.andrexthedev.autovybz.ui.components.HintRow
import com.andrexthedev.autovybz.ui.components.SectionTitle
import com.andrexthedev.autovybz.ui.components.VybzIcons
import com.andrexthedev.autovybz.ui.components.VybzRings
import com.andrexthedev.autovybz.ui.components.rememberEqPower
import com.andrexthedev.autovybz.ui.components.rememberPulse
import com.andrexthedev.autovybz.ui.components.NoPulse
import com.andrexthedev.autovybz.ui.theme.AutoVybzColors
import com.bearinmind.equalizer314.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Sound" - the everyday screen: one big switch, which speaker, which style. Everything else lives in Studio.
 * Every change is audible immediately (no "apply" step), and every section says in one line what it does.
 */
@Composable
fun SoundScreen(onCalibrate: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selection by remember { mutableStateOf(VybzSound.selection(context)) }
    var calibrations by remember { mutableStateOf<List<AutoVybzPreset>?>(null) }
    var activeName by remember { mutableStateOf(VybzSound.activeCalibrationName(context)) }
    LaunchedEffect(Unit) {
        calibrations = withContext(Dispatchers.IO) { AutoVybzPresetStore.listCalibrations(context) }
    }
    val active = calibrations?.firstOrNull { it.name == activeName }

    fun commit(block: () -> Unit) {
        scope.launch(Dispatchers.IO) { block() }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            stringResource(R.string.avz_home_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.testTag("avz:screen:home").semantics { heading() },
        )

        PowerHero(speaker = active?.let { speakerLabel(it) }, style = selection.style)

        if (calibrations?.isEmpty() == true) {
            ThreeSteps(onCalibrate)
        } else if (calibrations != null) {
            SectionTitle(stringResource(R.string.avz_home_speaker_title))
            HintRow(stringResource(R.string.avz_home_speaker_hint))
            SpeakerPicker(
                items = calibrations.orEmpty(),
                activeName = activeName,
                onPick = { p ->
                    activeName = p?.name
                    commit { VybzSound.useProfile(context, p?.name) }
                },
                onCalibrate = onCalibrate,
            )
        }

        SectionTitle(stringResource(R.string.avz_home_style_title))
        HintRow(stringResource(R.string.avz_home_style_hint))
        StylePicker(selection.style) { style ->
            selection = selection.copy(style = style)
            commit { VybzSound.setStyle(context, style, selection.intensity) }
        }

        if (selection.style != SoundStyle.NEUTRAL) {
            IntensityCard(selection.intensity) { value ->
                selection = selection.copy(intensity = value)
                commit { VybzSound.setStyle(context, selection.style, value) }
            }
        }
        CurveCard(active, selection)
        Spacer(Modifier.height(16.dp))
    }
}

/* ------------------------------------------------------------------ power -- */

@Composable
private fun PowerHero(speaker: String?, style: SoundStyle) {
    val power = rememberEqPower()
    val pulse = if (power.on) rememberPulse() else NoPulse
    val stateText = stringResource(if (power.on) R.string.avz_home_power_on else R.string.avz_home_power_off)
    AvzCard(accent = if (power.on) AutoVybzColors.Green.copy(alpha = 0.55f) else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(132.dp)
                    .clip(CircleShape)
                    .toggleable(value = power.on, role = Role.Switch, onValueChange = { power.set(it) })
                    .semantics { stateDescription = stateText }
                    .testTag("avz:home:power"),
                contentAlignment = Alignment.Center,
            ) {
                VybzRings(
                    Modifier.fillMaxSize(),
                    pulse = pulse,
                    outer = if (power.on) AutoVybzColors.Green else AutoVybzColors.Outline,
                    inner = if (power.on) AutoVybzColors.Gold else AutoVybzColors.SurfaceHigh,
                    strokeWidth = 5.dp,
                )
                Icon(
                    VybzIcons.Power, contentDescription = stringResource(R.string.avz_home_power_label),
                    tint = if (power.on) AutoVybzColors.Green else AutoVybzColors.OnDarkMuted, modifier = Modifier.size(40.dp),
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stateText, style = MaterialTheme.typography.titleLarge,
                    color = if (power.on) AutoVybzColors.GreenBright else AutoVybzColors.OnDark,
                )
                Text(
                    speaker?.let { stringResource(R.string.avz_home_speaker_value, it) } ?: stringResource(R.string.avz_home_speaker_none_value),
                    style = MaterialTheme.typography.bodyMedium, color = AutoVybzColors.OnDarkMuted,
                )
                Text(
                    stringResource(R.string.avz_home_style_value, stringResource(styleName(style))),
                    style = MaterialTheme.typography.bodyMedium, color = AutoVybzColors.OnDarkMuted,
                )
            }
        }
        HintRow(stringResource(if (power.on) R.string.avz_home_power_hint_on else R.string.avz_home_power_hint_off))
    }
}

/* ------------------------------------------------------------- first run -- */

@Composable
private fun ThreeSteps(onCalibrate: () -> Unit) {
    AvzCard(accent = AutoVybzColors.Gold.copy(alpha = 0.6f), modifier = Modifier.testTag("avz:home:steps")) {
        Text(stringResource(R.string.avz_home_steps_title), style = MaterialTheme.typography.titleLarge)
        for ((i, step) in listOf(R.string.avz_home_step1, R.string.avz_home_step2, R.string.avz_home_step3).withIndex()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(30.dp).clip(CircleShape).background(if (i == 0) AutoVybzColors.Green else AutoVybzColors.SurfaceHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "${i + 1}", style = MaterialTheme.typography.labelLarge,
                        color = if (i == 0) AutoVybzColors.OnGreen else AutoVybzColors.OnDark,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(stringResource(step), style = MaterialTheme.typography.bodyLarge)
            }
        }
        Button(
            onClick = onCalibrate,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("avz:home:calibrate"),
            colors = ButtonDefaults.buttonColors(containerColor = AutoVybzColors.Green, contentColor = AutoVybzColors.OnGreen),
        ) {
            Icon(VybzIcons.Calibrate, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.avz_home_calibrate_now), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/* --------------------------------------------------------------- speaker -- */

private fun speakerLabel(p: AutoVybzPreset): String = p.speakerName?.takeIf { it.isNotBlank() } ?: p.name

@Composable
private fun SpeakerPicker(
    items: List<AutoVybzPreset>,
    activeName: String?,
    onPick: (AutoVybzPreset?) -> Unit,
    onCalibrate: () -> Unit,
) {
    val labels = items.map { speakerLabel(it) }
    val df = remember { java.text.SimpleDateFormat("dd.MM.", java.util.Locale.getDefault()) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for ((i, p) in items.withIndex()) {
            // two calibrations of the same speaker (e.g. indoor / outdoor) get their date as a tie-breaker
            val label = if (labels.count { it == labels[i] } > 1) "${labels[i]} · ${df.format(java.util.Date(p.createdAtMillis))}" else labels[i]
            VybzChip(p.name == activeName, label, Modifier.testTag("avz:home:speaker:${p.name}")) { onPick(p) }
        }
        VybzChip(activeName == null, stringResource(R.string.avz_home_speaker_none), Modifier.testTag("avz:home:speaker:none")) { onPick(null) }
        FilterChip(
            selected = false, onClick = onCalibrate,
            label = { Text(stringResource(R.string.avz_home_speaker_add)) },
            leadingIcon = { Icon(VybzIcons.Plus, contentDescription = null, modifier = Modifier.size(18.dp)) },
            modifier = Modifier.testTag("avz:home:speaker:add"),
            colors = FilterChipDefaults.filterChipColors(labelColor = AutoVybzColors.Gold, iconColor = AutoVybzColors.Gold),
        )
    }
}

@Composable
private fun VybzChip(selected: Boolean, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FilterChip(
        selected = selected, onClick = onClick, label = { Text(label) }, modifier = modifier,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = AutoVybzColors.Green,
            selectedLabelColor = AutoVybzColors.OnGreen,
            labelColor = AutoVybzColors.OnDark,
        ),
    )
}

/* ----------------------------------------------------------------- style -- */

@Composable
private fun StylePicker(selected: SoundStyle, onPick: (SoundStyle) -> Unit) {
    val columns = if (LocalConfiguration.current.screenWidthDp >= 600) 3 else 2
    for (group in StyleGroup.values()) {
        val styles = SoundStyle.values().filter { it.group == group }
        if (group != StyleGroup.BASE) {
            Text(
                stringResource(groupName(group)), style = MaterialTheme.typography.labelLarge, color = AutoVybzColors.Gold,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        for (row in styles.chunked(columns)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (s in row) StyleTile(s, s == selected, Modifier.weight(1f)) { onPick(s) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun StyleTile(style: SoundStyle, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = MaterialTheme.shapes.medium
    Column(
        modifier
            .heightIn(min = 76.dp)
            .clip(shape)
            .background(
                if (selected) Brush.verticalGradient(listOf(AutoVybzColors.GreenContainer, AutoVybzColors.Surface))
                else Brush.verticalGradient(listOf(AutoVybzColors.CardTop, AutoVybzColors.CardBottom)),
            )
            .border(
                BorderStroke(if (selected) 2.dp else 1.dp, if (selected) AutoVybzColors.Green else AutoVybzColors.Outline.copy(alpha = 0.35f)),
                shape,
            )
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .testTag("avz:style:${style.name}")
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            stringResource(styleName(style)), style = MaterialTheme.typography.titleSmall,
            color = if (selected) AutoVybzColors.GreenBright else AutoVybzColors.OnDark,
        )
        Text(stringResource(styleDescription(style)), style = MaterialTheme.typography.bodySmall, color = AutoVybzColors.OnDarkMuted)
    }
}

@Composable
private fun IntensityCard(intensity: Int, onChange: (Int) -> Unit) {
    var value by remember(intensity) { mutableFloatStateOf(intensity.toFloat()) }
    AvzCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.avz_home_intensity), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(
                stringResource(R.string.avz_home_intensity_value, value.toInt()), style = MaterialTheme.typography.titleMedium,
                color = AutoVybzColors.Gold, textAlign = TextAlign.End,
            )
        }
        Slider(
            value = value, onValueChange = { value = (it / 10f).toInt() * 10f },
            onValueChangeFinished = { onChange(value.toInt()) },
            valueRange = 0f..100f, steps = 9,
            modifier = Modifier.testTag("avz:home:intensity"),
            colors = SliderDefaults.colors(
                thumbColor = AutoVybzColors.Gold, activeTrackColor = AutoVybzColors.Green,
                inactiveTrackColor = AutoVybzColors.SurfaceHigh, activeTickColor = AutoVybzColors.OnGreen,
                inactiveTickColor = AutoVybzColors.Outline,
            ),
        )
        HintRow(stringResource(R.string.avz_home_intensity_hint))
    }
}

/** What the style (and the calibration below it) does to the sound - the professional view in one glance. */
@Composable
private fun CurveCard(calibration: AutoVybzPreset?, selection: SoundSelection) {
    val grid = remember { FrequencyGrid.log(pointsPerOctave = 12) }
    val styleLabel = stringResource(R.string.avz_home_curve_style)
    val totalLabel = stringResource(R.string.avz_home_curve_total)
    val series = remember(calibration, selection) {
        val extra = selection.style.bandsAt(selection.intensity) + SoundComposer.toneBands(selection)
        val g = BiquadMath.Grid(grid)
        buildList {
            if (calibration != null) {
                add(ChartSeries(g.response(calibration.bands + extra), AutoVybzColors.Green, 2.5f, label = totalLabel))
                add(ChartSeries(g.response(extra), AutoVybzColors.Gold, 2f, dashed = true, label = styleLabel))
            } else {
                add(ChartSeries(g.response(extra), AutoVybzColors.Green, 2.5f, label = styleLabel))
            }
        }
    }
    AvzCard {
        Text(stringResource(R.string.avz_home_curve_title), style = MaterialTheme.typography.titleMedium)
        FrequencyResponseChart(
            frequencies = grid, series = series,
            modifier = Modifier.fillMaxWidth().height(150.dp).testTag("avz:home:curve"),
            description = stringResource(R.string.avz_home_curve_a11y, stringResource(styleName(selection.style))),
        )
    }
}

/* ---------------------------------------------------------------- labels -- */

internal fun styleName(s: SoundStyle): Int = when (s) {
    SoundStyle.NEUTRAL -> R.string.avz_style_neutral
    SoundStyle.HIPHOP_RNB -> R.string.avz_style_hiphop_rnb
    SoundStyle.REGGAE_DANCEHALL -> R.string.avz_style_reggae_dancehall
    SoundStyle.AFROBEATS -> R.string.avz_style_afrobeats
    SoundStyle.LATIN -> R.string.avz_style_latin
    SoundStyle.ELECTRONIC -> R.string.avz_style_electronic
    SoundStyle.ROCK -> R.string.avz_style_rock
    SoundStyle.POP -> R.string.avz_style_pop
    SoundStyle.LIVE -> R.string.avz_style_live
    SoundStyle.JAZZ_ACOUSTIC -> R.string.avz_style_jazz_acoustic
    SoundStyle.CLASSICAL -> R.string.avz_style_classical
    SoundStyle.SPEECH -> R.string.avz_style_speech
    SoundStyle.PARTY_OUTDOOR -> R.string.avz_style_party_outdoor
    SoundStyle.NIGHT -> R.string.avz_style_night
}

private fun styleDescription(s: SoundStyle): Int = when (s) {
    SoundStyle.NEUTRAL -> R.string.avz_style_neutral_desc
    SoundStyle.HIPHOP_RNB -> R.string.avz_style_hiphop_rnb_desc
    SoundStyle.REGGAE_DANCEHALL -> R.string.avz_style_reggae_dancehall_desc
    SoundStyle.AFROBEATS -> R.string.avz_style_afrobeats_desc
    SoundStyle.LATIN -> R.string.avz_style_latin_desc
    SoundStyle.ELECTRONIC -> R.string.avz_style_electronic_desc
    SoundStyle.ROCK -> R.string.avz_style_rock_desc
    SoundStyle.POP -> R.string.avz_style_pop_desc
    SoundStyle.LIVE -> R.string.avz_style_live_desc
    SoundStyle.JAZZ_ACOUSTIC -> R.string.avz_style_jazz_acoustic_desc
    SoundStyle.CLASSICAL -> R.string.avz_style_classical_desc
    SoundStyle.SPEECH -> R.string.avz_style_speech_desc
    SoundStyle.PARTY_OUTDOOR -> R.string.avz_style_party_outdoor_desc
    SoundStyle.NIGHT -> R.string.avz_style_night_desc
}

private fun groupName(g: StyleGroup): Int = when (g) {
    StyleGroup.BASE -> R.string.avz_style_group_base
    StyleGroup.BEATS -> R.string.avz_style_group_beats
    StyleGroup.STAGE -> R.string.avz_style_group_stage
    StyleGroup.ACOUSTIC -> R.string.avz_style_group_acoustic
    StyleGroup.SITUATION -> R.string.avz_style_group_situation
}
