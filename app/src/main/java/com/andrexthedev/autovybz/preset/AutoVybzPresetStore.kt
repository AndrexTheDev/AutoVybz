package com.andrexthedev.autovybz.preset

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.andrexthedev.autovybz.export.PresetNaming
import com.andrexthedev.autovybz.preset.model.AutoVybzPreset
import com.bearinmind.equalizer314.audio.EqService
import com.bearinmind.equalizer314.dsp.BiquadFilter
import com.bearinmind.equalizer314.dsp.ParametricEqualizer
import com.bearinmind.equalizer314.state.EqPreferencesManager
import java.io.File

/**
 * Bridges AutoVybz calibrations into the Equalizer314 engine:
 *  - saves into the shared `custom_presets` pool (same JSON as AutoEqActivity), so
 *    the calibration shows up in the normal preset list / device bindings
 *  - keeps the full measurement as `files/autovybz/presets/<name>.avz.json`
 *  - applies it as the active EQ (10-band PEQ + pre-gain + safety limiter)
 */
object AutoVybzPresetStore {

    private const val TAG = "AutoVybzPresetStore"
    private const val POOL_PREFS = "custom_presets"
    private const val POOL_NAMES = "preset_names"
    private const val AVZ_PREFS = "autovybz_state"
    private const val KEY_PENDING_APPLY = "pending_apply"
    private const val KEY_LAST_PRESET = "last_preset"

    fun existingNames(context: Context): Set<String> =
        context.getSharedPreferences(POOL_PREFS, Context.MODE_PRIVATE).getStringSet(POOL_NAMES, emptySet()) ?: emptySet()

    fun defaultName(context: Context, speakerName: String?): String =
        PresetNaming.defaultName(speakerName?.takeIf { it.isNotBlank() } ?: Build.MODEL, existing = existingNames(context))

    private fun dir(context: Context) = File(context.filesDir, "autovybz/presets").apply { mkdirs() }

    private fun fileFor(context: Context, name: String) =
        File(dir(context), name.replace(Regex("[^A-Za-z0-9._+-]"), "_") + AutoVybzPreset.FILE_EXTENSION)

    /** Saves (or overwrites) [preset] under its name. Returns the stored name. */
    fun save(context: Context, preset: AutoVybzPreset): String {
        require(PresetNaming.isValid(preset.name)) { "invalid preset name" }
        val prefs = context.getSharedPreferences(POOL_PREFS, Context.MODE_PRIVATE)
        val names = (prefs.getStringSet(POOL_NAMES, emptySet()) ?: emptySet()).toMutableSet()
        names += preset.name
        prefs.edit()
            .putString("preset_${preset.name}", preset.toPoolJson())
            .putStringSet(POOL_NAMES, names)
            .apply()
        runCatching { fileFor(context, preset.name).writeText(preset.toInternalJson()) }
            .onFailure { Log.w(TAG, "could not write measurement file", it) }
        return preset.name
    }

    fun loadFull(context: Context, name: String): AutoVybzPreset? = runCatching {
        val f = fileFor(context, name)
        if (f.exists()) AutoVybzPresetCodec.fromInternalJson(f.readText()) else null
    }.getOrNull()

    /** Calibrations saved by AutoVybz (newest first). */
    fun listCalibrations(context: Context): List<AutoVybzPreset> =
        (dir(context).listFiles { f -> f.name.endsWith(AutoVybzPreset.FILE_EXTENSION) } ?: emptyArray())
            .mapNotNull { runCatching { AutoVybzPresetCodec.fromInternalJson(it.readText()) }.getOrNull() }
            .sortedByDescending { it.createdAtMillis }

    fun delete(context: Context, name: String) {
        val prefs = context.getSharedPreferences(POOL_PREFS, Context.MODE_PRIVATE)
        val names = (prefs.getStringSet(POOL_NAMES, emptySet()) ?: emptySet()).toMutableSet()
        names -= name
        prefs.edit().remove("preset_$name").putStringSet(POOL_NAMES, names).apply()
        fileFor(context, name).delete()
    }

    /**
     * Makes [preset] the active EQ, exactly like AutoEqActivity.applyProfile():
     * single channel (CSE off), Simple EQ off so all bands are visible, pre-gain
     * from the anti-clipping stage, and the Equalizer314 limiter armed as a
     * second safety net. Running DynamicsProcessing is updated live.
     */
    fun apply(context: Context, preset: AutoVybzPreset, enableLimiter: Boolean = true) {
        val p = EqPreferencesManager(context)
        val eq = ParametricEqualizer()
        eq.clearBands()
        for (b in preset.bands) {
            val type = BiquadFilter.FilterType.valueOf(AutoVybzPreset.filterTypeName(b.type))
            eq.addBand(b.frequency.toFloat(), b.gainDb.toFloat(), type, b.q)
        }
        eq.isEnabled = true
        p.saveState(eq, (0 until eq.getBandCount()).toList())
        p.savePreampGain(preset.preampDb.toFloat())
        p.savePresetName(preset.name)
        p.saveChannelSideEqEnabled(false)
        p.clearLeftRightBands()
        p.saveSimpleEqEnabled(false)
        p.saveAutoEqName("")
        if (enableLimiter) {
            p.saveLimiterEnabled(true)
            p.saveLimiterThreshold(-1f)
            p.saveLimiterRatio(10f)
            p.saveLimiterAttack(1f)
            p.saveLimiterRelease(60f)
            p.saveLimiterPostGain(0f)
        }
        context.getSharedPreferences(AVZ_PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_PENDING_APPLY, true)
            .putString(KEY_LAST_PRESET, preset.name)
            .apply()
        if (EqService.isDpRunning) {
            runCatching {
                context.startService(Intent(context, EqService::class.java).setAction(EqService.ACTION_APPLY_PERSISTED_EQ))
            }.onFailure { Log.w(TAG, "live apply failed", it) }
        }
    }

    /** Called from MainActivity.onResume: true once after a wizard apply, so the Pro EQ screen reloads. */
    fun consumePendingApply(context: Context): Boolean {
        val prefs = context.getSharedPreferences(AVZ_PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_PENDING_APPLY, false)) return false
        prefs.edit().putBoolean(KEY_PENDING_APPLY, false).apply()
        return true
    }

    fun lastAppliedName(context: Context): String? =
        context.getSharedPreferences(AVZ_PREFS, Context.MODE_PRIVATE).getString(KEY_LAST_PRESET, null)
}
