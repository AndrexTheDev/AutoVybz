package com.andrexthedev.autovybz.ui.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.ui.graphics.toArgb
import com.andrexthedev.autovybz.measurement.MeasurementOutcome
import com.andrexthedev.autovybz.measurement.eq.TargetType
import com.andrexthedev.autovybz.preset.PresetDisplayName
import com.andrexthedev.autovybz.ui.theme.AutoVybzColors
import com.bearinmind.equalizer314.R
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10

/**
 * Draws the shareable "result card" (1080 x 1350, 4:5 - fits Instagram/WhatsApp/X feeds without cropping).
 *
 * Content is strictly what was measured: measured / target / corrected curves and the RMS deviation from
 * the target before and after the EQ. No invented scores or superlatives.
 */
object ResultCardRenderer {

    const val WIDTH = 1080
    const val HEIGHT = 1350
    private const val F_MIN = 20.0
    private const val F_MAX = 20_000.0

    private val GREEN = AutoVybzColors.Green.toArgb()
    private val GOLD = AutoVybzColors.Gold.toArgb()
    private val MEASURED = AutoVybzColors.CurveMeasured.toArgb()
    private val TARGET = AutoVybzColors.CurveTarget.toArgb()
    private val CORRECTED = AutoVybzColors.CurveCorrected.toArgb()
    private val ON_DARK = AutoVybzColors.OnDark.toArgb()
    private val MUTED = AutoVybzColors.OnDarkMuted.toArgb()
    private val BEFORE = AutoVybzColors.CurveMeasured.toArgb()
    private val GRID = AutoVybzColors.Grid.toArgb()
    private val BG = AutoVybzColors.Background.toArgb()
    private val DEEP = AutoVybzColors.BackgroundDeep.toArgb()
    private val PANEL = AutoVybzColors.Surface.toArgb()
    private val ZERO_LINE = Color.rgb(0x4C, 0x5A, 0x67)

    // Brand typeface (Outfit); falls back to the system font if the resource can't be loaded.
    @Volatile private var regular: Typeface = Typeface.DEFAULT
    @Volatile private var bold: Typeface = Typeface.DEFAULT_BOLD

