package com.andrexthedev.autovybz.settings

import android.content.Context
import com.andrexthedev.autovybz.measurement.ListeningEnvironment
import com.andrexthedev.autovybz.measurement.MeasurementRequest
import com.andrexthedev.autovybz.measurement.dsp.SmoothingProfile
import com.andrexthedev.autovybz.measurement.eq.CustomTarget
import com.andrexthedev.autovybz.measurement.eq.TargetType
import com.andrexthedev.autovybz.measurement.mic.CalibrationFileParser
import com.andrexthedev.autovybz.measurement.mic.ResponseCurve
import java.io.File

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

    /** Validates + stores a calibration/offset text. Throws IllegalArgumentException on bad data. */
    fun storeCurve(file: File, text: String): ResponseCurve {
        val cal = CalibrationFileParser.parse(text)
        file.writeText(text)
        return cal.curve
    }

    fun loadCurve(file: File): ResponseCurve? =
        if (file.exists()) runCatching { CalibrationFileParser.parse(file.readText()).curve }.getOrNull() else null

    fun clearCurve(file: File) { file.delete() }

    fun customTarget() = CustomTarget(
        bassGainDb = customBassDb.toDouble(),
        trebleGainDb = customTrebleDb.toDouble(),
        tiltDbPerOctave = customTiltDbPerOct.toDouble(),
    )

    fun buildRequest(speakerName: String?, target: TargetType = this.target): MeasurementRequest = MeasurementRequest(
        speakerName = speakerName?.trim()?.takeIf { it.isNotEmpty() },
        target = target,
        customTarget = customTarget(),
        bandCount = bandCount,
        maxBoostDb = maxBoostDb.toDouble(),
        smoothing = smoothing.profile,
        useUsbMic = useUsbMic,
        usbCalibration = loadCurve(usbCalibrationFile),
        userMicOffset = loadCurve(userOffsetFile),
        micProfileOverrideId = micProfileOverrideId,
        environment = environment,
    )
}
