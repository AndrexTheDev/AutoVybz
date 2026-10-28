@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.andrexthedev.autovybz.ui.pro

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import com.andrexthedev.autovybz.measurement.eq.TargetType
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import android.content.Context
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.andrexthedev.autovybz.measurement.ListeningEnvironment
import com.andrexthedev.autovybz.measurement.SpeakerClass
import com.andrexthedev.autovybz.measurement.audio.InputSourceResolver
import com.andrexthedev.autovybz.measurement.mic.MicProfileDatabase
import com.andrexthedev.autovybz.premium.Entitlement
import com.andrexthedev.autovybz.premium.EntitlementPolicy
import com.andrexthedev.autovybz.preset.AutoVybzPresetStore
import com.andrexthedev.autovybz.preset.PresetDisplayName
import com.andrexthedev.autovybz.preset.model.AutoVybzPreset
import com.andrexthedev.autovybz.settings.AutoVybzSettings
import com.andrexthedev.autovybz.settings.SmoothingChoice
import com.andrexthedev.autovybz.ui.components.AvzCard
import com.andrexthedev.autovybz.ui.components.CollapsibleCard
import com.andrexthedev.autovybz.ui.components.ExportButton
import com.andrexthedev.autovybz.ui.components.SectionTitle
import com.andrexthedev.autovybz.ui.components.HintRow
import com.andrexthedev.autovybz.ui.components.VybzIcons
import com.andrexthedev.autovybz.ui.components.rememberEqPower
import com.andrexthedev.autovybz.ui.premium.PremiumStatusCard
import com.andrexthedev.autovybz.sound.SoundComposer
import com.andrexthedev.autovybz.sound.VybzSound
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import com.andrexthedev.autovybz.ui.theme.AutoVybzColors
import com.bearinmind.equalizer314.LimiterActivity
import com.bearinmind.equalizer314.MainActivity
import com.bearinmind.equalizer314.MbcActivity
import com.bearinmind.equalizer314.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ProScreen(entitlement: Entitlement = Entitlement.free(), onOpenPremium: () -> Unit = {}) {
    val limits = EntitlementPolicy.limits(entitlement)
    val context = LocalContext.current
    val settings = remember { AutoVybzSettings(context) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.avz_pro_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.testTag("avz:screen:pro").semantics { heading() },
        )

        HintRow(stringResource(R.string.avz_pro_intro))

        PremiumStatusCard(entitlement = entitlement, onOpenPremium = onOpenPremium)

        SectionTitle(stringResource(R.string.avz_studio_tone_title))
        ToneCard(settings)

        SectionTitle(stringResource(R.string.avz_pro_calibrations))
        SoundCorrectionToggle()
        CalibrationList(externalExportsEnabled = limits.externalExports, onOpenPremium = onOpenPremium)

        SectionTitle(stringResource(R.string.avz_pro_engine_title))
        HintRow(stringResource(R.string.avz_pro_engine_hint))
        EngineLink(VybzIcons.Studio, R.string.avz_pro_peq, R.string.avz_pro_peq_desc) { open(context, MainActivity::class.java) }
        EngineLink(VybzIcons.Sound, R.string.avz_pro_mbc, R.string.avz_pro_mbc_desc) { open(context, MbcActivity::class.java) }
        EngineLink(VybzIcons.Power, R.string.avz_pro_limiter, R.string.avz_pro_limiter_desc) { open(context, LimiterActivity::class.java) }

        if (limits.advancedMeasurementSettings) {
            CollapsibleCard(title = stringResource(R.string.avz_pro_measure_title), icon = Icons.Filled.Settings) {
                MeasurementSettings(settings)
            }
            CollapsibleCard(title = stringResource(R.string.avz_pro_mic_title), icon = Icons.Filled.List) {
                MicSettings(settings)
            }
            CollapsibleCard(title = stringResource(R.string.avz_pro_custom_title), icon = Icons.Filled.Star) {
                CustomTargetSettings(settings)
            }
        } else {
            AvzCard(accent = AutoVybzColors.Gold, modifier = Modifier.testTag("avz:premium:locked-settings")) {
                Text(stringResource(R.string.avz_pro_measure_title), style = MaterialTheme.typography.titleMedium)
                HintRow(stringResource(R.string.avz_premium_locked_settings))
                Button(onClick = onOpenPremium, modifier = Modifier.fillMaxWidth().testTag("avz:premium:locked-settings:open")) {
                    Text(stringResource(R.string.avz_premium_open))
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun open(context: Context, cls: Class<*>) {
    context.startActivity(Intent(context, cls))
}

@Composable
private fun EngineLink(icon: ImageVector, title: Int, desc: Int, onClick: () -> Unit) {
    AvzCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(AutoVybzColors.GreenContainer),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, contentDescription = null, tint = AutoVybzColors.Green, modifier = Modifier.size(24.dp)) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(desc), style = MaterialTheme.typography.bodySmall, color = AutoVybzColors.OnDarkMuted)
            }
            Icon(Icons.Filled.KeyboardArrowRight, contentDescription = null, tint = AutoVybzColors.OnDarkMuted)
        }
    }
}

