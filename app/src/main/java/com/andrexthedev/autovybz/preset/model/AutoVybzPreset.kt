package com.andrexthedev.autovybz.preset.model

import com.andrexthedev.autovybz.export.PresetExporters
import com.andrexthedev.autovybz.measurement.Verification
import com.andrexthedev.autovybz.measurement.eq.PeqBand
import com.andrexthedev.autovybz.measurement.eq.PeqType
import com.andrexthedev.autovybz.measurement.eq.TargetType
import java.util.Locale

/** Measurement data stored alongside a calibration (for graphs, REW export, re-fitting). */
class MeasurementSnapshot(
    val frequencies: DoubleArray,
    /** Unsmoothed, mic-compensated, normalised response. */
    val rawDb: DoubleArray,
    /** Dynamically smoothed response used for the fit. */
    val smoothedDb: DoubleArray,
    val targetDb: DoubleArray,
    /** Predicted response after correction. */
    val predictedDb: DoubleArray,
    val micProfileId: String,
    val micDescription: String,
    val inputSource: String,
    val engine: String,
    val latencyMs: Double,
    val medianSnrDb: Double,
    val warnings: List<String>,
    /** [com.andrexthedev.autovybz.measurement.ListeningEnvironment] name; INDOOR for older presets. */
    val environment: String = "INDOOR",
    /** Number of averaged measurement positions. */
    val positions: Int = 1,
    /** [com.andrexthedev.autovybz.measurement.SpeakerClass] name; STANDARD for older presets. */
    val speakerClass: String = "STANDARD",
    /**
     * The optional "Check" run (W6) that verified this very plan, `null` when the user never ran one.
     *
     * It belongs to the calibration, not to the wizard session: a shared card and an exported preset should
     * say that the result was measured a second time, and with which numbers. A re-fit (another target)
     * would produce a different plan, so the wizard drops the report on the way to the new preset.
     */
    val verification: Verification.Report? = null,
) {
    init {
        val n = frequencies.size
        require(rawDb.size == n && smoothedDb.size == n && targetDb.size == n && predictedDb.size == n)
    }

    /**
     * A copy with [report] attached. Written out rather than using a data-class `copy`, because the class
     * carries five arrays: a generated `copy` would invite an accidental "same measurement, other arrays".
     */
    fun withVerification(report: Verification.Report?) = MeasurementSnapshot(
        frequencies, rawDb, smoothedDb, targetDb, predictedDb, micProfileId, micDescription,
        inputSource, engine, latencyMs, medianSnrDb, warnings, environment, positions, speakerClass, report,
    )
}

/**
 * Internal AutoVybz preset ("*.avz.json"). Superset of an Equalizer314 pool
 * preset: the EQ part goes into the shared `custom_presets` pool, the rest is
 * kept so a calibration can be inspected, re-exported or re-fitted later.
 */
