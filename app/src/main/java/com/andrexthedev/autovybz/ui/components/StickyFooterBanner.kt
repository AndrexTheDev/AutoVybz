package com.andrexthedev.autovybz.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andrexthedev.autovybz.ui.theme.AutoVybzColors
import com.bearinmind.equalizer314.R

/** Layout contract for the permanent footer slot: fixed size, so replacing the placeholder never shifts the UI. */
object StickyFooterBannerDefaults {
    const val TestTag = "avz:sticky-footer"
    val Height = 52.dp
}

/**
 * Permanent sticky footer for the main AutoVybz shell (Path A monetisation slot).
 *
 * For now this deliberately renders only an in-app placeholder: no advertising SDK, no network access and no
 * tracking are introduced. A future banner provider can replace the content of this one component without
 * touching the screens or changing the reserved layout space. Before enabling a real provider, the privacy
 * policy, store data-safety declaration and any required consent flow must be updated with it.
 */
@Composable
fun StickyFooterBanner(modifier: Modifier = Modifier) {
    val label = stringResource(R.string.avz_sticky_footer_placeholder)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(AutoVybzColors.BackgroundDeep)
            .testTag(StickyFooterBannerDefaults.TestTag),
    ) {
        VybzStripe(Modifier.fillMaxWidth().height(2.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(StickyFooterBannerDefaults.Height)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Brush.verticalGradient(listOf(AutoVybzColors.CardTop, AutoVybzColors.CardBottom)))
                    .border(1.dp, AutoVybzColors.Outline.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(AutoVybzColors.Gold),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = AutoVybzColors.OnDark,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
