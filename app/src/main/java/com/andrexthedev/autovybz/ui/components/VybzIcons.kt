package com.andrexthedev.autovybz.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * AutoVybz line icons (24 dp, 2 dp round strokes) - drawn for the brand instead of the stock Material set:
 * the calibrate icon is the logo's reticle, the sound icon its waveform. Tinted like any Icon().
 */
object VybzIcons {
    private fun icon(name: String, vararg paths: String): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        for (d in paths) {
            b.addPath(
                pathData = addPathNodes(d), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }

    private const val RING = "M4 12a8 8 0 1 0 16 0a8 8 0 1 0 -16 0"

    /** Waveform - the "Sound" tab. */
    val Sound: ImageVector by lazy { icon("avz.sound", "M4 10v4", "M8 7v10", "M12 4v16", "M16 8v8", "M20 11v2") }

    /** The logo reticle - "Calibrate". */
    val Calibrate: ImageVector by lazy {
        icon("avz.calibrate", RING, "M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0", "M12 1.5v3", "M12 19.5v3", "M1.5 12h3", "M19.5 12h3")
    }

    /** Mixing-desk faders - "Studio". */
    val Studio: ImageVector by lazy {
        icon("avz.studio", "M6 4v16", "M12 4v16", "M18 4v16", "M3.5 15h5", "M9.5 8h5", "M15.5 12.5h5")
    }

    val Help: ImageVector by lazy {
        icon("avz.help", "M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0", "M9.6 9.4a2.5 2.5 0 1 1 3.6 2.3c-.7.4-1.2.9-1.2 1.7v.4", "M12 17.2v.01")
    }

    val Info: ImageVector by lazy { icon("avz.info", "M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0", "M12 11v6", "M12 7.6v.01") }

    /** Power glyph for the big on/off ring. */
    val Power: ImageVector by lazy { icon("avz.power", "M12 3v8", "M6.7 7.7a7.5 7.5 0 1 0 10.6 0") }

    val Plus: ImageVector by lazy { icon("avz.plus", "M12 5v14", "M5 12h14") }
}