/* ---------------------------------------------------------- calibrations -- */

/**
 * Studio "Feinschliff": bass / treble shelves on top of calibration + style, and the volume protection (limiter).
 * Changes are audible when the finger lifts - no apply button.
 */
@Composable
private fun ToneCard(settings: AutoVybzSettings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val initial = remember { VybzSound.selection(context) }
    var bass by remember { mutableFloatStateOf(initial.bassDb.toFloat()) }
    var treble by remember { mutableFloatStateOf(initial.trebleDb.toFloat()) }
    var limiter by remember { mutableStateOf(settings.enableLimiterOnApply) }
    val f = { v: Float -> String.format(Locale.getDefault(), "%+.1f", v) }
    fun push() {
        val b = bass.toDouble()
        val t = treble.toDouble()
        scope.launch(Dispatchers.IO) { VybzSound.setTone(context, b, t) }
    }
    val max = SoundComposer.MAX_TONE_DB.toFloat()
    AvzCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.avz_studio_bass), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.avz_studio_db, f(bass)), style = MaterialTheme.typography.titleSmall, color = AutoVybzColors.Gold)
        }
        Slider(
            bass, { bass = (it * 2).toInt() / 2f }, valueRange = -max..max, onValueChangeFinished = { push() },
            colors = avzBipolarSliderColors(), modifier = Modifier.zeroMark().testTag("avz:studio:bass"),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.avz_studio_treble), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.avz_studio_db, f(treble)), style = MaterialTheme.typography.titleSmall, color = AutoVybzColors.Gold)
        }
        Slider(
            treble, { treble = (it * 2).toInt() / 2f }, valueRange = -max..max, onValueChangeFinished = { push() },
            colors = avzBipolarSliderColors(), modifier = Modifier.zeroMark().testTag("avz:studio:treble"),
        )
        if (bass != 0f || treble != 0f) {
            TextButton(onClick = { bass = 0f; treble = 0f; push() }, modifier = Modifier.testTag("avz:studio:tone_reset")) {
                Text(stringResource(R.string.avz_studio_tone_reset), color = AutoVybzColors.GreenBright)
            }
        }
        HintRow(stringResource(R.string.avz_studio_tone_hint))
        SwitchRow(stringResource(R.string.avz_pro_limiter_on_apply), limiter, Modifier.testTag("avz:studio:protect")) {
            limiter = it
            settings.enableLimiterOnApply = it
            scope.launch(Dispatchers.IO) { VybzSound.reapply(context) }
        }
        HintRow(stringResource(R.string.avz_studio_protect_hint))
    }
}

