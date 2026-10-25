package com.andrexthedev.autovybz.ui.premium

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.andrexthedev.autovybz.premium.CryptoCoin
import com.andrexthedev.autovybz.premium.Entitlement
import com.andrexthedev.autovybz.premium.EntitlementPolicy
import com.andrexthedev.autovybz.premium.EntitlementTier
import com.andrexthedev.autovybz.premium.PremiumPlan
import com.andrexthedev.autovybz.ui.about.AutoVybzInfo
import com.andrexthedev.autovybz.ui.components.AvzCard
import com.andrexthedev.autovybz.ui.theme.AutoVybzColors
import com.bearinmind.equalizer314.R
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Compact plan/limit summary used in Studio and About. */
@Composable
fun PremiumStatusCard(entitlement: Entitlement, onOpenPremium: () -> Unit, modifier: Modifier = Modifier) {
    val pro = EntitlementPolicy.isPro(entitlement)
    AvzCard(modifier = modifier, accent = AutoVybzColors.Gold) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Star, contentDescription = null, tint = AutoVybzColors.Gold, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.avz_premium_status_title), style = MaterialTheme.typography.titleMedium)
        }
        Text(premiumStatusText(entitlement), style = MaterialTheme.typography.bodyMedium, color = AutoVybzColors.OnDarkMuted)
        Button(
            onClick = onOpenPremium,
            modifier = Modifier.fillMaxWidth().testTag("avz:premium:open"),
            colors = ButtonDefaults.buttonColors(containerColor = AutoVybzColors.Gold, contentColor = AutoVybzColors.OnGreen),
        ) {
            Text(stringResource(if (pro) R.string.avz_premium_manage else R.string.avz_premium_open))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PremiumPaywallDialog(
    entitlement: Entitlement,
    onDismiss: () -> Unit,
    onStartTrial: () -> Unit,
    onActivate: (PremiumPlan, CryptoCoin, String) -> Boolean,
    onResetAlpha: () -> Unit,
) {
    val context = LocalContext.current
    var selectedPlan by rememberSaveable { mutableStateOf(PremiumPlan.LIFETIME) }
    var selectedCoin by rememberSaveable { mutableStateOf(CryptoCoin.SOL) }
    var txid by rememberSaveable { mutableStateOf("") }
    var showTxidError by remember { mutableStateOf(false) }
    val txidValid = EntitlementPolicy.isValidTxid(selectedCoin, txid)
    val effectiveTier = EntitlementPolicy.effectiveTier(entitlement)
    val address = cryptoAddress(selectedCoin)
    val coinLabel = stringResource(coinLabelRes(selectedCoin))
    val copiedMsg = stringResource(R.string.avz_copied, coinLabel)
    val activatedMsg = stringResource(R.string.avz_premium_activated)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.94f).padding(12.dp).testTag("avz:premium"),
            shape = RoundedCornerShape(24.dp),
            color = AutoVybzColors.Background,
            contentColor = AutoVybzColors.OnDark,
        ) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Star, contentDescription = null, tint = AutoVybzColors.Gold, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.avz_premium_open), style = MaterialTheme.typography.headlineSmall)
                }
                Text(stringResource(R.string.avz_premium_subtitle), style = MaterialTheme.typography.bodyMedium, color = AutoVybzColors.OnDarkMuted)
                Text(
                    premiumStatusText(entitlement),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (effectiveTier == EntitlementTier.FREE) AutoVybzColors.Gold else AutoVybzColors.GreenBright,
                    modifier = Modifier.testTag("avz:premium:status"),
                )

                for (benefit in listOf(
                    R.string.avz_premium_benefit_profiles,
                    R.string.avz_premium_benefit_positions,
                    R.string.avz_premium_benefit_tools,
                    R.string.avz_premium_benefit_exports,
                    R.string.avz_premium_benefit_support,
                )) {
                    BenefitRow(stringResource(benefit))
                }

                if (effectiveTier == EntitlementTier.FREE && !entitlement.trialUsed) {
                    Button(
                        onClick = { onStartTrial(); onDismiss() },
                        modifier = Modifier.fillMaxWidth().testTag("avz:premium:trial"),
                        colors = ButtonDefaults.buttonColors(containerColor = AutoVybzColors.Green, contentColor = AutoVybzColors.OnGreen),
                    ) { Text(stringResource(R.string.avz_premium_trial_cta)) }
                }
                Text(stringResource(R.string.avz_premium_trial_note), style = MaterialTheme.typography.bodySmall, color = AutoVybzColors.OnDarkMuted)

                Text(stringResource(R.string.avz_premium_pay_title), style = MaterialTheme.typography.titleMedium)
                for (plan in listOf(PremiumPlan.LIFETIME, PremiumPlan.YEARLY, PremiumPlan.MONTHLY)) {
                    FilterChip(
                        selected = selectedPlan == plan,
                        onClick = { selectedPlan = plan },
                        label = {
                            Text(
                                stringResource(planLabelRes(plan)) + " · " + stringResource(R.string.avz_premium_price, formatUsd(plan.priceUsdCents)) +
                                    if (plan == PremiumPlan.LIFETIME) " · " + stringResource(R.string.avz_premium_best_value) else "",
                            )
                        },
                        modifier = Modifier.fillMaxWidth().testTag("avz:premium:plan:${plan.name}"),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AutoVybzColors.Green,
                            selectedLabelColor = AutoVybzColors.OnGreen,
                            labelColor = AutoVybzColors.OnDark,
                        ),
                    )
                }
                Text(stringResource(R.string.avz_premium_no_autorenew), style = MaterialTheme.typography.bodySmall, color = AutoVybzColors.OnDarkMuted)

                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (coin in CryptoCoin.values()) {
                        FilterChip(
                            selected = selectedCoin == coin,
                            onClick = { selectedCoin = coin; showTxidError = false },
                            label = { Text(stringResource(coinLabelRes(coin))) },
                            modifier = Modifier.testTag("avz:premium:coin:${coin.name}"),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AutoVybzColors.Gold,
                                selectedLabelColor = AutoVybzColors.OnGreen,
                                labelColor = AutoVybzColors.OnDark,
                            ),
                        )
                    }
                }
                Text(stringResource(R.string.avz_premium_pay_instructions), style = MaterialTheme.typography.bodyMedium)
                AddressBox(label = coinLabel, address = address) { copyToClipboard(context, coinLabel, address, copiedMsg) }
                Text(stringResource(R.string.avz_premium_sol_tip), style = MaterialTheme.typography.bodySmall, color = AutoVybzColors.OnDarkMuted)
                Text(stringResource(R.string.avz_donate_eth_note), style = MaterialTheme.typography.bodySmall, color = AutoVybzColors.Gold)

                OutlinedTextField(
                    value = txid,
                    onValueChange = { txid = it.trim(); showTxidError = false },
                    label = { Text(stringResource(R.string.avz_premium_txid_label)) },
                    isError = showTxidError && !txidValid,
                    supportingText = if (showTxidError && !txidValid) ({ Text(stringResource(R.string.avz_premium_txid_invalid)) }) else null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth().testTag("avz:premium:txid"),
                )
                Button(
                    onClick = {
                        showTxidError = !txidValid
                        if (txidValid && onActivate(selectedPlan, selectedCoin, txid)) {
                            Toast.makeText(context, activatedMsg, Toast.LENGTH_SHORT).show()
                            onDismiss()
                        }
                    },
                    enabled = txid.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().testTag("avz:premium:activate"),
                    colors = ButtonDefaults.buttonColors(containerColor = AutoVybzColors.Gold, contentColor = AutoVybzColors.OnGreen),
                ) { Text(stringResource(R.string.avz_premium_activate)) }
                Text(stringResource(R.string.avz_premium_alpha_note), style = MaterialTheme.typography.bodySmall, color = AutoVybzColors.OnDarkMuted)

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onResetAlpha, modifier = Modifier.testTag("avz:premium:reset")) {
                        Text(stringResource(R.string.avz_premium_reset_alpha), color = AutoVybzColors.Error)
                    }
                    TextButton(onClick = onDismiss, modifier = Modifier.testTag("avz:premium:close")) {
                        Text(stringResource(R.string.avz_close), color = AutoVybzColors.Gold)
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
fun premiumStatusText(entitlement: Entitlement): String {
    val expiry = entitlement.expiresAtEpochMs
    val expiryText = remember(expiry) {
        expiry?.let { DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault()).format(Date(it)) } ?: ""
    }
    val limits = EntitlementPolicy.limits(entitlement)
    return when (EntitlementPolicy.effectiveTier(entitlement)) {
        EntitlementTier.FREE -> stringResource(R.string.avz_premium_free_status, limits.maxSavedProfiles, limits.maxPositions)
        EntitlementTier.TRIAL -> stringResource(R.string.avz_premium_trial_status, expiryText)
        EntitlementTier.PRO -> if (entitlement.plan == PremiumPlan.LIFETIME || expiry == null) {
            stringResource(R.string.avz_premium_lifetime_status)
        } else {
            stringResource(R.string.avz_premium_pro_status, expiryText)
        }
    }
}

