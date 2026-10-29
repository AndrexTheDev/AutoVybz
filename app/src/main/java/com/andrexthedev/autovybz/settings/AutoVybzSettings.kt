package com.andrexthedev.autovybz.settings

import android.content.Context
import android.util.Log
import com.andrexthedev.autovybz.measurement.ListeningEnvironment
import com.andrexthedev.autovybz.measurement.MeasurementRequest
import com.andrexthedev.autovybz.measurement.SpeakerClass
import com.andrexthedev.autovybz.measurement.SpeakerClassTuning
import com.andrexthedev.autovybz.measurement.dsp.SmoothingProfile
import com.andrexthedev.autovybz.measurement.eq.CustomTarget
import com.andrexthedev.autovybz.measurement.eq.TargetType
import com.andrexthedev.autovybz.measurement.mic.CalibrationFileParser
import com.andrexthedev.autovybz.measurement.mic.ResponseCurve
import java.io.File
import java.io.FileOutputStream

enum class SmoothingChoice(val profile: SmoothingProfile) {
    DYNAMIC(SmoothingProfile.DYNAMIC),
    FINE(SmoothingProfile.FINE),
    STRONG(SmoothingProfile.STRONG),
}

/** Persisted AutoVybz preferences ("autovybz_settings"). */
class AutoVybzSettings(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("autovybz_settings", Context.MODE_PRIVATE)

    var target: TargetType
        get() = runCatching { TargetType.valueOf(prefs.getString("target", null) ?: "") }.getOrDefault(TargetType.SMART)
        set(v) = prefs.edit().putString("target", v.name).apply()

    /** 5 = fast, 10 = max precision (default). */
    var bandCount: Int
        get() = prefs.getInt("bandCount", 10).let { if (it == 5) 5 else 10 }
        set(v) = prefs.edit().putInt("bandCount", if (v == 5) 5 else 10).apply()

    /** Microphone positions per calibration: 1 = quick (10 s), 3 = precise (spatial average, ~30 s). */
    var positions: Int
        get() = if (prefs.getInt("positions", 1) == 3) 3 else 1
        set(v) = prefs.edit().putInt("positions", if (v == 3) 3 else 1).apply()

    /** Indoor (default) or outdoor / party. */
    var environment: ListeningEnvironment
        get() = runCatching { ListeningEnvironment.valueOf(prefs.getString("environment", null) ?: "") }
            .getOrDefault(ListeningEnvironment.INDOOR)
        set(v) = prefs.edit().putString("environment", v.name).apply()

    /**
     * W8: the user's subwoofer crossover in Hz (80 / 100 / 120), 0 when there is no sub. Above it the app
     * corrects; below it the sub's own tuning and level knob rule. Stored as an int so an old install reads
     * "no sub" instead of an unparsable string, and so a future hand-edited value degrades to "no sub".
     */
    var subwooferCrossoverHz: Double
        get() = prefs.getInt("subCrossoverHz", 0).let { if (it in setOf(80, 100, 120)) it.toDouble() else 0.0 }
        set(v) = prefs.edit().putInt("subCrossoverHz", if (v in setOf(80.0, 100.0, 120.0)) v.toInt() else 0).apply()

    /** External speaker (default), weak-speaker rescue, or this device's own speakers. */
    var speakerClass: SpeakerClass
        get() = runCatching { SpeakerClass.valueOf(prefs.getString("speakerClass", null) ?: "") }
            .getOrDefault(SpeakerClass.STANDARD)
        set(v) = prefs.edit().putString("speakerClass", v.name).apply()

    var smoothing: SmoothingChoice
        get() = runCatching { SmoothingChoice.valueOf(prefs.getString("smoothing", null) ?: "") }.getOrDefault(SmoothingChoice.DYNAMIC)
        set(v) = prefs.edit().putString("smoothing", v.name).apply()

    var maxBoostDb: Float
        get() = prefs.getFloat("maxBoostDb", 6f).coerceIn(0f, 9f)
        set(v) = prefs.edit().putFloat("maxBoostDb", v.coerceIn(0f, 9f)).apply()

    var customBassDb: Float
        get() = prefs.getFloat("customBassDb", 4f)
        set(v) = prefs.edit().putFloat("customBassDb", v).apply()
    var customTrebleDb: Float
        get() = prefs.getFloat("customTrebleDb", 0f)
        set(v) = prefs.edit().putFloat("customTrebleDb", v).apply()
    var customTiltDbPerOct: Float
        get() = prefs.getFloat("customTilt", 0f)
        set(v) = prefs.edit().putFloat("customTilt", v).apply()

    var useUsbMic: Boolean
        get() = prefs.getBoolean("useUsbMic", false)
        set(v) = prefs.edit().putBoolean("useUsbMic", v).apply()

    /** null = automatic (Build.MANUFACTURER / MODEL). */
    var micProfileOverrideId: String?
        get() = prefs.getString("micProfileOverride", null)
        set(v) = prefs.edit().putString("micProfileOverride", v).apply()

    var restoreVolumeAfter: Boolean
        get() = prefs.getBoolean("restoreVolumeAfter", true)
        set(v) = prefs.edit().putBoolean("restoreVolumeAfter", v).apply()

    /**
     * Field data: keep one anonymous record per calibration so the app's own limits (distortion gate,
     * room gate, position outlier rule, measurement level) can be recalibrated against real devices
     * instead of a model - see docs/FIELD_DATA.md. Local only; defaults on, and the Studio card shows the
     * count and can delete or export the file. Nothing is ever uploaded.
     */
    var fieldLog: Boolean
        get() = prefs.getBoolean("fieldLog", true)
        set(v) = prefs.edit().putBoolean("fieldLog", v).apply()

    /**
     * Start of the current field-data collection run (0 = none). The Studio card exports only samples from
     * this moment on, so a tester can hand over what they recorded for a beta without handing over every
     * calibration their phone ever made.
     */
    var fieldLogMarkMillis: Long
        get() = prefs.getLong("fieldLogMark", 0L)
        set(v) = prefs.edit().putLong("fieldLogMark", v).apply()

    var enableLimiterOnApply: Boolean
        get() = prefs.getBoolean("enableLimiterOnApply", true)
        set(v) = prefs.edit().putBoolean("enableLimiterOnApply", v).apply()

    var lastSpeakerName: String
        get() = prefs.getString("lastSpeakerName", "") ?: ""
        set(v) = prefs.edit().putString("lastSpeakerName", v).apply()

    /* ---- calibration files (Pro) ---- */

    private fun micDir() = File(app.filesDir, "autovybz/mic").apply { mkdirs() }
    val usbCalibrationFile: File get() = File(micDir(), "usb_calibration.txt")
    val userOffsetFile: File get() = File(micDir(), "user_offset.txt")

    /**
     * Validates + stores a calibration/offset text. Throws IllegalArgumentException on bad data.
     *
     * Written through a sibling `.tmp` file + rename: `writeText` truncates first, so a full disk, a killed
     * process or a crash in the middle left a half file behind - and since [loadCurve] ignores unparseable
     * files, the user's imported mic calibration then silently disappeared with a generic "invalid file"
     * message on the next attempt. The rename is atomic on the app's own (POSIX) filesystem, so a reader
     * either sees the old or the new text.
     */
    fun storeCurve(file: File, text: String): ResponseCurve {
        val cal = CalibrationFileParser.parse(text)
        val dir = file.parentFile ?: app.filesDir
        val tmp = File(dir, file.name + ".tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync() // survive a power loss right after the rename
            }
            if (!tmp.renameTo(file)) {
                // Some (rare) filesystems refuse to rename over an existing file: fall back, but only after
                // the content is already completely on disk.
                file.writeText(text)
            }
        } finally {
            tmp.delete() // no-op after a successful rename; never leave a stale .tmp in files/
        }
        return cal.curve
    }

    fun loadCurve(file: File): ResponseCurve? {
        if (!file.exists()) return null
        return runCatching { CalibrationFileParser.parse(file.readText()).curve }
            .onFailure { Log.w(TAG, "ignoring unreadable calibration file ${file.name}", it) }
            .getOrNull()
    }

    fun clearCurve(file: File) { file.delete() }

    fun customTarget() = CustomTarget(
        bassGainDb = customBassDb.toDouble(),
        trebleGainDb = customTrebleDb.toDouble(),
        tiltDbPerOctave = customTiltDbPerOct.toDouble(),
    )

    fun buildRequest(speakerName: String?, target: TargetType = this.target): MeasurementRequest {
        val cls = speakerClass
        return MeasurementRequest(
            speakerName = speakerName?.trim()?.takeIf { it.isNotEmpty() },
            target = target,
            customTarget = customTarget(),
            bandCount = bandCount,
            maxBoostDb = maxBoostDb.toDouble(),
            smoothing = smoothing.profile,
            // A device-speaker calibration is defined around the phone's own microphone.
            useUsbMic = useUsbMic && !SpeakerClassTuning.of(cls).forcePhoneMic,
            usbCalibration = loadCurve(usbCalibrationFile),
            userMicOffset = loadCurve(userOffsetFile),
            micProfileOverrideId = micProfileOverrideId,
            environment = environment,
            speakerClass = cls,
            subwooferCrossoverHz = subwooferCrossoverHz.takeIf { it > 0.0 },
        )
    }

    private companion object {
        const val TAG = "AutoVybzSettings"
    }
}
