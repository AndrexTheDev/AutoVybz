package com.andrexthedev.autovybz.ui.components

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.andrexthedev.autovybz.export.PresetExporters
import com.andrexthedev.autovybz.preset.model.AutoVybzPreset
import com.bearinmind.equalizer314.R

private enum class ExportFormat(val suffix: String) {
    APO("_ParametricEQ.txt"),
    PEQ_JSON("_peq.json"),
    REW_CSV("_measurement.csv"),
    INTERNAL(AutoVybzPreset.FILE_EXTENSION),
}

private fun render(preset: AutoVybzPreset, format: ExportFormat): String = when (format) {
    ExportFormat.APO -> preset.toApoText()
    ExportFormat.PEQ_JSON -> PresetExporters.toPeqJson(preset.bands, preset.preampDb, preset.name)
    ExportFormat.REW_CSV -> {
        val m = requireNotNull(preset.measurement)
        PresetExporters.toRewCsv(
            m.frequencies, m.rawDb,
            listOf("Name: ${preset.name}", "Mic: ${m.micDescription}", "Input: ${m.inputSource}", "Normalised to 0 dB (300 Hz - 3 kHz)"),
        )
    }
    ExportFormat.INTERNAL -> preset.toInternalJson()
}

/** "Export" button with APO / JSON / REW CSV / AutoVybz options, written via the Storage Access Framework. */
@Composable
fun ExportButton(preset: () -> AutoVybzPreset?, modifier: Modifier = Modifier, fillWidth: Boolean = false) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<String?>(null) }
    val okMsg = stringResource(R.string.avz_export_done)
    val failMsg = stringResource(R.string.avz_export_failed)

    fun write(uri: Uri?) {
        val text = pending
        pending = null
        if (uri == null || text == null) return
        val ok = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) } != null
        }.getOrDefault(false)
        Toast.makeText(context, if (ok) okMsg else failMsg, Toast.LENGTH_SHORT).show()
    }

    val textLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { write(it) }
    val jsonLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { write(it) }
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { write(it) }

    fun start(format: ExportFormat) {
        menu = false
        val p = preset() ?: return
        pending = render(p, format)
        val fileName = p.name + format.suffix
        when (format) {
            ExportFormat.APO -> textLauncher.launch(fileName)
            ExportFormat.PEQ_JSON, ExportFormat.INTERNAL -> jsonLauncher.launch(fileName)
            ExportFormat.REW_CSV -> csvLauncher.launch(fileName)
        }
    }

    Box(modifier) {
        OutlinedButton(onClick = { menu = true }, modifier = if (fillWidth) Modifier.fillMaxWidth() else Modifier) {
            Text(stringResource(R.string.avz_export))
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.avz_export_apo)) }, onClick = { start(ExportFormat.APO) })
            DropdownMenuItem(text = { Text(stringResource(R.string.avz_export_json)) }, onClick = { start(ExportFormat.PEQ_JSON) })
            if (preset()?.measurement != null) {
                DropdownMenuItem(text = { Text(stringResource(R.string.avz_export_rew)) }, onClick = { start(ExportFormat.REW_CSV) })
            }
            DropdownMenuItem(text = { Text(stringResource(R.string.avz_export_internal)) }, onClick = { start(ExportFormat.INTERNAL) })
        }
    }
}
