package com.andrexthedev.autovybz.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.bearinmind.equalizer314.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * AutoVybz "Vybz Slate" palette - mirrors res/values/autovybz_colors.xml 1:1
 * (the XML is used by the window themes / launcher icon, this object by Compose).
 */
object AutoVybzColors {
    val Background = Color(AutoVybzPalette.BACKGROUND)
    val BackgroundDeep = Color(AutoVybzPalette.BACKGROUND_DEEP)
    val Surface = Color(AutoVybzPalette.SURFACE)
    val SurfaceHigh = Color(AutoVybzPalette.SURFACE_HIGH)
    val Outline = Color(AutoVybzPalette.OUTLINE)
    val Green = Color(AutoVybzPalette.GREEN)
    val GreenBright = Color(AutoVybzPalette.GREEN_BRIGHT)
    val GreenContainer = Color(AutoVybzPalette.GREEN_CONTAINER)
    val Gold = Color(AutoVybzPalette.GOLD)
    val GoldContainer = Color(AutoVybzPalette.GOLD_CONTAINER)
    val OnDark = Color(AutoVybzPalette.ON_DARK)
    val OnDarkMuted = Color(AutoVybzPalette.ON_DARK_MUTED)
    val OnGreen = Color(AutoVybzPalette.ON_GREEN)
    val Error = Color(AutoVybzPalette.ERROR)

    /** Chart colours. */
    val CurveMeasured = Color(AutoVybzPalette.CURVE_MEASURED)
    val CurveTarget = Gold
    val CurveCorrected = GreenBright
    val Grid = Color(AutoVybzPalette.GRID)

    /** Subtle top-lit card fill: the "glow from the ring" look of the brand. */
    val CardTop = Color(0xFF2A3540)
    val CardBottom = Surface
}

private val AutoVybzColorScheme = darkColorScheme(
    primary = AutoVybzColors.Green,
    onPrimary = AutoVybzColors.OnGreen,
    primaryContainer = AutoVybzColors.GreenContainer,
    onPrimaryContainer = AutoVybzColors.GreenBright,
    secondary = AutoVybzColors.Gold,
    onSecondary = AutoVybzColors.OnGreen,
    secondaryContainer = AutoVybzColors.GoldContainer,
    onSecondaryContainer = AutoVybzColors.Gold,
    tertiary = AutoVybzColors.GreenBright,
    onTertiary = Color.Black,
    background = AutoVybzColors.Background,
    onBackground = AutoVybzColors.OnDark,
    surface = AutoVybzColors.Background,
    onSurface = AutoVybzColors.OnDark,
    surfaceVariant = AutoVybzColors.SurfaceHigh,
    onSurfaceVariant = AutoVybzColors.OnDarkMuted,
    surfaceTint = AutoVybzColors.Green,
    outline = AutoVybzColors.Outline,
    outlineVariant = Color(0xFF3A4652),
    error = AutoVybzColors.Error,
    onError = Color.Black,
    inverseSurface = AutoVybzColors.OnDark,
    inverseOnSurface = AutoVybzColors.Background,
    scrim = Color.Black,
)

/** Brand typeface Outfit (SIL OFL 1.1) - geometric and round like the logo rings. */
val Outfit = FontFamily(
    Font(R.font.outfit_regular, FontWeight.Normal),
    Font(R.font.outfit_medium, FontWeight.Medium),
    Font(R.font.outfit_semibold, FontWeight.SemiBold),
    Font(R.font.outfit_bold, FontWeight.Bold),
)

/** Tabular figures: dB / Hz / % readouts keep their width while the value changes. */
private const val TNUM = "tnum"

private fun style(weight: FontWeight, size: Int, line: Int, spacing: Double = 0.0) = TextStyle(
    fontFamily = Outfit, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp,
    letterSpacing = spacing.sp, fontFeatureSettings = TNUM,
)

private val AutoVybzTypography = Typography(
    displayLarge = style(FontWeight.Bold, 52, 56, -1.0),
    displayMedium = style(FontWeight.Bold, 40, 46, -0.8),
    displaySmall = style(FontWeight.Bold, 32, 38, -0.5),
    headlineLarge = style(FontWeight.Bold, 30, 36, -0.4),
    headlineMedium = style(FontWeight.Bold, 26, 32, -0.2),
    headlineSmall = style(FontWeight.SemiBold, 22, 28),
    titleLarge = style(FontWeight.SemiBold, 20, 26),
    titleMedium = style(FontWeight.SemiBold, 16, 22, 0.1),
    titleSmall = style(FontWeight.Medium, 14, 20, 0.1),
    bodyLarge = style(FontWeight.Normal, 16, 24, 0.1),
    bodyMedium = style(FontWeight.Normal, 14, 20, 0.1),
    bodySmall = style(FontWeight.Normal, 12, 16, 0.2),
    labelLarge = style(FontWeight.SemiBold, 15, 20, 0.3),
    labelMedium = style(FontWeight.Medium, 12, 16, 0.4),
    labelSmall = style(FontWeight.Medium, 11, 14, 0.5),
)

private val AutoVybzShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

/** Always dark - AutoVybz has a single, deliberately dark look. */
@Composable
fun AutoVybzTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AutoVybzColorScheme,
        typography = AutoVybzTypography,
        shapes = AutoVybzShapes,
        content = content,
    )
}
