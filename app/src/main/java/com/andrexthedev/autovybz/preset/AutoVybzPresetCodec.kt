package com.andrexthedev.autovybz.preset

import com.andrexthedev.autovybz.measurement.Verification
import com.andrexthedev.autovybz.measurement.eq.PeqBand
import com.andrexthedev.autovybz.measurement.eq.PeqType
import com.andrexthedev.autovybz.measurement.eq.TargetType
import com.andrexthedev.autovybz.preset.model.AutoVybzPreset
import com.andrexthedev.autovybz.preset.model.MeasurementSnapshot
import org.json.JSONArray
import org.json.JSONObject

/** Parses the internal `*.avz.json` format written by [AutoVybzPreset.toInternalJson]. */
object AutoVybzPresetCodec {

    fun fromInternalJson(json: String): AutoVybzPreset {
        val o = JSONObject(json)
        require(o.optString("format") == AutoVybzPreset.FORMAT_ID) { "not an AutoVybz preset" }
        val version = o.optInt("formatVersion", 1)
        require(version <= AutoVybzPreset.FORMAT_VERSION) { "preset was created by a newer AutoVybz (format $version)" }
        val bandsArr = o.getJSONArray("bands")
        val bands = (0 until bandsArr.length()).mapNotNull { i ->
            val b = bandsArr.getJSONObject(i)
            val type = PeqType.fromApoCode(b.getString("type")) ?: return@mapNotNull null
            PeqBand(type, b.getDouble("frequency"), b.getDouble("gain"), b.getDouble("q"))
        }
        val m = o.optJSONObject("measurement")?.let { mo ->
            MeasurementSnapshot(
                frequencies = mo.getJSONArray("frequencies").toDoubles(),
                rawDb = mo.getJSONArray("rawDb").toDoubles(),
                smoothedDb = mo.getJSONArray("smoothedDb").toDoubles(),
                targetDb = mo.getJSONArray("targetDb").toDoubles(),
                predictedDb = mo.getJSONArray("predictedDb").toDoubles(),
                micProfileId = mo.optString("micProfile"),
                micDescription = mo.optString("micDescription"),
                inputSource = mo.optString("inputSource"),
                engine = mo.optString("engine"),
                latencyMs = mo.optDouble("latencyMs", 0.0),
                medianSnrDb = mo.optDouble("medianSnrDb", 0.0),
                warnings = mo.optJSONArray("warnings")?.let { w -> (0 until w.length()).map { w.getString(it) } } ?: emptyList(),
                environment = mo.optString("environment", "INDOOR").ifEmpty { "INDOOR" },
                positions = mo.optInt("positions", 1),
                speakerClass = mo.optString("speakerClass", "STANDARD").ifEmpty { "STANDARD" },
                verification = mo.optJSONObject("verification")?.let { vo ->
                    // A preset from a newer build may carry a verdict this one does not know: the numbers are
                    // still worth keeping, the verdict is what the UI words, so an unknown name means "none".
                    val verdict = runCatching { Verification.Verdict.valueOf(vo.optString("verdict")) }.getOrNull()
                    verdict?.let {
                        Verification.Report(
                            correlation = vo.optDouble("correlation", 0.0),
                            measuredChangeDb = vo.optDouble("measuredChangeDb", 0.0),
                            predictedChangeDb = vo.optDouble("predictedChangeDb", 0.0),
                            predictionErrorDb = vo.optDouble("predictionErrorDb", 0.0),
                            residualBeforeDb = vo.optDouble("residualBeforeDb", 0.0),
                            residualAfterDb = vo.optDouble("residualAfterDb", 0.0),
                            residualPredictedDb = vo.optDouble("residualPredictedDb", 0.0),
                            measuredImprovementDb = vo.optDouble("measuredImprovementDb", 0.0),
                            predictedImprovementDb = vo.optDouble("predictedImprovementDb", 0.0),
                            judgeLowHz = vo.optDouble("judgeLowHz", 0.0),
                            judgeHighHz = vo.optDouble("judgeHighHz", 0.0),
                            verdict = it,
                        )
                    }
                },
            )
        }
        return AutoVybzPreset(
            name = o.getString("name"),
            createdAtMillis = o.optLong("createdAt", 0L),
            deviceManufacturer = o.optString("deviceManufacturer"),
            deviceModel = o.optString("deviceModel"),
            speakerName = if (o.has("speaker")) o.getString("speaker") else null,
            target = runCatching { TargetType.valueOf(o.optString("target")) }.getOrDefault(TargetType.SMART),
            bandCount = o.optInt("bandCount", bands.size),
            bands = bands,
            preampDb = o.optDouble("preamp", 0.0),
            lowLimitHz = o.optDouble("lowLimitHz", 20.0),
            highLimitHz = o.optDouble("highLimitHz", 20000.0),
            measurement = m,
            formatVersion = version,
        )
    }

    private fun JSONArray.toDoubles() = DoubleArray(length()) { getDouble(it) }
}
