package com.andrexthedev.autovybz.measurement

import com.andrexthedev.autovybz.measurement.audio.MeasurementLevel
import com.andrexthedev.autovybz.measurement.dsp.HarmonicDistortion
import com.andrexthedev.autovybz.measurement.dsp.MeasurementWarning
import com.andrexthedev.autovybz.measurement.eq.TargetType
import org.json.JSONArray
import org.json.JSONObject

/**
 * The four questions that a model cannot answer (docs/FIELD_DATA.md, roadmap §7) need numbers from real
 * rooms and real speakers. This is the record that carries those numbers out of a calibration:
 *
 * | Question | Field in [Sample] |
 * |---|---|
 * | W3 "how much THD do real speakers have at 70 %?" | [Sample.thdPercent], [Sample.volumeFraction] |
 * | W4 "how long is a real room?" | [Sample.rt60S] |
 * | W5 "how far apart are the three positions really?" | [Sample.positionDeviationsDb] |
 * | W11 "does the mic clip before the speaker distorts?" | [Sample.levelBound] |
 *
 * ## What is deliberately *not* in here
 *
 * No frequency curves, no filter list, no preset or speaker name, no recording, no account - a sample is a
 * dozen numbers about a measurement, not about the user's music or their room. `device` is the
 * manufacturer/model pair that a preset already stores locally (needed to tell one phone's microphone from
 * another), and it is only ever read by whoever the user hands the file to: nothing is uploaded, ever
 * (PRIVACY_POLICY.md §4).
 *
 * ## Why the sample is flat and schema-versioned
 *
 * The consumer is `tools/fielddata/analyze.py`, not the app: one JSON object per line, every key stable,
 * `schema` bumped when a key changes meaning. A reader must be able to skip a line it cannot parse instead
 * of losing the file, so [fromJson] returns `null` rather than throwing.
 */
object FieldLog {

    /** Bumped when a key changes meaning; older lines stay readable (`schema` is explicit, not implied). */
    const val SCHEMA = 1

    /**
     * Who forced the level down, when anyone did (W11's question). Derived, not stored: it follows from
     * two numbers that are already in the record, and a stored copy could disagree with them.
     */
    enum class LevelBound {
        /** Neither: the speaker stayed under the distortion gate and the microphone stayed under full scale. */
        NONE,

        /** The microphone hit full scale while the speaker was still linear - the capture, not the driver. */
        MIC_CLIPPING,

        /** The speaker distorted above [HarmonicDistortion.THD_WARN_PERCENT] while the capture was clean. */
        SPEAKER_THD,

        /** Both at once: the capture clipped *and* the speaker distorted, so the two cannot be told apart. */
        BOTH,
    }

    data class Sample(
        val createdAtMillis: Long,
        val device: String,
        val speakerClass: String,
        val environment: String,
        val target: String,
        /** Microphone positions that were averaged (1 = quick run). */
        val positions: Int,
        /** Deviation of every position from the median curve (dB); empty for a single position. */
        val positionDeviationsDb: List<Double>,
        /** Positions dropped as outliers. */
        val rejectedPositions: Int,
        /** RMS disagreement of the kept positions, dB. */
        val spreadDb: Double?,
        /** THD of the loudspeaker during the sweep, percent - the W3 number. */
        val thdPercent: Double?,
        /** True when the microphone hit full scale (see `CaptureAudit`). */
        val micClipped: Boolean,
        /** Peak input sample (1.0 = full scale), the headroom the capture had left. */
        val inputPeak: Double,
        /** Measured reverberation time, seconds - the W4 number. */
        val rt60S: Double?,
        /** Media stream fraction the measurement ran at (0.7 = the documented 70 %). */
        val volumeFraction: Double,
        /** How far W11 turned the level down for this run (0.0 = not at all). */
        val levelStepDb: Double,
        /** Warning names, as the app words them (`MeasurementWarning`); bounds the record to a known set. */
        val warnings: List<String>,
        val schema: Int = SCHEMA,
    ) {
        /**
         * W11, asked of the two numbers that produced it: a clipped *capture* and a distorting *speaker*
         * are different faults, and only one of them means "the phone, not the speaker, is the limit".
         */
        val levelBound: LevelBound
            get() {
                val mic = micClipped || MeasurementWarning.CLIPPING.name in warnings
                val speaker = thdPercent != null && thdPercent >= HarmonicDistortion.THD_WARN_PERCENT
                return when {
                    mic && speaker -> LevelBound.BOTH
                    mic -> LevelBound.MIC_CLIPPING
                    speaker -> LevelBound.SPEAKER_THD
                    else -> LevelBound.NONE
                }
            }

        /** True when W11 actually turned this measurement down (i.e. the starting level was too hot). */
        val levelWasLowered: Boolean get() = levelStepDb > 0.0
    }