/**
 * A/B switch for the system-wide EQ: turn it off and on while music plays to hear what the calibration does.
 * Same live state as the big ring on the Sound screen.
 */
@Composable
private fun SoundCorrectionToggle() {
    val power = rememberEqPower()
    val on = power.on
    val name = power.calibration
    val status = when {
        !on -> stringResource(R.string.avz_pro_ab_off)
        name != null -> stringResource(R.string.avz_pro_ab_active, name)
        else -> stringResource(R.string.avz_pro_ab_on)
    }
    AvzCard(accent = if (on) AutoVybzColors.Green.copy(alpha = 0.55f) else null) {
        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(value = on, role = Role.Switch, onValueChange = { power.set(it) })
                .testTag("avz:pro:eq"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.avz_pro_ab_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    status, style = MaterialTheme.typography.bodySmall,
                    color = if (on) AutoVybzColors.GreenBright else AutoVybzColors.OnDarkMuted,
                )
            }
            Switch(checked = on, onCheckedChange = null, colors = avzSwitchColors())
        }
        HintRow(stringResource(R.string.avz_pro_ab_hint))
    }
}

@Composable
private fun CalibrationList(externalExportsEnabled: Boolean = true, onOpenPremium: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var items by remember { mutableStateOf<List<AutoVybzPreset>>(emptyList()) }
    LaunchedEffect(reload) {
        items = withContext(Dispatchers.IO) { AutoVybzPresetStore.listCalibrations(context) }
    }
    if (items.isEmpty()) {
        Text(stringResource(R.string.avz_pro_no_calibrations), color = AutoVybzColors.OnDarkMuted)
        return
    }
    val df = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    val active = VybzSound.activeCalibrationName(context)
    var toDelete by remember { mutableStateOf<String?>(null) }
    for (p in items) {
        // Readable title (the stored identifier is `AutoVybz_<device>_<date>`); the exact date is on the
        // second line and the test tags keep the stored name, so nothing becomes ambiguous.
        val label = PresetDisplayName.of(p.speakerName, p.name)
        AvzCard(accent = if (p.name == active) AutoVybzColors.Green else null, modifier = Modifier.testTag("avz:pro:cal:${p.name}")) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(
                "${df.format(Date(p.createdAtMillis))} · ${stringResource(targetLabel(p.target))} · ${p.bands.size} PEQ · " +
                    String.format(Locale.US, "%+.1f dB", p.preampDb) +
                    (if (p.measurement?.environment == ListeningEnvironment.OUTDOOR.name) " · " + stringResource(R.string.avz_env_outdoor_short) else "") +
                    (if (p.measurement?.speakerClass == SpeakerClass.DEVICE.name) " · " + stringResource(R.string.avz_device_speaker_name) else ""),
                style = MaterialTheme.typography.bodySmall,
                color = AutoVybzColors.OnDarkMuted,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(
                    onClick = {
                        // style + Studio tone stay layered on top; outdoor / rescue / device calibrations always
                        // arm the limiter (PresetSafety, the same rule the wizard uses when saving)
                        Toast.makeText(context, context.getString(R.string.avz_applied_toast, label), Toast.LENGTH_SHORT).show()
                        scope.launch {
                            withContext(Dispatchers.IO) { VybzSound.useProfile(context, p.name) }
                            reload++ // re-read the active marker once the engine has the new profile
                        }
                    },
                    modifier = Modifier.testTag("avz:pro:apply:${p.name}"),
                ) { Text(stringResource(R.string.avz_apply)) }
                ExportButton(
                    preset = { p },
                    openTag = "avz:pro:export:${p.name}",
                    externalExportsEnabled = externalExportsEnabled,
                    onOpenPremium = onOpenPremium,
                )
                TextButton(onClick = { toDelete = p.name }, modifier = Modifier.testTag("avz:pro:delete:${p.name}")) {
                    Text(stringResource(R.string.avz_delete), color = AutoVybzColors.Error)
                }
            }
        }
    }
    toDelete?.let { name ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            containerColor = AutoVybzColors.Surface,
            title = { Text(stringResource(R.string.avz_delete_title)) },
            text = { Text(stringResource(R.string.avz_delete_text, name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        toDelete = null
                        scope.launch {
                            // sound layer, not the store: deleting the profile that is audible right now must
                            // also stop playing its correction (otherwise the EQ keeps the deleted bands).
                            withContext(Dispatchers.IO) { VybzSound.deleteCalibration(context, name) }
                            reload++
                        }
                    },
                    modifier = Modifier.testTag("avz:dialog:confirm"),
                ) { Text(stringResource(R.string.avz_delete), color = AutoVybzColors.Error) }
            },
            dismissButton = {
                TextButton(onClick = { toDelete = null }, modifier = Modifier.testTag("avz:dialog:dismiss")) {
                    Text(stringResource(R.string.avz_cancel), color = AutoVybzColors.GreenBright)
                }
            },
        )
    }
}