    fun render(context: Context, outcome: MeasurementOutcome, presetName: String): Bitmap {
        val bmp = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val res = context.resources
        val e = outcome.autoEq
        val loc = Locale.getDefault()
        regular = runCatching { res.getFont(R.font.outfit_regular) }.getOrDefault(Typeface.DEFAULT)
        bold = runCatching { res.getFont(R.font.outfit_bold) }.getOrDefault(Typeface.DEFAULT_BOLD)

        // Background: Vybz slate with a green aura top-left (like the end of the intro) and a faint gold glow.
        c.drawColor(BG)
        fill(RadialGradient(160f, 120f, 900f, withAlpha(GREEN, 0x66), Color.TRANSPARENT, Shader.TileMode.CLAMP)).let {
            c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), it)
        }
        fill(RadialGradient(WIDTH - 80f, HEIGHT - 260f, 620f, withAlpha(GOLD, 0x26), Color.TRANSPARENT, Shader.TileMode.CLAMP)).let {
            c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), it)
        }

        // Header: logo + wordmark + hashtag pill.
        runCatching { BitmapFactory.decodeResource(res, R.drawable.autovybz_logo) }.getOrNull()?.let { logo ->
            c.drawBitmap(logo, null, RectF(64f, 56f, 64f + 112f, 56f + 112f), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            logo.recycle()
        }
        val brand = text(64f, ON_DARK, bold = true)
        c.drawText("Auto", 200f, 136f, brand)
        val autoW = brand.measureText("Auto")
        brand.color = GOLD
        c.drawText("Vybz", 200f + autoW, 136f, brand)

        c.drawText(res.getString(R.string.avz_share_card_free), 202f, 170f, text(24f, GOLD))

        val tag = "#10SecondVybz"
        val tagPaint = text(28f, GOLD, bold = true)
        val tagW = tagPaint.measureText(tag)
        val pill = RectF(WIDTH - 64f - tagW - 40f, 82f, WIDTH - 64f, 142f)
        c.drawRoundRect(pill, 30f, 30f, stroke(GOLD, 3f))
        c.drawText(tag, pill.left + 20f, 122f, tagPaint)

        // Headline (max. 2 lines) + speaker.
        val headlinePaint = text(64f, ON_DARK, bold = true)
        val lines = wrap(res.getString(R.string.avz_share_card_headline), headlinePaint, WIDTH - 128f)
        var y = if (lines.size == 1) 290f else 262f
        for (line in lines) {
            c.drawText(line, 64f, y, headlinePaint)
            y += headlinePaint.textSize * 1.12f
        }
        val speaker = outcome.request.speakerName?.trim().orEmpty().ifEmpty { PresetDisplayName.compact(presetName) }
        val speakerPaint = text(34f, MUTED)
        c.drawText(ellipsize(speaker, speakerPaint, WIDTH - 128f), 64f, if (lines.size == 1) 356f else 408f, speakerPaint)

        // Chart panel.
        val panel = RectF(48f, 440f, WIDTH - 48f, 1000f)
        c.drawRoundRect(panel, 36f, 36f, fill(PANEL))
        c.drawRoundRect(panel, 36f, 36f, stroke(GRID, 2f))
        val plot = RectF(124f, 476f, WIDTH - 88f, 920f)
        drawChart(c, plot, e.frequencies, e.measuredDb, e.targetDb, e.predictedDb)

        // Legend.
        var lx = 88f
        for ((color, label, dashed) in listOf(
            Triple(MEASURED, res.getString(R.string.avz_legend_measured), false),
            Triple(TARGET, res.getString(R.string.avz_legend_target), true),
            Triple(CORRECTED, res.getString(R.string.avz_legend_corrected), false),
        )) {
            val p = stroke(color, 7f).apply { if (dashed) pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f) }
            c.drawLine(lx, 1050f, lx + 48f, 1050f, p)
            val tp = text(30f, ON_DARK)
            c.drawText(label, lx + 62f, 1061f, tp)
            lx += 62f + tp.measureText(label) + 48f
        }

        // Key figure: deviation from target before -> after.
        val before = "±" + String.format(loc, "%.1f", e.rmsErrorBeforeDb)
        val after = "±" + String.format(loc, "%.1f", e.rmsErrorAfterDb) + " dB"
        val big = text(72f, BEFORE, bold = true)
        var x = 64f
        c.drawText(before, x, 1160f, big); x += big.measureText(before)
        big.color = ON_DARK
        c.drawText("  →  ", x, 1160f, big); x += big.measureText("  →  ")
        big.color = CORRECTED
        c.drawText(after, x, 1160f, big)
        c.drawText(res.getString(R.string.avz_share_card_deviation), 64f, 1206f, text(30f, MUTED))

        val details = buildList {
            add(res.getString(targetName(outcome.request.target)))
            add(res.getQuantityString(R.plurals.avz_share_card_filters, e.bands.size, e.bands.size, String.format(loc, "%+.1f", e.preampDb)))
            if (outcome.positions > 1) add(res.getString(R.string.avz_share_card_positions, outcome.positions))
        }.joinToString("  ·  ")
        c.drawText(ellipsize(details, text(28f, MUTED), WIDTH - 128f), 64f, 1248f, text(28f, MUTED))

        // Footer: brand stripes + URL.
        c.drawRect(0f, 1276f, WIDTH.toFloat(), HEIGHT.toFloat(), fill(DEEP))
        c.drawRect(0f, 1276f, WIDTH.toFloat(), 1282f, fill(GOLD))
        c.drawRect(0f, 1282f, WIDTH.toFloat(), 1288f, fill(GREEN))
        val urlPaint = text(34f, ON_DARK, bold = true)
        c.drawText(ResultSharer.SITE_LABEL, (WIDTH - urlPaint.measureText(ResultSharer.SITE_LABEL)) / 2f, 1332f, urlPaint)
        return bmp
    }

    private fun drawChart(c: Canvas, plot: RectF, f: DoubleArray, measured: DoubleArray, target: DoubleArray, corrected: DoubleArray) {
        val idx = f.indices.filter { f[it] in F_MIN..F_MAX }
        if (idx.isEmpty()) return
        val all = idx.flatMap { listOf(measured[it], target[it], corrected[it]) }
        val lo = (floor((all.min() - 2.0) / 6.0) * 6.0).coerceIn(-30.0, -12.0)
        val hi = (ceil((all.max() + 2.0) / 6.0) * 6.0).coerceIn(12.0, 24.0)

        fun x(hz: Double) = plot.left + ((log10(hz) - log10(F_MIN)) / (log10(F_MAX) - log10(F_MIN))).toFloat() * plot.width()
        fun y(db: Double) = plot.bottom - ((db.coerceIn(lo, hi) - lo) / (hi - lo)).toFloat() * plot.height()

        val grid = stroke(GRID, 2f)
        val label = text(24f, MUTED)
        var db = lo
        while (db <= hi + 1e-6) {
            c.drawLine(plot.left, y(db), plot.right, y(db), if (db == 0.0) stroke(ZERO_LINE, 2f) else grid)
            val s = if (db > 0) "+${db.toInt()}" else "${db.toInt()}"
            c.drawText(s, plot.left - 16f - label.measureText(s), y(db) + 8f, label)
            db += 6.0
        }
        for (hz in listOf(20.0, 50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0, 10_000.0, 20_000.0)) {
            c.drawLine(x(hz), plot.top, x(hz), plot.bottom, grid)
        }
        for ((hz, s) in listOf(20.0 to "20", 100.0 to "100", 1000.0 to "1k", 10_000.0 to "10k", 20_000.0 to "20k")) {
            val w = label.measureText(s)
            val lx = (x(hz) - w / 2f).coerceIn(plot.left - 8f, plot.right - w + 8f)
            c.drawText(s, lx, plot.bottom + 44f, label)
        }
        c.drawText("Hz", plot.right - label.measureText("Hz"), plot.bottom + 72f, label)

        fun path(values: DoubleArray) = Path().apply {
            idx.forEachIndexed { i, k -> if (i == 0) moveTo(x(f[k]), y(values[k])) else lineTo(x(f[k]), y(values[k])) }
        }
        c.save()
        c.clipRect(plot.left, plot.top - 6f, plot.right, plot.bottom + 6f)
        // Soft glow under the corrected curve.
        val area = path(corrected).apply { lineTo(plot.right, plot.bottom); lineTo(plot.left, plot.bottom); close() }
        c.drawPath(area, fill(LinearGradient(0f, plot.top, 0f, plot.bottom, withAlpha(CORRECTED, 0x40), Color.TRANSPARENT, Shader.TileMode.CLAMP)))
        c.drawPath(path(target), stroke(TARGET, 4f).apply { pathEffect = DashPathEffect(floatArrayOf(18f, 14f), 0f) })
        c.drawPath(path(measured), stroke(MEASURED, 6f))
        c.drawPath(path(corrected), stroke(CORRECTED, 7f))
        c.restore()
    }

    private fun targetName(t: TargetType) = when (t) {
        TargetType.SMART -> R.string.avz_target_smart
        TargetType.FLAT -> R.string.avz_target_flat
        TargetType.BASS_BOOST -> R.string.avz_target_bass
        TargetType.HARMAN -> R.string.avz_target_harman
        TargetType.CUSTOM -> R.string.avz_target_custom
    }

    private fun text(size: Float, color: Int, bold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = if (bold) this@ResultCardRenderer.bold else regular
    }

    private fun stroke(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        this.color = color
    }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private fun fill(shader: Shader) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader }
    private fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)

    /**
     * Fits [s] into one line, or two balanced lines (no orphan words), shrinking [p] if needed.
     */
    private fun wrap(s: String, p: Paint, maxWidth: Float): List<String> {
        val words = s.split(' ').filter { it.isNotEmpty() }
        while (true) {
            if (p.measureText(s) <= maxWidth || words.size < 2) return listOf(s)
            var best: List<String>? = null
            var bestWidth = Float.MAX_VALUE
            for (k in 1 until words.size) {
                val a = words.subList(0, k).joinToString(" ")
                val b = words.subList(k, words.size).joinToString(" ")
                val w = maxOf(p.measureText(a), p.measureText(b))
                if (w < bestWidth) { bestWidth = w; best = listOf(a, b) }
            }
            // best is always set: words.size >= 2 was checked above, so `1 until words.size` is not empty.
            if (bestWidth <= maxWidth || p.textSize <= 36f) return best ?: listOf(s)
            p.textSize -= 2f
        }
    }

    private fun ellipsize(s: String, p: Paint, maxWidth: Float): String {
        if (p.measureText(s) <= maxWidth) return s
        var end = s.length
        while (end > 1 && p.measureText(s.substring(0, end) + "…") > maxWidth) end--
        return s.substring(0, end) + "…"
    }
}