    /**
     * Builds a record from plain values (no `MeasurementOutcome`, so this stays testable without Android).
     * All arguments are required except the ones that a quick single-position run has no answer for.
     */
    fun sample(
        createdAtMillis: Long,
        device: String,
        speakerClass: SpeakerClass,
        environment: ListeningEnvironment,
        target: TargetType,
        positions: Int,
        positionDeviationsDb: List<Double>,
        rejectedPositions: Int,
        spreadDb: Double?,
        thdPercent: Double?,
        micClipped: Boolean,
        inputPeak: Double,
        rt60S: Double?,
        volumeFraction: Double,
        levelStepDb: Double,
        warnings: List<MeasurementWarning>,
    ): Sample = Sample(
        createdAtMillis = createdAtMillis,
        device = device,
        speakerClass = speakerClass.name,
        environment = environment.name,
        target = target.name,
        positions = positions,
        positionDeviationsDb = positionDeviationsDb,
        rejectedPositions = rejectedPositions,
        spreadDb = spreadDb,
        thdPercent = thdPercent,
        micClipped = micClipped,
        inputPeak = inputPeak,
        rt60S = rt60S,
        volumeFraction = volumeFraction,
        levelStepDb = levelStepDb,
        warnings = warnings.map { it.name },
    )

    /** One line of JSONL. Keys are written in a fixed, readable order; `null` is a JSON `null`. */
    fun toJson(sample: Sample): String {
        val o = JSONObject()
        o.put("schema", sample.schema)
        o.put("at", sample.createdAtMillis)
        o.put("device", sample.device)
        o.put("speakerClass", sample.speakerClass)
        o.put("environment", sample.environment)
        o.put("target", sample.target)
        o.put("positions", sample.positions)
        o.put("deviations", JSONArray(sample.positionDeviationsDb))
        o.put("rejected", sample.rejectedPositions)
        o.put("spreadDb", sample.spreadDb ?: JSONObject.NULL)
        o.put("thdPercent", sample.thdPercent ?: JSONObject.NULL)
        o.put("micClipped", sample.micClipped)
        o.put("inputPeak", sample.inputPeak)
        o.put("rt60S", sample.rt60S ?: JSONObject.NULL)
        o.put("volume", sample.volumeFraction)
        o.put("levelStepDb", sample.levelStepDb)
        o.put("warnings", JSONArray(sample.warnings))
        return o.toString()
    }

    /**
     * Parses one line back. `null` - never an exception - for anything this build does not understand: a
     * line from a newer schema, a truncated write, a hand-edited file. Losing one sample is acceptable;
     * losing the file is not.
     */
    fun fromJson(line: String): Sample? {
        if (line.isBlank()) return null
        val o = runCatching { JSONObject(line) }.getOrNull() ?: return null
        val schema = o.optInt("schema", -1)
        if (schema !in 1..SCHEMA) return null
        val at = o.optLong("at", -1L)
        if (at <= 0L) return null
        return Sample(
            createdAtMillis = at,
            device = o.optString("device", ""),
            speakerClass = o.optString("speakerClass", ""),
            environment = o.optString("environment", ""),
            target = o.optString("target", ""),
            positions = o.optInt("positions", 1),
            positionDeviationsDb = o.optJSONArray("deviations")?.let { a ->
                (0 until a.length()).map { a.optDouble(it, 0.0) }
            } ?: emptyList(),
            rejectedPositions = o.optInt("rejected", 0),
            spreadDb = o.optDoubleOrNull("spreadDb"),
            thdPercent = o.optDoubleOrNull("thdPercent"),
            micClipped = o.optBoolean("micClipped", false),
            inputPeak = o.optDouble("inputPeak", 0.0),
            rt60S = o.optDoubleOrNull("rt60S"),
            volumeFraction = o.optDouble("volume", 0.0),
            levelStepDb = o.optDouble("levelStepDb", 0.0),
            warnings = o.optJSONArray("warnings")
                ?.let { a -> (0 until a.length()).map { a.optString(it, "") } } ?: emptyList(),
            schema = schema,
        )
    }

    /** Every key [toJson] writes - the set a privacy test can check and the analyser relies on. */
    val KEYS = listOf(
        "schema", "at", "device", "speakerClass", "environment", "target", "positions", "deviations",
        "rejected", "spreadDb", "thdPercent", "micClipped", "inputPeak", "rt60S", "volume", "levelStepDb",
        "warnings",
    )

    /** The starting level the app documents (W11): 70 % of the stream, 50 % for the device's own speaker. */
    const val DOCUMENTED_VOLUME = 0.7

    /** The level W11 aims at; the analyser compares a field distribution against it. */
    const val TARGET_THD_PERCENT = MeasurementLevel.TARGET_THD_PERCENT

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (isNull(key)) null else optDouble(key).takeIf { it.isFinite() }
}