private fun targetLabel(t: TargetType): Int = when (t) {
    TargetType.SMART -> R.string.avz_target_smart
    TargetType.FLAT -> R.string.avz_target_flat
    TargetType.BASS_BOOST -> R.string.avz_target_bass
    TargetType.HARMAN -> R.string.avz_target_harman
    TargetType.CUSTOM -> R.string.avz_target_custom
}

/* ------------------------------------------------------------ settings -- */

@Composable
private fun MeasurementSettings(settings: AutoVybzSettings) {
    var bands by remember { mutableIntStateOf(settings.bandCount) }
    var smoothing by remember { mutableStateOf(settings.smoothing) }
    var maxBoost by remember { mutableStateOf(settings.maxBoostDb) }
    var restoreVol by remember { mutableStateOf(settings.restoreVolumeAfter) }

    Text(stringResource(R.string.avz_pro_bands), style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { // wraps instead of splitting words (W10)
        for ((n, label) in listOf(5 to R.string.avz_bands_5, 10 to R.string.avz_bands_10)) {
            AvzChip(bands == n, stringResource(label)) { bands = n; settings.bandCount = n }
        }
    }
    Text(stringResource(R.string.avz_pro_smoothing), style = MaterialTheme.typography.titleSmall)
    Column {
        for ((choice, label) in listOf(
            SmoothingChoice.DYNAMIC to R.string.avz_smoothing_dynamic,
            SmoothingChoice.FINE to R.string.avz_smoothing_fine,
            SmoothingChoice.STRONG to R.string.avz_smoothing_strong,
        )) {
            AvzChip(smoothing == choice, stringResource(label)) { smoothing = choice; settings.smoothing = choice }
        }
    }
    Text(stringResource(R.string.avz_pro_max_boost, String.format(Locale.getDefault(), "%.1f", maxBoost)), style = MaterialTheme.typography.titleSmall)
    Slider(
        value = maxBoost, onValueChange = { maxBoost = (it * 2).toInt() / 2f },
        onValueChangeFinished = { settings.maxBoostDb = maxBoost },
        valueRange = 0f..9f, colors = avzSliderColors(),
    )
    SwitchRow(stringResource(R.string.avz_pro_restore_volume), restoreVol) { restoreVol = it; settings.restoreVolumeAfter = it }
}

@Composable
private fun MicSettings(settings: AutoVybzSettings) {
    val context = LocalContext.current
    val db = remember { runCatching { MicProfileDatabase.get(context) }.getOrNull() }
    val auto = remember { db?.forDevice() }
    var overrideId by remember { mutableStateOf(settings.micProfileOverrideId) }
    var menu by remember { mutableStateOf(false) }
    var useUsb by remember { mutableStateOf(settings.useUsbMic) }
    val usbDevice = remember(useUsb) { InputSourceResolver.findUsbMic(context) }

    Text(stringResource(R.string.avz_pro_mic_profile), style = MaterialTheme.typography.titleSmall)
    Box {
        val currentLabel = overrideId?.let { db?.byId(it)?.label } ?: stringResource(R.string.avz_pro_mic_auto, auto?.label ?: "-")
        OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth()) { Text(currentLabel) }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.avz_pro_mic_auto, auto?.label ?: "-")) },
                onClick = { overrideId = null; settings.micProfileOverrideId = null; menu = false },
            )
            // Auto-detected profile first, then the rest of this device's manufacturer,
            // then everything else (stable sort keeps database order inside each group).
            val options = (listOfNotNull(db?.default) + (db?.profiles ?: emptyList()))
                .distinctBy { it.id }
                .sortedBy { p ->
                    when {
                        p.id == auto?.id -> 0
                        p.manufacturer != null && p.manufacturer == auto?.manufacturer -> 1
                        else -> 2
                    }
                }
            for (p in options) {
                DropdownMenuItem(
                    text = { Text("${p.label} (${p.confidence.name.lowercase()})") },
                    onClick = { overrideId = p.id; settings.micProfileOverrideId = p.id; menu = false },
                )
            }
        }
    }
    CurveFileRow(stringResource(R.string.avz_pro_user_offset), settings, settings.userOffsetFile)

    SwitchRow(stringResource(R.string.avz_pro_mic_usb), useUsb) { useUsb = it; settings.useUsbMic = it }
    Text(stringResource(R.string.avz_pro_mic_usb_desc), style = MaterialTheme.typography.bodySmall, color = AutoVybzColors.OnDarkMuted)
    if (useUsb) {
        Text(
            usbDevice?.let { stringResource(R.string.avz_pro_mic_usb_found, it.productName?.toString() ?: "USB") }
                ?: stringResource(R.string.avz_pro_mic_usb_none),
            color = if (usbDevice != null) AutoVybzColors.GreenBright else AutoVybzColors.Gold,
            style = MaterialTheme.typography.bodySmall,
        )
        CurveFileRow(stringResource(R.string.avz_pro_usb_cal), settings, settings.usbCalibrationFile)
    }
}

