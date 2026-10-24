package com.andrexthedev.autovybz.sound

import android.content.Context
import com.andrexthedev.autovybz.preset.AutoVybzPresetStore
import com.andrexthedev.autovybz.settings.AutoVybzSettings

/**
 * The user's everyday sound: speaker profile (calibration or none) + style × intensity + Studio bass/treble.
 * Persisted in "autovybz_sound"; [reapply] makes the combination audible through [AutoVybzPresetStore.apply],
 * which layers the selection on the calibration (so picking another speaker keeps the chosen style).
 */
object VybzSound {
    private const val PREFS = "autovybz_sound"
    private const val KEY_STYLE = "style"
    private const val KEY_INTENSITY = "intensity"
    private const val KEY_BASS = "bassDb"
    private const val KEY_TREBLE = "trebleDb"
    private const val KEY_USE_CAL = "useCalibration"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun selection(context: Context): SoundSelection {
        val p = prefs(context)
        return SoundSelection(
            style = SoundStyle.fromName(p.getString(KEY_STYLE, null)),
            intensity = p.getInt(KEY_INTENSITY, SoundStyle.DEFAULT_INTENSITY).coerceIn(0, 100),
            bassDb = p.getFloat(KEY_BASS, 0f).toDouble().coerceIn(-SoundComposer.MAX_TONE_DB, SoundComposer.MAX_TONE_DB),
            trebleDb = p.getFloat(KEY_TREBLE, 0f).toDouble().coerceIn(-SoundComposer.MAX_TONE_DB, SoundComposer.MAX_TONE_DB),
        )
    }

    /** False = "without calibration": the style alone shapes the sound. */
    fun usesCalibration(context: Context): Boolean = prefs(context).getBoolean(KEY_USE_CAL, true)

    /** Name of the calibration that is audible right now, or null (none saved / "without calibration"). */
    fun activeCalibrationName(context: Context): String? {
        if (!usesCalibration(context)) return null
        // no file access: safe on the main thread (deleting the active calibration clears the name in the store)
        return AutoVybzPresetStore.lastAppliedName(context)
    }

    fun save(context: Context, selection: SoundSelection) {
        prefs(context).edit()
            .putString(KEY_STYLE, selection.style.name)
            .putInt(KEY_INTENSITY, selection.intensity.coerceIn(0, 100))
            .putFloat(KEY_BASS, selection.bassDb.toFloat())
            .putFloat(KEY_TREBLE, selection.trebleDb.toFloat())
            .apply()
    }

    fun setStyle(context: Context, style: SoundStyle, intensity: Int = selection(context).intensity) {
        save(context, selection(context).copy(style = style, intensity = intensity))
        reapply(context)
    }

    fun setTone(context: Context, bassDb: Double, trebleDb: Double) {
        save(context, selection(context).copy(bassDb = bassDb, trebleDb = trebleDb))
        reapply(context)
    }

    /** Pick a speaker profile: a calibration name, or null for "without calibration". */
    fun useProfile(context: Context, calibrationName: String?) {
        prefs(context).edit().putBoolean(KEY_USE_CAL, calibrationName != null).apply()
        val preset = calibrationName?.let { AutoVybzPresetStore.loadFull(context, it) }
        applyBase(context, preset)
    }

    /** Called whenever a calibration is applied from the wizard / profile list: that speaker is now in use. */
    internal fun onCalibrationApplied(context: Context) {
        prefs(context).edit().putBoolean(KEY_USE_CAL, true).apply()
    }

    /** Re-applies the current combination (after a style / tone change). */
    fun reapply(context: Context) {
        val preset = activeCalibrationName(context)?.let { AutoVybzPresetStore.loadFull(context, it) }
        applyBase(context, preset)
    }

    private fun applyBase(context: Context, preset: com.andrexthedev.autovybz.preset.model.AutoVybzPreset?) {
        // outdoor calibrations always arm the limiter (as in the profile list)
        val limiter = AutoVybzSettings(context).enableLimiterOnApply || preset?.measurement?.environment == "OUTDOOR"
        if (preset != null) AutoVybzPresetStore.apply(context, preset, limiter)
        else AutoVybzPresetStore.applyComposed(context, SoundComposer.compose(SoundComposer.styleOnlyBase(), selection(context)), limiter)
    }

    /** Tests: back to calibration-only, neutral style, no tone. */
    fun reset(context: Context) {
        prefs(context).edit().clear().commit()
    }
}
