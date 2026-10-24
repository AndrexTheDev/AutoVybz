package com.andrexthedev.autovybz.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.outlined.Info
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andrexthedev.autovybz.ui.theme.AutoVybzColors
import com.bearinmind.equalizer314.R
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln

/* ------------------------------------------------------------- header -- */

@Composable
fun AutoVybzHeader(
    subtitle: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    /** Optional action on the right (e.g. the info button). */
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(AutoVybzColors.BackgroundDeep, AutoVybzColors.Background)))
            // edge-to-edge: the gradient runs under the status bar, the content stays below it / beside a cutout
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(start = 16.dp, end = 8.dp, top = if (compact) 4.dp else 10.dp, bottom = if (compact) 4.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.drawable.autovybz_logo),
            contentDescription = null,
            modifier = Modifier.size(if (compact) 34.dp else 46.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            VybzWordmark(if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall)
            if (!compact) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = AutoVybzColors.OnDarkMuted)
            }
        }
        if (action != null) action() else Spacer(Modifier.width(8.dp))
    }
}

/** The word mark: "Auto" light, "Vybz" in neon green with a gold dot - read as one word by TalkBack. */
@Composable
fun VybzWordmark(style: TextStyle, modifier: Modifier = Modifier) {
    Row(modifier.clearAndSetSemantics { contentDescription = "AutoVybz" }, verticalAlignment = Alignment.Bottom) {
        Text("Auto", style = style.copy(fontWeight = FontWeight.Medium), color = AutoVybzColors.OnDark)
        Text("Vybz", style = style.copy(fontWeight = FontWeight.Bold), color = AutoVybzColors.Green)
        Box(
            Modifier.padding(start = 2.dp, bottom = (style.fontSize.value * 0.22f).dp)
                .size((style.fontSize.value * 0.22f).dp).clip(CircleShape).background(AutoVybzColors.Gold),
        )
    }
}

/**
 * Two or three buttons side by side - or stacked full width when they would not fit: large font scales
 * (> 115 %) or narrow screens split words like "Abbrechen" mid-word otherwise (end-user test W10).
 * Children size themselves with `Modifier.slot(weight)`.
 */
class AdaptiveButtonScope internal constructor(private val row: RowScope?) {
    fun Modifier.slot(share: Float = 1f): Modifier =
        if (row != null) with(row) { this@slot.weight(share) } else this.fillMaxWidth()
}

@Composable
fun AdaptiveButtonRow(spacing: Dp = 8.dp, content: @Composable AdaptiveButtonScope.() -> Unit) {
    val stacked = LocalDensity.current.fontScale > 1.15f || LocalConfiguration.current.screenWidthDp < 360
    if (stacked) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing)) { AdaptiveButtonScope(null).content() }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing)) { AdaptiveButtonScope(this).content() }
    }
}

/** Thin neon-green → gold accent bar (the two logo rings). */
@Composable
fun VybzStripe(modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(2.dp))
            .background(Brush.horizontalGradient(listOf(AutoVybzColors.Green, AutoVybzColors.Gold))),
    )
}

/**
 * The brand sign: an outer neon-green ring and an inner gold ring, like the logo. [progress] (0..1) draws the outer
 * ring as a sweep (countdown, measuring); [pulse] (0..1) adds a soft glow ring that breathes outwards.
 */
