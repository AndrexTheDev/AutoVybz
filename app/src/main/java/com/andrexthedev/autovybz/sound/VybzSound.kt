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

    /** Engine-update coalescing, see [reapply]. */
    private const val COALESCE_MS = 150L
    /** Minimum gap between two engine writes (each is a DSP rebuild + binder calls on the service's main thread). */
    private const val MIN_SPACING_MS = 700L
    @Volatile private var lastApplyAt = 0L
    private val generation = java.util.concurrent.atomic.AtomicLong()
    private val applyLock = Any()

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
        // remembered right away, so the UI never shows a stale profile while the engine update is coalesced
        if (calibrationName != null) AutoVybzPresetStore.markActive(context, calibrationName)
        reapply(context)
    }

    /** Called whenever a calibration is applied from the wizard / profile list: that speaker is now in use. */
    internal fun onCalibrationApplied(context: Context) {
        prefs(context).edit().putBoolean(KEY_USE_CAL, true).apply()
    }

    /**
     * Re-applies the current combination (after a style / tone / profile change). Call off the main thread.
     *
     * Bursts are coalesced: every engine update makes the sound service rebuild its DSP chain on the main thread, so
     * ten quick taps on styles must not become ten rebuilds (a monkey run turned that into an ANR). Each call waits
     * [COALESCE_MS]; only the newest request of a burst writes the engine - it reads the preferences, so it already
     * contains every earlier change. Writes are also spaced by [MIN_SPACING_MS] (the last state always lands).
     */
    fun reapply(context: Context) {
        val mine = generation.incrementAndGet()
        Thread.sleep(COALESCE_MS)
        if (generation.get() != mine) return // a newer request will apply the same (newer) state
        synchronized(applyLock) {
            if (generation.get() != mine) return
            val wait = lastApplyAt + MIN_SPACING_MS - android.os.SystemClock.uptimeMillis()
            if (wait > 0) {
                Thread.sleep(wait)
                if (generation.get() != mine) return
            }
            lastApplyAt = android.os.SystemClock.uptimeMillis()
            val preset = activeCalibrationName(context)?.let { AutoVybzPresetStore.loadFull(context, it) }
            applyBase(context, preset)
        }
    }

    private fun applyBase(context: Context, preset: com.andrexthedev.autovybz.preset.model.AutoVybzPreset?) {
        // outdoor calibrations always arm the limiter (as in the profile list)
        val limiter = AutoVybzSettings(context).enableLimiterOnApply || preset?.measurement?.environment == "OUTDOOR"
        if (preset != null) AutoVybzPresetStore.apply(context, preset, limiter)
        else AutoVybzPresetStore.applyComposed(context, SoundComposer.compose(SoundComposer.styleOnlyBase(), selection(context)), limiter)
    }

    /** Tests: back to calibration-only, neutral style, no tone (synchronous on purpose: the next test reads it). */
    @android.annotation.SuppressLint("ApplySharedPref")
    fun reset(context: Context) {
        prefs(context).edit().clear().commit()
    }
}
