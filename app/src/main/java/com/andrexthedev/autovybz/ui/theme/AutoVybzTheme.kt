package com.andrexthedev.autovybz.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * AutoVybz "Jamaica" palette - mirrors res/values/autovybz_colors.xml 1:1
 * (the XML is used by the window theme / launcher icon, this object by Compose).
 */
object AutoVybzColors {
    val Background = Color(0xFF121212)
    val BackgroundDeep = Color(0xFF0A0A0A)
    val Surface = Color(0xFF181818)
    val SurfaceHigh = Color(0xFF222222)
    val Outline = Color(0xFF3A3A3A)
    val Green = Color(0xFF009B3A)
    val GreenBright = Color(0xFF2BC266)
    val GreenContainer = Color(0xFF0B3A1F)
    val Gold = Color(0xFFFED100)
    val GoldContainer = Color(0xFF3D3300)
    val OnDark = Color(0xFFEDEDED)
    val OnDarkMuted = Color(0xFFA8A8A8)
    val Error = Color(0xFFFF6B6B)

    /** Chart colours. */
    val CurveMeasured = Color(0xFF8A8A8A)
    val CurveTarget = Gold
    val CurveCorrected = GreenBright
    val Grid = Color(0xFF2A2A2A)
}

private val AutoVybzColorScheme = darkColorScheme(
    primary = AutoVybzColors.Green,
    onPrimary = Color.White,
    primaryContainer = AutoVybzColors.GreenContainer,
    onPrimaryContainer = AutoVybzColors.GreenBright,
    secondary = AutoVybzColors.Gold,
    onSecondary = Color.Black,
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
    outlineVariant = Color(0xFF2C2C2C),
    error = AutoVybzColors.Error,
    onError = Color.Black,
    inverseSurface = AutoVybzColors.OnDark,
    inverseOnSurface = AutoVybzColors.Background,
    scrim = Color.Black,
)

private val AutoVybzTypography = Typography(
    displaySmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 38.sp, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 32.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.1.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.2.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
)

private val AutoVybzShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
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
