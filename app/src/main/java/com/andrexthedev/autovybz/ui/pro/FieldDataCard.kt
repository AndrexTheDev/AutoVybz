@file:OptIn(ExperimentalLayoutApi::class)

package com.andrexthedev.autovybz.ui.pro

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.andrexthedev.autovybz.measurement.FieldLogStore
import com.andrexthedev.autovybz.settings.AutoVybzSettings
import com.andrexthedev.autovybz.ui.components.HintRow
import com.andrexthedev.autovybz.ui.theme.AutoVybzColors
import com.bearinmind.equalizer314.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Studio → field data: the switch, the count, and the two buttons that matter.
 *
 * The app asks four questions about real rooms and real speakers that no model can answer
 * (docs/FIELD_DATA.md). Answering them needs a record per calibration - which this screen is honest
 * about: it shows how many are stored *before* offering the export, states that nothing leaves the
 * device by itself, and can delete the file in one tap.
 *
 * The count is the argument. A user who sees "0 measurements" knows the switch is off; a user who sees
 * "12 measurements" knows the file is real and worth exporting if they want to help.
 */
@Composable
fun FieldDataCard() {
    val context = LocalContext.current
    val settings = remember { AutoVybzSettings(context) }
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(settings.fieldLog) }
    var count by remember { mutableIntStateOf(0) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(reload) {
        count = withContext(Dispatchers.IO) { FieldLogStore.count(context) }
    }

    val okMsg = stringResource(R.string.avz_export_done)
    val failMsg = stringResource(R.string.avz_export_failed)
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val ok = runCatching {
            val text = FieldLogStore.text(context)
            context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) } != null
        }.getOrDefault(false)
        Toast.makeText(context, if (ok) okMsg else failMsg, Toast.LENGTH_SHORT).show()
    }

    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = enabled, role = Role.Switch, onValueChange = {
                enabled = it
                settings.fieldLog = it
            })
            .testTag("avz:field:toggle"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.avz_field_toggle),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = enabled, onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = AutoVybzColors.OnGreen,
                checkedTrackColor = AutoVybzColors.Green,
                uncheckedThumbColor = AutoVybzColors.OnDarkMuted,
                uncheckedTrackColor = AutoVybzColors.SurfaceHigh,
            ),
        )
    }
    Text(
        stringResource(R.string.avz_field_count, count),
        style = MaterialTheme.typography.bodySmall,
        color = if (count > 0) AutoVybzColors.OnDark else AutoVybzColors.OnDarkMuted,
        modifier = Modifier.padding(top = 2.dp).testTag("avz:field:count"),
    )
    HintRow(stringResource(R.string.avz_field_hint))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { launcher.launch("autovybz_fielddata.jsonl") },
            enabled = count > 0,
            modifier = Modifier.testTag("avz:field:export"),
        ) { Text(stringResource(R.string.avz_field_export)) }
        TextButton(
            onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) { FieldLogStore.clear(context) }
                    reload++
                }
            },
            enabled = count > 0,
            modifier = Modifier.testTag("avz:field:delete"),
        ) { Text(stringResource(R.string.avz_field_delete), color = AutoVybzColors.Error) }
    }
}