@Composable
private fun BenefitRow(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Spacer(
            Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(AutoVybzColors.Green),
        )
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun AddressBox(label: String, address: String, onCopy: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(AutoVybzColors.BackgroundDeep).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleSmall, color = AutoVybzColors.Gold, modifier = Modifier.weight(1f))
            OutlinedButton(
                onClick = onCopy,
                border = BorderStroke(1.dp, AutoVybzColors.Green),
                modifier = Modifier.testTag("avz:premium:copy"),
            ) { Text(stringResource(R.string.avz_copy), color = AutoVybzColors.GreenBright) }
        }
        Text(
            address,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium),
            color = AutoVybzColors.OnDark,
            modifier = Modifier.testTag("avz:premium:address"),
        )
    }
}

private fun planLabelRes(plan: PremiumPlan): Int = when (plan) {
    PremiumPlan.MONTHLY -> R.string.avz_premium_plan_monthly
    PremiumPlan.YEARLY -> R.string.avz_premium_plan_yearly
    PremiumPlan.LIFETIME -> R.string.avz_premium_plan_lifetime
}

private fun coinLabelRes(coin: CryptoCoin): Int = when (coin) {
    CryptoCoin.SOL -> R.string.avz_donate_sol
    CryptoCoin.BTC -> R.string.avz_donate_btc
    CryptoCoin.ETH -> R.string.avz_donate_eth
}

private fun cryptoAddress(coin: CryptoCoin): String = when (coin) {
    CryptoCoin.SOL -> AutoVybzInfo.DONATE_SOL
    CryptoCoin.BTC -> AutoVybzInfo.DONATE_BTC
    CryptoCoin.ETH -> AutoVybzInfo.DONATE_ETH
}

private fun formatUsd(cents: Int): String = String.format(Locale.US, "%.2f", cents / 100.0)

private fun copyToClipboard(context: Context, label: String, text: String, toast: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
}
