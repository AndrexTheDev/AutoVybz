package com.andrexthedev.autovybz.ui.help

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.andrexthedev.autovybz.ui.components.CollapsibleCard
import com.bearinmind.equalizer314.R

/** Step-by-step placement & usage guide for laypeople. */
@Composable
fun HelpScreen() {
    val sections = listOf(
        Triple(Icons.Filled.CheckCircle, R.string.avz_help_s1_title, R.string.avz_help_s1_body),
        Triple(Icons.Filled.LocationOn, R.string.avz_help_s2_title, R.string.avz_help_s2_body),
        Triple(Icons.Filled.Notifications, R.string.avz_help_s3_title, R.string.avz_help_s3_body),
        Triple(Icons.Filled.Warning, R.string.avz_help_s4_title, R.string.avz_help_s4_body),
        Triple(Icons.Filled.Settings, R.string.avz_help_s5_title, R.string.avz_help_s5_body),
        Triple(Icons.Filled.Phone, R.string.avz_help_s6_title, R.string.avz_help_s6_body),
        Triple(Icons.Filled.Star, R.string.avz_help_out_title, R.string.avz_help_out_body),
        Triple(Icons.Filled.Build, R.string.avz_help_s7_title, R.string.avz_help_s7_body),
        Triple(Icons.Filled.Info, R.string.avz_help_s8_title, R.string.avz_help_s8_body),
    )
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.avz_help_title), style = MaterialTheme.typography.headlineSmall)
        sections.forEachIndexed { i, (icon, title, body) ->
            CollapsibleCard(title = stringResource(title), icon = icon, initiallyExpanded = i < 2) {
                Text(stringResource(body), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