/** Load / remove a calibration (UMIK-style "freq dB" text) or manual offset file. */
@Composable
private fun CurveFileRow(title: String, settings: AutoVybzSettings, file: File) {
    val context = LocalContext.current
    var points by remember(file) { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    // Parsing the curve reads (and validates) a file of up to 1 MB - done once, off the main thread.
    LaunchedEffect(file) {
        points = withContext(Dispatchers.IO) { settings.loadCurve(file)?.frequencies?.size }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // Reading (up to 1 MB), parsing and fsyncing the file must not run on the main thread: the old
        // synchronous version blocked the UI - measurably so since the write became atomic (tmp + fsync).
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { settings.storeCurve(file, readSmallText(context, uri) ?: "") }
            }
            result.onSuccess { points = it.frequencies.size }
                .onFailure {
                    Toast.makeText(context, context.getString(R.string.avz_pro_file_invalid, it.message ?: "?"), Toast.LENGTH_LONG).show()
                }
        }
    }
    Text(title, style = MaterialTheme.typography.titleSmall)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            points?.let { LocalContext.current.resources.getQuantityString(R.plurals.avz_pro_file_loaded, it, it) } ?: stringResource(R.string.avz_pro_file_none),
            color = AutoVybzColors.OnDarkMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = { launcher.launch("text/*") }) { Text(stringResource(R.string.avz_pro_file_load)) }
        if (points != null) {
            TextButton(onClick = { settings.clearCurve(file); points = null }) {
                Text(stringResource(R.string.avz_pro_file_clear), color = AutoVybzColors.Error)
            }
        }
    }
}

