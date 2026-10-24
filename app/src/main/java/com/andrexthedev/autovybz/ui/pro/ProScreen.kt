@file:OptIn(ExperimentalMaterial3Api::class)

package com.andrexthedev.autovybz.ui.pro

import android.content.Context
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
import com.andrexthedev.autovybz.measurement.audio.InputSourceResolver
import com.andrexthedev.autovybz.measurement.mic.MicProfileDatabase
import com.andrexthedev.autovybz.preset.AutoVybzPresetStore
import com.andrexthedev.autovybz.preset.model.AutoVybzPreset
import com.andrexthedev.autovybz.settings.AutoVybzSettings
import com.andrexthedev.autovybz.settings.SmoothingChoice
import com.andrexthedev.autovybz.ui.components.AvzCard
import com.andrexthedev.autovybz.ui.components.CollapsibleCard
import com.andrexthedev.autovybz.ui.components.ExportButton
import com.andrexthedev.autovybz.ui.components.SectionTitle
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
fun ProScreen() {
    val context = LocalContext.current
    val settings = remember { AutoVybzSettings(context) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.avz_pro_title), style = MaterialTheme.typography.headlineSmall)

        SectionTitle(stringResource(R.string.avz_pro_engine_title))
        EngineLink(Icons.Filled.Build, R.string.avz_pro_peq, R.string.avz_pro_peq_desc) { open(context, MainActivity::class.java) }
        EngineLink(Icons.Filled.Star, R.string.avz_pro_mbc, R.string.avz_pro_mbc_desc) { open(context, MbcActivity::class.java) }
        EngineLink(Icons.Filled.ThumbUp, R.string.avz_pro_limiter, R.string.avz_pro_limiter_desc) { open(context, LimiterActivity::class.java) }

        SectionTitle(stringResource(R.string.avz_pro_calibrations))
        CalibrationList()

        CollapsibleCard(title = stringResource(R.string.avz_pro_measure_title), icon = Icons.Filled.Settings) {
            MeasurementSettings(settings)
        }
        CollapsibleCard(title = stringResource(R.string.avz_pro_mic_title), icon = Icons.Filled.List) {
            MicSettings(settings)
        }
        CollapsibleCard(title = stringResource(R.string.avz_pro_custom_title), icon = Icons.Filled.Star) {
            CustomTargetSettings(settings)
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
            Icon(icon, contentDescription = null, tint = AutoVybzColors.Green, modifier = Modifier.size(26.dp))
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

@Composable
private fun CalibrationList() {
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
    val active = AutoVybzPresetStore.lastAppliedName(context)
    for (p in items) {
        AvzCard(accent = if (p.name == active) AutoVybzColors.Green else null) {
            Text(p.name, style = MaterialTheme.typography.titleMedium)
            Text(
                "${df.format(Date(p.createdAtMillis))} · ${p.target.name} · ${p.bands.size} PEQ · " +
                    String.format(Locale.US, "%.1f dB", p.preampDb) +
                    (if (p.measurement?.environment == ListeningEnvironment.OUTDOOR.name) " · " + stringResource(R.string.avz_env_outdoor_short) else ""),
                style = MaterialTheme.typography.bodySmall,
                color = AutoVybzColors.OnDarkMuted,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = {
                    val outdoor = p.measurement?.environment == ListeningEnvironment.OUTDOOR.name
                    AutoVybzPresetStore.apply(context, p, AutoVybzSettings(context).enableLimiterOnApply || outdoor)
                    reload++
                }) { Text(stringResource(R.string.avz_apply)) }
                ExportButton(preset = { p })
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { AutoVybzPresetStore.delete(context, p.name) }
                        reload++
                    }
                }) { Text(stringResource(R.string.avz_delete), color = AutoVybzColors.Error) }
            }
        }
    }
}

/* ------------------------------------------------------------ settings -- */

@Composable
private fun MeasurementSettings(settings: AutoVybzSettings) {
    var bands by remember { mutableIntStateOf(settings.bandCount) }
    var smoothing by remember { mutableStateOf(settings.smoothing) }
    var maxBoost by remember { mutableStateOf(settings.maxBoostDb) }
    var limiter by remember { mutableStateOf(settings.enableLimiterOnApply) }
    var restoreVol by remember { mutableStateOf(settings.restoreVolumeAfter) }

    Text(stringResource(R.string.avz_pro_bands), style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
    SwitchRow(stringResource(R.string.avz_pro_limiter_on_apply), limiter) { limiter = it; settings.enableLimiterOnApply = it }
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
            val options = (listOfNotNull(db?.default) + (db?.profiles ?: emptyList())).distinctBy { it.id }
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
    var points by remember(file) { mutableStateOf(settings.loadCurve(file)?.frequencies?.size) }
    val invalidFmt = stringResource(R.string.avz_pro_file_invalid, "%s")
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()
        val result = runCatching { settings.storeCurve(file, text ?: "") }
        result.onSuccess { points = it.frequencies.size }
            .onFailure { Toast.makeText(context, invalidFmt.replace("%s", it.message ?: "?"), Toast.LENGTH_LONG).show() }
    }
    Text(title, style = MaterialTheme.typography.titleSmall)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            points?.let { stringResource(R.string.avz_pro_file_loaded, it) } ?: stringResource(R.string.avz_pro_file_none),
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
            selectedContainerColor = AutoVybzColors.GreenContainer,
            selectedLabelColor = AutoVybzColors.GreenBright,
            labelColor = AutoVybzColors.OnDarkMuted,
        ),
    )
}

@Composable
private fun SwitchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedThumbColor = AutoVybzColors.Gold, checkedTrackColor = AutoVybzColors.Green),
        )
    }
}

@Composable
private fun avzSliderColors() = SliderDefaults.colors(
    thumbColor = AutoVybzColors.Gold,
    activeTrackColor = AutoVybzColors.Green,
    inactiveTrackColor = AutoVybzColors.SurfaceHigh,
)
