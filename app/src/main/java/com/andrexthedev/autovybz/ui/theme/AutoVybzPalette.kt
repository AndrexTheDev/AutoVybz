package com.andrexthedev.autovybz.ui.theme

/**
 * Raw ARGB values of the AutoVybz "Jamaica" palette (pure Kotlin, so the colour-contrast audit can run
 * on the JVM). Mirrored 1:1 in res/values/autovybz_colors.xml - `ColorContrastAuditTest` enforces both
 * the mirror and the WCAG 2.1 contrast ratios of every text/background pair the UI uses.
 */
object AutoVybzPalette {
    const val BACKGROUND = 0xFF121212
    const val BACKGROUND_DEEP = 0xFF0A0A0A
    const val SURFACE = 0xFF181818
    const val SURFACE_HIGH = 0xFF222222
    /** Borders of text fields / outlined controls: >= 3:1 against the background (WCAG 1.4.11). */
    const val OUTLINE = 0xFF737373
    const val GREEN = 0xFF009B3A
    const val GREEN_BRIGHT = 0xFF2BC266
    const val GREEN_CONTAINER = 0xFF0B3A1F
    const val GOLD = 0xFFFED100
    const val GOLD_CONTAINER = 0xFF3D3300
    const val ON_DARK = 0xFFEDEDED
    const val ON_DARK_MUTED = 0xFFA8A8A8
    /** Text/icons on Jamaica green: white only reaches 3.6:1, black 5.4:1 (black/green/gold = the flag). */
    const val ON_GREEN = 0xFF0A0A0A
    const val ERROR = 0xFFFF6B6B
    const val CURVE_MEASURED = 0xFF8A8A8A
    const val GRID = 0xFF2A2A2A
}
