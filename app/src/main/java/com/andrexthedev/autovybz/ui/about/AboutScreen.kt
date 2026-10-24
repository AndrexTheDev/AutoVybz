@file:OptIn(ExperimentalLayoutApi::class)

package com.andrexthedev.autovybz.ui.about

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.FlowRow
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.andrexthedev.autovybz.ui.components.AvzCard
import com.andrexthedev.autovybz.ui.components.CollapsibleCard
import com.andrexthedev.autovybz.ui.components.JamaicaStripe
import com.andrexthedev.autovybz.ui.theme.AutoVybzColors
import com.bearinmind.equalizer314.BuildConfig
import com.bearinmind.equalizer314.MainActivity
import com.bearinmind.equalizer314.R

@Composable
fun AboutScreen() {
    val context = LocalContext.current
    var showDonate by rememberSaveable { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(8.dp))
        Image(
            painterResource(R.drawable.autovybz_logo), contentDescription = null,
            modifier = Modifier.size(120.dp).clip(RoundedCornerShape(28.dp)),
        )
        Row {
            Text("Auto", style = MaterialTheme.typography.headlineMedium)
            Text("Vybz", style = MaterialTheme.typography.headlineMedium, color = AutoVybzColors.Gold)
        }
        Text(
            stringResource(R.string.avz_about_version, BuildConfig.VERSION_NAME),
            color = AutoVybzColors.OnDarkMuted,
            modifier = Modifier.testTag("avz:screen:about"),
        )
        JamaicaStripe(Modifier.width(64.dp).height(4.dp))
        Text(stringResource(R.string.avz_about_text), textAlign = TextAlign.Center)

        Button(
            onClick = { showDonate = true },
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("avz:about:donate"),
            colors = ButtonDefaults.buttonColors(containerColor = AutoVybzColors.Gold, contentColor = androidx.compose.ui.graphics.Color.Black),
        ) {
            Icon(Icons.Filled.Favorite, contentDescription = null)
            Text("  " + stringResource(R.string.avz_about_donate), style = MaterialTheme.typography.titleMedium)
        }

        AvzCard {
            Text(stringResource(R.string.avz_about_dev), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.avz_about_contact), color = AutoVybzColors.OnDarkMuted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { sendMail(context) }, modifier = Modifier.testTag("avz:about:mail")) {
                    Icon(Icons.Filled.Email, contentDescription = null)
                    Text("  " + stringResource(R.string.avz_about_email))
                }
                OutlinedButton(onClick = { openUrl(context, AutoVybzInfo.SOURCE_URL) }) { Text("GitHub") }
            }
        }

        AvzCard {
            Text(stringResource(R.string.avz_about_based_on), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { openUrl(context, AutoVybzInfo.UPSTREAM_URL) }) {
                Text("github.com/bearinmindcat/Equalizer314", color = AutoVybzColors.GreenBright)
            }
        }

        CollapsibleCard(title = stringResource(R.string.avz_about_licenses)) {
            Text(stringResource(R.string.avz_about_licenses_text), style = MaterialTheme.typography.bodySmall)
        }

        AvzCard(modifier = Modifier.clickable { context.startActivity(Intent(context, MainActivity::class.java)) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Home, contentDescription = null, tint = AutoVybzColors.Green)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.avz_about_classic_ui))
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (showDonate) DonateDialog(onDismiss = { showDonate = false })
}

/** Crypto donate modal - tap an address to copy it to the clipboard. */
@Composable
fun DonateDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AutoVybzColors.Surface,
        icon = { Icon(Icons.Filled.Favorite, contentDescription = null, tint = AutoVybzColors.Gold) },
        title = { Text(stringResource(R.string.avz_donate_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.avz_donate_text), style = MaterialTheme.typography.bodyMedium)
                DonateAddress(context, stringResource(R.string.avz_donate_sol), AutoVybzInfo.DONATE_SOL, "SOL")
                DonateAddress(context, stringResource(R.string.avz_donate_btc), AutoVybzInfo.DONATE_BTC, "BTC")
                DonateAddress(context, stringResource(R.string.avz_donate_eth), AutoVybzInfo.DONATE_ETH, "ETH")
                Text(stringResource(R.string.avz_donate_eth_note), style = MaterialTheme.typography.bodySmall, color = AutoVybzColors.OnDarkMuted)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("avz:donate:close")) { Text(stringResource(R.string.avz_close), color = AutoVybzColors.Gold) }
        },
    )
}

@Composable
private fun DonateAddress(context: Context, label: String, address: String, tag: String) {
    val copiedMsg = stringResource(R.string.avz_copied, label)
    Column(
        Modifier.fillMaxWidth().testTag("avz:donate:$tag")
            .clip(RoundedCornerShape(12.dp))
            .background(AutoVybzColors.BackgroundDeep)
            .clickable { copyToClipboard(context, label, address, copiedMsg) }
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleSmall, color = AutoVybzColors.Gold, modifier = Modifier.weight(1f))
            OutlinedButton(
                onClick = { copyToClipboard(context, label, address, copiedMsg) },
                border = BorderStroke(1.dp, AutoVybzColors.Green),
                modifier = Modifier.testTag("avz:donate:copy:$tag"),
            ) { Text(stringResource(R.string.avz_copy), color = AutoVybzColors.GreenBright) }
        }
        Spacer(Modifier.height(6.dp))
        Text(address, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = AutoVybzColors.OnDark)
    }
}

private fun copyToClipboard(context: Context, label: String, text: String, toast: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    // Android 13+ shows its own clipboard confirmation.
    if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
}

/** Feedback mail with the facts a bug report needs (no personal data, nothing sent automatically). */
private fun sendMail(context: Context) {
    val body = "\n\n---\nAutoVybz ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
        "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${AutoVybzInfo.CONTACT_EMAIL}"))
        .putExtra(Intent.EXTRA_SUBJECT, "AutoVybz ${BuildConfig.VERSION_NAME}")
        .putExtra(Intent.EXTRA_TEXT, body)
    // No mail app installed: copy the address instead of silently doing nothing.
    runCatching { context.startActivity(i) }.onFailure {
        copyToClipboard(context, "E-mail", AutoVybzInfo.CONTACT_EMAIL, context.getString(R.string.avz_copied, AutoVybzInfo.CONTACT_EMAIL))
    }
}

private fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.onFailure {
        copyToClipboard(context, "URL", url, context.getString(R.string.avz_copied, url))
    }
}