data class AutoVybzPreset(
    val name: String,
    val createdAtMillis: Long,
    val deviceManufacturer: String,
    val deviceModel: String,
    val speakerName: String?,
    val target: TargetType,
    val bandCount: Int,
    val bands: List<PeqBand>,
    val preampDb: Double,
    val lowLimitHz: Double,
    val highLimitHz: Double,
    val measurement: MeasurementSnapshot? = null,
    val formatVersion: Int = FORMAT_VERSION,
) {
    companion object {
        const val FORMAT_VERSION = 1
        const val FORMAT_ID = "autovybz-preset"
        const val FILE_EXTENSION = ".avz.json"

        /** Equalizer314 BiquadFilter.FilterType names. */
        fun filterTypeName(type: PeqType): String = when (type) {
            PeqType.PEAK -> "BELL"
            PeqType.LOW_SHELF -> "LOW_SHELF"
            PeqType.HIGH_SHELF -> "HIGH_SHELF"
            PeqType.HIGH_PASS -> "HIGH_PASS"
        }

        fun peqTypeFromFilterTypeName(name: String): PeqType? = when (name) {
            "BELL" -> PeqType.PEAK
            "LOW_SHELF" -> PeqType.LOW_SHELF
            "HIGH_SHELF" -> PeqType.HIGH_SHELF
            "HIGH_PASS" -> PeqType.HIGH_PASS
            else -> null
        }
    }

    /**
     * JSON in the exact shape Equalizer314 uses for `custom_presets/preset_<name>`
     * (preamp, channelSideEqEnabled=false, bands[{frequency,gain,q,filterType,enabled}]),
     * plus an "autovybz" summary object that upstream code simply ignores.
     */
    fun toPoolJson(): String = buildString {
        append("{\"preamp\":").append(num(preampDb))
        append(",\"channelSideEqEnabled\":false,\"bands\":[")
        bands.forEachIndexed { i, b ->
            if (i > 0) append(',')
            append("{\"frequency\":").append(num(b.frequency))
            append(",\"gain\":").append(num(b.gainDb))
            append(",\"q\":").append(num(b.q))
            append(",\"filterType\":\"").append(filterTypeName(b.type)).append('"')
            append(",\"enabled\":true}")
        }
        append("],\"autovybz\":{\"formatVersion\":").append(formatVersion)
        append(",\"target\":\"").append(target.name).append('"')
        append(",\"createdAt\":").append(createdAtMillis)
        append(",\"device\":").append(PresetExporters.jsonString("$deviceManufacturer $deviceModel".trim()))
        speakerName?.let { append(",\"speaker\":").append(PresetExporters.jsonString(it)) }
        append("}}")
    }

    /** Full internal format (pretty printed). Parsed by AutoVybzPresetCodec (org.json, Android layer). */
    fun toInternalJson(): String = buildString {
        append("{\n")
        kv("format", PresetExporters.jsonString(FORMAT_ID)); kv("formatVersion", formatVersion.toString())
        kv("name", PresetExporters.jsonString(name))
        kv("createdAt", createdAtMillis.toString())
        kv("deviceManufacturer", PresetExporters.jsonString(deviceManufacturer))
        kv("deviceModel", PresetExporters.jsonString(deviceModel))
        speakerName?.let { kv("speaker", PresetExporters.jsonString(it)) }
        kv("target", PresetExporters.jsonString(target.name))
        kv("bandCount", bandCount.toString())
        kv("preamp", num(preampDb))
        kv("lowLimitHz", num(lowLimitHz)); kv("highLimitHz", num(highLimitHz))
        append("  \"bands\": [")
        bands.forEachIndexed { i, b ->
            append(if (i == 0) "\n" else ",\n")
            append("    {\"type\": \"").append(b.type.apoCode).append("\", \"frequency\": ").append(num(b.frequency))
            append(", \"gain\": ").append(num(b.gainDb)).append(", \"q\": ").append(num(b.q)).append('}')
        }
        append(if (bands.isEmpty()) "]" else "\n  ]")
        val m = measurement
        if (m != null) {
            append(",\n  \"measurement\": {\n")
            append("    \"micProfile\": ").append(PresetExporters.jsonString(m.micProfileId)).append(",\n")
            append("    \"micDescription\": ").append(PresetExporters.jsonString(m.micDescription)).append(",\n")
            append("    \"inputSource\": ").append(PresetExporters.jsonString(m.inputSource)).append(",\n")
            append("    \"engine\": ").append(PresetExporters.jsonString(m.engine)).append(",\n")
            append("    \"latencyMs\": ").append(num(m.latencyMs)).append(",\n")
            append("    \"medianSnrDb\": ").append(num(m.medianSnrDb)).append(",\n")
            append("    \"warnings\": [").append(m.warnings.joinToString(", ") { PresetExporters.jsonString(it) }).append("],\n")
            append("    \"environment\": ").append(PresetExporters.jsonString(m.environment)).append(",\n")
            append("    \"positions\": ").append(m.positions).append(",\n")
            append("    \"speakerClass\": ").append(PresetExporters.jsonString(m.speakerClass)).append(",\n")
            arr("frequencies", m.frequencies, ",\n")
            arr("rawDb", m.rawDb, ",\n")
            arr("smoothedDb", m.smoothedDb, ",\n")
            arr("targetDb", m.targetDb, ",\n")
            // Trailing comma only when another member follows: an unchecked preset must stay valid JSON.
            arr("predictedDb", m.predictedDb, if (m.verification == null) "\n" else ",\n")
            m.verification?.let { v ->
                append("    \"verification\": {\n")
                append("      \"verdict\": \"").append(v.verdict.name).append("\",\n")
                append("      \"correlation\": ").append(num(v.correlation)).append(",\n")
                append("      \"measuredChangeDb\": ").append(num(v.measuredChangeDb)).append(",\n")
                append("      \"predictedChangeDb\": ").append(num(v.predictedChangeDb)).append(",\n")
                append("      \"predictionErrorDb\": ").append(num(v.predictionErrorDb)).append(",\n")
                append("      \"residualBeforeDb\": ").append(num(v.residualBeforeDb)).append(",\n")
                append("      \"residualAfterDb\": ").append(num(v.residualAfterDb)).append(",\n")
                append("      \"residualPredictedDb\": ").append(num(v.residualPredictedDb)).append(",\n")
                append("      \"measuredImprovementDb\": ").append(num(v.measuredImprovementDb)).append(",\n")
                append("      \"predictedImprovementDb\": ").append(num(v.predictedImprovementDb)).append(",\n")
                append("      \"judgeLowHz\": ").append(num(v.judgeLowHz)).append(",\n")
                append("      \"judgeHighHz\": ").append(num(v.judgeHighHz)).append("\n")
                append("    }\n")
            }
            append("  }")
        }
        append("\n}\n")
    }

    fun toApoText(): String = PresetExporters.toApoText(
        bands, preampDb,
        buildString {
            append("AutoVybz calibration: $name\nTarget: ${target.name}, device: $deviceManufacturer $deviceModel")
            // The check (W6) travels with the file: whoever imports these filters can see that the result
            // was measured a second time through the correction, and with which numbers.
            measurement?.verification?.let { append("\n").append(it.exportLine) }
        },
    )

    /**
     * Header lines for the REW export (`* ` comments): what was measured how, and - when it was run - the
     * verdict of the check. Lives here rather than in the export menu so it can be unit-tested.
     */
    fun rewHeader(): List<String> = buildList {
        add("Name: $name")
        val m = measurement ?: return@buildList
        add("Mic: ${m.micDescription}")
        add("Input: ${m.inputSource}")
        add("Normalised to 0 dB (300 Hz - 3 kHz)")
        m.verification?.let { add(it.exportLine) }
    }

    private fun StringBuilder.kv(k: String, v: String) {
        append("  \"").append(k).append("\": ").append(v).append(",\n")
    }

    private fun StringBuilder.arr(k: String, v: DoubleArray, end: String) {
        append("    \"").append(k).append("\": [")
        v.forEachIndexed { i, x -> if (i > 0) append(','); append(num(x)) }
        append(']').append(end)
    }

    private fun num(v: Double): String {
        if (!v.isFinite()) return "0"
        val s = String.format(Locale.US, "%.4f", v).trimEnd('0').trimEnd('.')
        return if (s == "-0") "0" else s
    }
}
