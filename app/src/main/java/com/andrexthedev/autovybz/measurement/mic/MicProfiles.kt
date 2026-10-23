package com.andrexthedev.autovybz.measurement.mic

import com.andrexthedev.autovybz.measurement.dsp.FrequencyGrid

/** Microphone capture path. Determines which compensation curve applies. */
enum class InputSource {
    /** MediaRecorder.AudioSource.UNPROCESSED - raw, no AGC/NS/HPF (preferred). */
    UNPROCESSED,
    /** MediaRecorder.AudioSource.VOICE_RECOGNITION - CDD: no AGC/NS, but usually a voice HPF. */
    VOICE_RECOGNITION,
    /** MediaRecorder.AudioSource.MIC - last resort; may contain processing. */
    MIC,
    /** External USB measurement microphone (uses its own calibration file). */
    USB,
}

enum class ProfileConfidence { MEASURED, COMMUNITY, ESTIMATED }

/** A frequency/level table in dB (what the microphone *adds*; compensation subtracts it). */
class ResponseCurve(points: List<Pair<Double, Double>>) {
    val frequencies: DoubleArray
    val levelsDb: DoubleArray

    init {
        require(points.size >= 2) { "a curve needs at least two points" }
        val sorted = points.sortedBy { it.first }
        frequencies = DoubleArray(sorted.size) { sorted[it].first }
        levelsDb = DoubleArray(sorted.size) { sorted[it].second }
        require(frequencies.all { it > 0 }) { "frequencies must be positive" }
    }

    fun levelAt(f: Double): Double = FrequencyGrid.interpolateLog(frequencies, levelsDb, f)

    /** Same curve shifted so that 1 kHz reads 0 dB (absolute sensitivity is irrelevant for EQ). */
    fun normalisedAt1k(): ResponseCurve {
        val ref = levelAt(1000.0)
        return ResponseCurve(frequencies.indices.map { frequencies[it] to levelsDb[it] - ref })
    }
}

class MicProfile(
    val id: String,
    val label: String,
    val manufacturer: String?,
    /** Case-insensitive regexes matched against Build.MODEL. Empty = any model of [manufacturer]. */
    val modelPatterns: List<Regex>,
    val confidence: ProfileConfidence,
    val curves: Map<InputSource, ResponseCurve>,
    val notes: String? = null,
) {
    /** Compensation lookup; falls back to UNPROCESSED, then to 0 dB. */
    fun levelAt(f: Double, source: InputSource): Double {
        val curve = curves[source] ?: curves[InputSource.UNPROCESSED] ?: return 0.0
        return curve.levelAt(f)
    }

    fun matchesModel(model: String): Boolean = modelPatterns.isEmpty() || modelPatterns.any { it.matches(model.trim()) }
}

/**
 * Picks the best profile for a device: model-specific match of the same
 * manufacturer beats a manufacturer-wide entry, which beats the default.
 * Among equal specificity, better [ProfileConfidence] wins.
 */
object MicProfileMatcher {

    fun match(profiles: List<MicProfile>, default: MicProfile, manufacturer: String, model: String): MicProfile {
        val manu = normaliseManufacturer(manufacturer)
        val candidates = profiles.filter { p ->
            p.manufacturer != null && normaliseManufacturer(p.manufacturer) == manu && p.matchesModel(model)
        }
        return candidates.minWithOrNull(
            compareBy<MicProfile>({ if (it.modelPatterns.isEmpty()) 1 else 0 }, { it.confidence.ordinal }),
        ) ?: default
    }

    /** "samsung", "Samsung Electronics" -> "samsung"; Redmi/POCO devices report "Xiaomi". */
    fun normaliseManufacturer(m: String): String {
        val s = m.trim().lowercase()
        return when {
            s.startsWith("samsung") -> "samsung"
            s == "redmi" || s == "poco" -> "xiaomi"
            s.startsWith("hmd") -> "hmd global"
            else -> s
        }
    }
}

/**
 * Parser for measurement-microphone calibration files and manual offset
 * profiles. Accepts the common miniDSP UMIK / Dayton / REW formats:
 * ```
 * "Sens Factor =-1.378dB, SERNO: 7000000"
 * 20.0   -1.20   [phase]
 * 1000   0.00
 * ```
 * Separators: whitespace, comma, semicolon or tab. Lines starting with `*`, `#`,
 * `"` or letters are treated as comments (a sensitivity factor is extracted).
 */
object CalibrationFileParser {

    data class Calibration(val curve: ResponseCurve, val sensitivityDb: Double?)

    private val sensRe = Regex("""(?i)sens(?:itivity)?\s*factor\s*=\s*(-?[\d.]+)""")

    fun parse(text: String): Calibration {
        var sens: Double? = null
        val pts = mutableListOf<Pair<Double, Double>>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            sensRe.find(line)?.let { sens = it.groupValues[1].toDoubleOrNull() }
            if (!(line[0].isDigit() || line[0] == '.')) continue
            val parts = line.split(Regex("[\\s,;]+")).filter { it.isNotEmpty() }
            if (parts.size < 2) continue
            val f = parts[0].toDoubleOrNull() ?: continue
            val db = parts[1].toDoubleOrNull() ?: continue
            if (f > 0 && db in -60.0..60.0) pts += f to db
        }
        require(pts.size >= 2) { "No frequency/dB pairs found" }
        return Calibration(ResponseCurve(pts), sens)
    }
}

/**
 * Final compensation function handed to the analyzer: the phone profile for the
 * active input source plus an optional user offset curve (Pro option), or an
 * external USB mic calibration that replaces the phone profile entirely.
 */
class MicCompensation private constructor(
    val description: String,
    private val curves: List<ResponseCurve>,
) : (Double) -> Double {

    override fun invoke(f: Double): Double = curves.sumOf { it.levelAt(f) }

    companion object {
        val NONE = MicCompensation("none", emptyList())

        fun forPhone(profile: MicProfile, source: InputSource, userOffset: ResponseCurve? = null): MicCompensation {
            val base = profile.curves[source] ?: profile.curves[InputSource.UNPROCESSED]
            val list = listOfNotNull(base?.normalisedAt1k(), userOffset)
            val desc = buildString {
                append(profile.label).append(" [").append(source.name).append(", ")
                append(profile.confidence.name.lowercase()).append(']')
                if (userOffset != null) append(" + user offset")
            }
            return MicCompensation(desc, list)
        }

        fun forUsbMic(calibration: ResponseCurve?, label: String = "USB measurement mic"): MicCompensation =
            MicCompensation(if (calibration != null) "$label (calibrated)" else "$label (uncalibrated)", listOfNotNull(calibration?.normalisedAt1k()))
    }
}
