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

    // "Since the beta started" never forgets the mark on its own: a reminder loop would need a service, and a
    // mark that silently disappears is worse than one that is one tap away from being set again.
    var since by remember { mutableStateOf<Long?>(null) }
    var sinceCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        count = withContext(Dispatchers.IO) { FieldLogStore.count(context) }
        val mark = withContext(Dispatchers.IO) { FieldLogStore.betaMarkMillis(context) }
        since = mark
        sinceCount = withContext(Dispatchers.IO) {
            if (mark == null) 0 else FieldLogStore.count(context, mark)
        }
    }

    val okMsg = stringResource(R.string.avz_export_done)
    val failMsg = stringResource(R.string.avz_export_failed)
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val mark = since
        val ok = runCatching {
            val text = if (mark == null) FieldLogStore.text(context) else FieldLogStore.text(context, mark)
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
    if (count > 0) {
        TextButton(
            onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        if (since == null) {
                            FieldLogStore.markBetaStart(context)
                        } else {
                            FieldLogStore.clearBetaMark(context)
                        }
                    }
                    reload++
                }
            },
            modifier = Modifier.testTag("avz:field:mark"),
        ) {
            Text(
                if (since == null) stringResource(R.string.avz_field_mark)
                else stringResource(R.string.avz_field_mark_clear),
                color = AutoVybzColors.GreenBright,
            )
        }
        since?.let {
            Text(
                stringResource(R.string.avz_field_since, sinceCount),
                style = MaterialTheme.typography.bodySmall,
                color = AutoVybzColors.OnDarkMuted,
                modifier = Modifier.testTag("avz:field:since"),
            )
        }
    }
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