@Composable
fun VybzRings(
    modifier: Modifier = Modifier,
    progress: Float? = null,
    pulse: Float = 0f,
    outer: Color = AutoVybzColors.Green,
    inner: Color = AutoVybzColors.Gold,
    strokeWidth: Dp = 6.dp,
) {
    Canvas(modifier) {
        val stroke = strokeWidth.toPx()
        val radius = size.minDimension / 2f - stroke * 1.5f
        val center = Offset(size.width / 2f, size.height / 2f)
        if (pulse > 0f) {
            drawCircle(
                outer.copy(alpha = 0.28f * (1f - pulse)), radius = radius + stroke * (1f + 4f * pulse), center = center,
                style = Stroke(width = stroke * (1f - 0.5f * pulse)),
            )
        }
        val outerRadius = radius
        val innerRadius = radius - stroke * 2.2f
        if (progress == null) {
            drawCircle(outer, radius = outerRadius, center = center, style = Stroke(width = stroke))
        } else {
            drawCircle(outer.copy(alpha = 0.18f), radius = outerRadius, center = center, style = Stroke(width = stroke))
            drawArc(
                outer, startAngle = -90f, sweepAngle = 360f * progress.coerceIn(0f, 1f), useCenter = false,
                topLeft = Offset(center.x - outerRadius, center.y - outerRadius),
                size = androidx.compose.ui.geometry.Size(outerRadius * 2f, outerRadius * 2f),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        drawCircle(inner, radius = innerRadius, center = center, style = Stroke(width = stroke * 0.8f))
    }
}

/** Infinite 0..1 breathing value for [VybzRings] - static (0) when the system has animations switched off. */
@Composable
fun rememberPulse(periodMs: Int = 2_400): Float {
    if (!animationsEnabled()) return 0f
    val transition = rememberInfiniteTransition(label = "pulse")
    val v by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(periodMs, easing = LinearEasing), RepeatMode.Restart),
        label = "pulse",
    )
    return v
}

/** False when the user (or a test device) removed animations - decorative motion then stays still. */
@Composable
fun animationsEnabled(): Boolean = remember { android.animation.ValueAnimator.areAnimatorsEnabled() }

/**
 * One-line explanation with an info icon - the app explains itself where a decision is made instead of hiding
 * it in the help. Muted, never louder than the control it belongs to.
 */
@Composable
fun HintRow(text: String, modifier: Modifier = Modifier, icon: ImageVector = Icons.Outlined.Info) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = AutoVybzColors.OnDarkMuted, modifier = Modifier.padding(top = 1.dp).size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = AutoVybzColors.OnDarkMuted)
    }
}

/* -------------------------------------------------------------- cards -- */

@Composable
fun AvzCard(modifier: Modifier = Modifier, accent: Color? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent, contentColor = AutoVybzColors.OnDark),
        border = BorderStroke(1.dp, accent ?: AutoVybzColors.Outline.copy(alpha = 0.35f)),
    ) {
        Column(
            Modifier.fillMaxWidth()
                .background(Brush.verticalGradient(listOf(AutoVybzColors.CardTop, AutoVybzColors.CardBottom)))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp), content = content,
        )
    }
}

/** Collapsible section (Pro area / help). */
@Composable
fun CollapsibleCard(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    val state = stringResource(if (expanded) R.string.avz_a11y_expanded else R.string.avz_a11y_collapsed)
    Card(
        modifier = modifier.fillMaxWidth().animateContentSize(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = AutoVybzColors.Surface, contentColor = AutoVybzColors.OnDark),
        border = BorderStroke(1.dp, if (expanded) AutoVybzColors.Green.copy(alpha = 0.6f) else AutoVybzColors.Outline.copy(alpha = 0.35f)),
    ) {
        Row(
            Modifier.fillMaxWidth()
                .clickable(role = Role.Button) { expanded = !expanded }
                .semantics { stateDescription = state }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = AutoVybzColors.Gold, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
            }
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = AutoVybzColors.OnDarkMuted,
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content,
            )
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = AutoVybzColors.Gold,
        modifier = modifier.padding(top = 8.dp, bottom = 2.dp).semantics { heading() },
    )
}

/* -------------------------------------------------------- level meter -- */

/** Horizontal input level bar: green < -12 dBFS, gold < -3 dBFS, red above. */
@Composable
fun LevelMeter(peak: Float, modifier: Modifier = Modifier) {
    val db = if (peak <= 1e-6f) -90f else 20f * kotlin.math.log10(peak)
    val fraction = ((db + 60f) / 60f).coerceIn(0f, 1f)
    val color = when {
        db > -3f -> AutoVybzColors.Error
        db > -12f -> AutoVybzColors.Gold
        else -> AutoVybzColors.GreenBright
    }
    val description = stringResource(R.string.avz_a11y_level, (fraction * 100).toInt())
    Box(
        modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(AutoVybzColors.SurfaceHigh)
            .semantics {
                contentDescription = description
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
            },
    ) {
        Box(Modifier.fillMaxWidth(fraction).height(10.dp).clip(CircleShape).background(color))
    }
}

