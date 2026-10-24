package com.andrexthedev.autovybz.ui.theme

/**
 * Raw ARGB values of the AutoVybz "Vybz Slate" palette (pure Kotlin, so the colour-contrast audit can run on the
 * JVM). Every value is taken from the brand material itself: the slate background is the backdrop of the intro
 * video (so the intro dissolves seamlessly into the app), neon green and gold are measured from the logo rings.
 *
 * Mirrored 1:1 in res/values/autovybz_colors.xml - `ColorContrastAuditTest` enforces both the mirror and the
 * WCAG 2.1 contrast ratios of every text/background pair the UI uses. `InheritedPaletteAuditTest` keeps the
 * inherited engine screens on the same ramp ([SLATE_RAMP]).
 */
object AutoVybzPalette {
    /** Intro video backdrop (edge colour of every frame). */
    const val BACKGROUND = 0xFF1E262D
    const val BACKGROUND_DEEP = 0xFF161C21
    const val SURFACE = 0xFF26303A
    const val SURFACE_HIGH = 0xFF313C47
    /** Borders of text fields / outlined controls: >= 3:1 against every background (WCAG 1.4.11). */
    const val OUTLINE = 0xFF7F8C98
    /** Neon green of the outer logo ring. */
    const val GREEN = 0xFF1EED61
    /** Lighter neon for green text and thin chart lines. */
    const val GREEN_BRIGHT = 0xFF5CF28C
    const val GREEN_CONTAINER = 0xFF123A27
    /** Gold of the inner logo ring. */
    const val GOLD = 0xFFFCD207
    const val GOLD_CONTAINER = 0xFF3E3612
    const val ON_DARK = 0xFFEEF2F5
    const val ON_DARK_MUTED = 0xFFA7B2BC
    /** Text/icons on neon green or gold: near-black with a hint of green (12:1). White would fail (1.3:1). */
    const val ON_GREEN = 0xFF08130C
    const val ERROR = 0xFFFF8A8A
    const val CURVE_MEASURED = 0xFF93A0AC
    const val GRID = 0xFF35414C

    /**
     * Slate ramp for the inherited engine screens, darkest to lightest. Each step replaces the neutral grey of the
     * same luminance (see tools/branding/retheme_inherited.py), so all contrast relations of the original layouts
     * are preserved while the screens take on the AutoVybz look.
     */
    val SLATE_RAMP = longArrayOf(
        0xFF0E1216, 0xFF12171C, 0xFF161C21, 0xFF1A2127, 0xFF1E262D, 0xFF222B33, 0xFF26303A, 0xFF2B3540,
        0xFF313C47, 0xFF38444F, 0xFF404D59, 0xFF4B5966, 0xFF586674, 0xFF65737F, 0xFF76838F, 0xFF87939E,
        0xFF97A3AD, 0xFFA7B2BC, 0xFFB6C0C8, 0xFFC5CDD4, 0xFFD3DAE0, 0xFFE1E6EA, 0xFFEEF2F5, 0xFFF7F9FA,
    )
}