@Composable
private fun CustomTargetSettings(settings: AutoVybzSettings) {
    var bass by remember { mutableStateOf(settings.customBassDb) }
    var treble by remember { mutableStateOf(settings.customTrebleDb) }
    var tilt by remember { mutableStateOf(settings.customTiltDbPerOct) }
    val f = { v: Float -> String.format(Locale.getDefault(), "%+.1f", v) }
    Text(stringResource(R.string.avz_pro_custom_bass, f(bass)), style = MaterialTheme.typography.titleSmall)
    Slider(bass, { bass = (it * 2).toInt() / 2f }, valueRange = -3f..10f, onValueChangeFinished = { settings.customBassDb = bass }, colors = avzSliderColors())
    Text(stringResource(R.string.avz_pro_custom_treble, f(treble)), style = MaterialTheme.typography.titleSmall)
    Slider(treble, { treble = (it * 2).toInt() / 2f }, valueRange = -6f..6f, onValueChangeFinished = { settings.customTrebleDb = treble }, colors = avzSliderColors())
    Text(stringResource(R.string.avz_pro_custom_tilt, f(tilt)), style = MaterialTheme.typography.titleSmall)
    Slider(tilt, { tilt = (it * 10).toInt() / 10f }, valueRange = -1.5f..0.5f, onValueChangeFinished = { settings.customTiltDbPerOct = tilt }, colors = avzSliderColors())
}

/* --------------------------------------------------------------- shared -- */

@Composable
private fun AvzChip(selected: Boolean, label: String, onClick: () -> Unit) {
    FilterChip(
        selected = selected, onClick = onClick, label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = AutoVybzColors.Green,
            selectedLabelColor = AutoVybzColors.OnGreen,
            labelColor = AutoVybzColors.OnDark,
        ),
    )
}

@Composable
private fun SwitchRow(text: String, checked: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    // The whole row is the switch: one TalkBack stop with its label, and a large touch target.
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null, colors = avzSwitchColors())
    }
}

/** Neon track with a dark thumb (white on neon green would be unreadable), slate when off. */
@Composable
private fun avzSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = AutoVybzColors.OnGreen,
    checkedTrackColor = AutoVybzColors.Green,
    checkedBorderColor = AutoVybzColors.Green,
    uncheckedThumbColor = AutoVybzColors.OnDarkMuted,
    uncheckedTrackColor = AutoVybzColors.SurfaceHigh,
    uncheckedBorderColor = AutoVybzColors.Outline,
)

@Composable
private fun avzSliderColors() = SliderDefaults.colors(
    thumbColor = AutoVybzColors.Gold,
    activeTrackColor = AutoVybzColors.Green,
    inactiveTrackColor = AutoVybzColors.SurfaceHigh,
)

/** ±dB sliders: neutral track (no "fill" from the left) - the gold thumb and the centre mark show the offset from 0. */
@Composable
private fun avzBipolarSliderColors() = SliderDefaults.colors(
    thumbColor = AutoVybzColors.Gold,
    activeTrackColor = AutoVybzColors.SurfaceHigh,
    inactiveTrackColor = AutoVybzColors.SurfaceHigh,
)

/** Thin mark at the slider's centre = 0 dB (the track is symmetric, so the centre is exactly 0). */
private fun Modifier.zeroMark() = drawBehind {
    val w = 2.dp.toPx()
    val h = 16.dp.toPx()
    drawRect(AutoVybzColors.Outline, topLeft = Offset(size.width / 2f - w / 2f, size.height / 2f - h / 2f), size = Size(w, h))
}

/** Calibration files are a few kB; refuse anything above 1 MB (a picked video must not cause an OOM). */
private fun readSmallText(context: android.content.Context, uri: android.net.Uri, maxBytes: Int = 1 shl 20): String? =
    context.contentResolver.openInputStream(uri)?.use { input ->
        val out = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var total = 0
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            total += n
            require(total <= maxBytes) { "file larger than 1 MB" }
            out.write(chunk, 0, n)
        }
        out.toString("UTF-8")
    }