/* -------------------------------------------------- frequency response -- */

class ChartSeries(
    val values: DoubleArray,
    val color: Color,
    val width: Float = 2.5f,
    val dashed: Boolean = false,
    val label: String,
)

/**
 * Log-frequency response chart (20 Hz - 20 kHz). All series share [frequencies].
 * Y range adapts to the data in 6 dB steps.
 */
@Composable
fun FrequencyResponseChart(
    frequencies: DoubleArray,
    series: List<ChartSeries>,
    modifier: Modifier = Modifier,
    fMin: Double = 20.0,
    fMax: Double = 20000.0,
    /** Spoken summary for TalkBack (the curves themselves are not accessible). */
    description: String? = null,
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = remember { TextStyle(color = AutoVybzColors.OnDarkMuted, fontSize = 10.sp) }
    val (yMin, yMax) = remember(series) {
        var lo = 0.0
        var hi = 0.0
        for (s in series) for (i in frequencies.indices) {
            if (frequencies[i] < fMin || frequencies[i] > fMax) continue
            val v = s.values[i]
            if (v.isFinite()) { lo = minOf(lo, v); hi = maxOf(hi, v) }
        }
        (floor(lo.coerceAtLeast(-30.0) / 6.0) * 6.0) to (ceil(hi.coerceAtMost(18.0) / 6.0) * 6.0).coerceAtLeast(6.0)
    }
    Column(modifier) {
        val a11y = if (description != null) Modifier.semantics { contentDescription = description } else Modifier
        Canvas(Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(12.dp)).background(AutoVybzColors.BackgroundDeep).then(a11y)) {
            val left = 34.dp.toPx()
            val bottom = size.height - 18.dp.toPx()
            val top = 8.dp.toPx()
            val right = size.width - 8.dp.toPx()
            val lnMin = ln(fMin)
            val lnSpan = ln(fMax) - lnMin
            fun x(f: Double) = left + ((ln(f) - lnMin) / lnSpan * (right - left)).toFloat()
            fun y(db: Double) = bottom - ((db - yMin) / (yMax - yMin) * (bottom - top)).toFloat()

            // grid
            val gridF = doubleArrayOf(20.0, 50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0, 10000.0, 20000.0)
            for (f in gridF) {
                drawLine(AutoVybzColors.Grid, Offset(x(f), top), Offset(x(f), bottom), 1f)
                val label = if (f >= 1000) "${(f / 1000).toInt()}k" else f.toInt().toString()
                val m = measurer.measure(label, labelStyle)
                drawText(m, topLeft = Offset((x(f) - m.size.width / 2f).coerceIn(left, size.width - m.size.width), bottom + 3.dp.toPx()))
            }
            var db = yMin
            while (db <= yMax + 1e-9) {
                val c = if (db == 0.0) AutoVybzColors.Outline else AutoVybzColors.Grid
                drawLine(c, Offset(left, y(db)), Offset(right, y(db)), if (db == 0.0) 1.5f else 1f)
                val m = measurer.measure(db.toInt().toString(), labelStyle)
                drawText(m, topLeft = Offset(left - m.size.width - 4.dp.toPx(), y(db) - m.size.height / 2f))
                db += 6.0
            }
            // curves
            for (s in series) {
                val path = Path()
                var started = false
                for (i in frequencies.indices) {
                    val f = frequencies[i]
                    val v = s.values[i]
                    if (f < fMin || f > fMax || !v.isFinite()) continue
                    val px = x(f)
                    val py = y(v.coerceIn(yMin, yMax))
                    if (!started) { path.moveTo(px, py); started = true } else path.lineTo(px, py)
                }
                drawPath(
                    path, s.color,
                    style = Stroke(
                        width = s.width.dp.toPx() / 1.5f,
                        pathEffect = if (s.dashed) PathEffect.dashPathEffect(floatArrayOf(12f, 10f)) else null,
                    ),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            for (s in series) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(14.dp).height(3.dp).background(s.color))
                    Spacer(Modifier.width(6.dp))
                    Text(s.label, style = MaterialTheme.typography.bodySmall, color = AutoVybzColors.OnDarkMuted)
                }
            }
        }
    }
}
