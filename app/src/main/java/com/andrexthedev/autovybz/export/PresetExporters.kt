package com.andrexthedev.autovybz.export

import com.andrexthedev.autovybz.measurement.eq.PeqBand
import com.andrexthedev.autovybz.measurement.eq.PeqType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Text exports. Everything here is pure Kotlin / pure string building so it can
 * be unit-tested on the JVM.
 */
object PresetExporters {

    /**
     * Equalizer APO / Peace / AutoEq "ParametricEQ.txt" syntax:
     * ```
     * Preamp: -4.3 dB
     * Filter 1: ON PK Fc 214 Hz Gain -3.5 dB Q 1.19
     * ```
     * Equalizer314 / AutoVybz can import this back (ApoConverter).
     */
    fun toApoText(bands: List<PeqBand>, preampDb: Double, comment: String? = null): String = buildString {
        comment?.lineSequence()?.forEach { appendLine("# $it") }
        appendLine("Preamp: ${fmt1(preampDb)} dB")
        bands.forEachIndexed { i, b ->
            appendLine("Filter ${i + 1}: ON ${b.type.apoCode} Fc ${fmtFreq(b.frequency)} Hz Gain ${fmt1(b.gainDb)} dB Q ${fmt2(b.q)}")
        }
    }

    /**
     * Generic parametric EQ JSON (one object per filter, AutoEq naming):
     * `{"preamp": -4.3, "filters": [{"type": "PK", "fc": 214, "gain": -3.5, "q": 1.19}]}`
     */
    fun toPeqJson(bands: List<PeqBand>, preampDb: Double, name: String): String = buildString {
        append("{\n")
        append("  \"name\": ").append(jsonString(name)).append(",\n")
        append("  \"generator\": \"AutoVybz\",\n")
        append("  \"preamp\": ").append(fmt1(preampDb)).append(",\n")
        append("  \"filters\": [")
        bands.forEachIndexed { i, b ->
            append(if (i == 0) "\n" else ",\n")
            append("    {\"type\": \"").append(b.type.apoCode).append("\", \"fc\": ").append(fmtFreq(b.frequency))
            append(", \"gain\": ").append(fmt1(b.gainDb)).append(", \"q\": ").append(fmt2(b.q)).append("}")
        }
        append(if (bands.isEmpty()) "]\n" else "\n  ]\n")
        append("}\n")
    }

    /**
     * Frequency response in a format REW's "Import frequency response" accepts:
     * comment lines start with `*`, then `frequency,SPL` pairs (no phase column).
     */
    fun toRewCsv(frequencies: DoubleArray, levelsDb: DoubleArray, header: List<String> = emptyList()): String {
        require(frequencies.size == levelsDb.size)
        return buildString {
            appendLine("* Measurement data exported by AutoVybz")
            header.forEach { appendLine("* $it") }
            appendLine("* Freq(Hz),SPL(dB)")
            for (i in frequencies.indices) {
                append(String.format(Locale.US, "%.3f,%.3f", frequencies[i], levelsDb[i])).append('\n')
            }
        }
    }

    /** Parse "Filter n: ON PK Fc 100 Hz Gain -3 dB Q 1.4" lines (+ Preamp). Unknown lines are ignored. */
    fun parseApoText(text: String): Pair<Double, List<PeqBand>> {
        var preamp = 0.0
        val bands = mutableListOf<PeqBand>()
        val preampRe = Regex("""(?i)^\s*preamp\s*:\s*(-?[\d.]+)\s*db""")
        val filterRe = Regex("""(?i)^\s*filter\s*\d*\s*:\s*on\s+(\w+)\s+fc\s+([\d.]+)\s*hz\s+gain\s+(-?[\d.]+)\s*db\s+q\s+([\d.]+)""")
        // Imported files come from anywhere: malformed numbers ("1.2.3", "-") and values no filter can
        // realise (Fc 0 Hz, Q 0, +100 dB) are skipped instead of crashing or producing NaN coefficients.
        for (line in text.lineSequence()) {
            preampRe.find(line)?.let { m -> m.groupValues[1].toDoubleOrNull()?.takeIf { it in -30.0..30.0 }?.let { preamp = it } }
            filterRe.find(line)?.let { m ->
                val type = PeqType.fromApoCode(m.groupValues[1]) ?: return@let
                val fc = m.groupValues[2].toDoubleOrNull()?.takeIf { it > 0.0 && it < 24_000.0 } ?: return@let
                val gain = m.groupValues[3].toDoubleOrNull()?.takeIf { it in -30.0..30.0 } ?: return@let
                val q = m.groupValues[4].toDoubleOrNull()?.takeIf { it > 0.0 && it <= 100.0 } ?: return@let
                bands += PeqBand(type, fc, gain, q)
            }
        }
        return preamp to bands
    }

    internal fun fmt1(v: Double) = String.format(Locale.US, "%.1f", v)
    internal fun fmt2(v: Double) = String.format(Locale.US, "%.2f", v)
    internal fun fmtFreq(v: Double) = if (v >= 100) v.roundToInt().toString() else String.format(Locale.US, "%.1f", v)

    internal fun jsonString(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append(String.format("\\u%04x", c.code)) else append(c)
        }
        append('"')
    }
}

/** Preset naming: `AutoVybz_[Gerätename]_[Datum]`. */
object PresetNaming {

    const val PREFIX = "AutoVybz"

    fun defaultName(deviceName: String?, date: Date = Date(), existing: Collection<String> = emptyList()): String {
        val device = sanitize(deviceName).ifEmpty { "Speaker" }
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date)
        val base = "${PREFIX}_${device}_$day"
        if (base !in existing) return base
        var i = 2
        while ("${base}_$i" in existing) i++
        return "${base}_$i"
    }

    /** Keep letters (incl. umlauts), digits, '-', '.', '+'; whitespace -> '-'; max 32 chars. */
    fun sanitize(name: String?): String {
        if (name == null) return ""
        val collapsed = name.trim().replace(Regex("\\s+"), "-")
        val cleaned = buildString {
            for (c in collapsed) if (c.isLetterOrDigit() || c == '-' || c == '.' || c == '+') append(c)
        }
        return cleaned.replace(Regex("-{2,}"), "-").trim('-', '.').take(32).trimEnd('-', '.')
    }

    /** Validates a user-edited preset name (non-blank, no path / control characters). */
    fun isValid(name: String): Boolean =
        name.isNotBlank() && name.length <= 64 && name.none { it == '/' || it == '\\' || it < ' ' }
}
