package com.andrexthedev.autovybz.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.andrexthedev.autovybz.measurement.Verification
import com.andrexthedev.autovybz.ui.theme.AutoVybzColors
import com.bearinmind.equalizer314.R

/**
 * The wording of a verdict, in one place.
 *
 * Three surfaces have to agree on what a verdict is called - the wizard's check card, the calibration lists
 * (Sound, Pro) and the share card - so the mapping lives here instead of three `when` blocks that drift.
 */
fun verificationVerdictLabel(verdict: Verification.Verdict): Int = when (verdict) {
    Verification.Verdict.AS_PREDICTED -> R.string.avz_check_as_predicted
    Verification.Verdict.BETTER_THAN_PREDICTED -> R.string.avz_check_better
    Verification.Verdict.WEAKER_THAN_PREDICTED -> R.string.avz_check_weaker
    Verification.Verdict.NO_CHANGE -> R.string.avz_check_no_change
    Verification.Verdict.UNEXPECTED -> R.string.avz_check_unexpected
}

/**
 * "Checked: 1.8 dB measured, 2.1 dB predicted" - one line, with the tick (or the warning) in front.
 *
 * Used wherever a *stored* calibration is listed. A check that did not confirm the plan is shown in the
 * warning colour and with the warning icon: a calibration that was verified and failed to do what it promised
 * must not look like one that was simply verified.
 *
 * A `null` [report] renders nothing at all - "not checked" is the absence of a badge, not a third state to
 * explain.
 */
@Composable
fun VerificationBadge(
    report: Verification.Report?,
    modifier: Modifier = Modifier,
    /** Chip mode: only the icon + `contentDescription`, for the speaker chips on the Sound screen. */
    iconOnly: Boolean = false,
) {
    if (report == null) return
    val confirmed = report.isConfirmation
    val tint = if (confirmed) AutoVybzColors.GreenBright else AutoVybzColors.Error
    val label = if (confirmed) R.string.avz_check_badge else R.string.avz_check_badge_failed
    val numbers = stringResource(
        label,
        "%.1f".format(java.util.Locale.getDefault(), report.measuredImprovementDb),
        "%.1f".format(java.util.Locale.getDefault(), report.predictedImprovementDb),
    )
    val icon = if (confirmed) Icons.Filled.CheckCircle else Icons.Filled.Warning
    if (iconOnly) {
        // The chip already carries the speaker name; the badge is the icon, and the sentence becomes its
        // content description so a screen reader still gets the whole verdict.
        Icon(icon, contentDescription = numbers, tint = tint, modifier = modifier.size(16.dp))
        return
    }
    Row(modifier.testTag("avz:check:badge"), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(numbers, style = MaterialTheme.typography.bodySmall, color = tint)
    }
}
