package com.andrexthedev.autovybz.preset

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.andrexthedev.autovybz.export.PresetNaming
import com.andrexthedev.autovybz.measurement.audio.EqBypass
import com.andrexthedev.autovybz.preset.model.AutoVybzPreset
import com.andrexthedev.autovybz.sound.SoundComposer
import com.andrexthedev.autovybz.sound.VybzSound
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

    private fun fileFor(context: Context, name: String) = File(dir(context), PresetFiles.fileName(name))

    /** Pre-0.1.1 file of exactly this preset (old names could collide, so the stored name is checked). */
    private fun legacyFileFor(context: Context, name: String): File? {
        val f = File(dir(context), PresetFiles.legacyFileName(name))
        if (f == fileFor(context, name) || !f.exists()) return null
        val stored = runCatching { AutoVybzPresetCodec.fromInternalJson(f.readText()).name }.getOrNull()
        return f.takeIf { stored == name }
    }

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
        runCatching {
            fileFor(context, preset.name).writeText(preset.toInternalJson())
            legacyFileFor(context, preset.name)?.delete() // migrated
        }.onFailure { Log.w(TAG, "could not write measurement file", it) }
        return preset.name
    }

    fun loadFull(context: Context, name: String): AutoVybzPreset? = runCatching {
        val f = fileFor(context, name).takeIf { it.exists() } ?: legacyFileFor(context, name)
        f?.let { AutoVybzPresetCodec.fromInternalJson(it.readText()) }
    }.getOrNull()

    /** Calibrations saved by AutoVybz (newest first). */
    fun listCalibrations(context: Context): List<AutoVybzPreset> =
        (dir(context).listFiles { f -> f.name.endsWith(AutoVybzPreset.FILE_EXTENSION) } ?: emptyArray())
            .mapNotNull { runCatching { AutoVybzPresetCodec.fromInternalJson(it.readText()) }.getOrNull() }
            .sortedByDescending { it.createdAtMillis }
            .distinctBy { it.name }

    fun delete(context: Context, name: String) {
        val prefs = context.getSharedPreferences(POOL_PREFS, Context.MODE_PRIVATE)
        val names = (prefs.getStringSet(POOL_NAMES, emptySet()) ?: emptySet()).toMutableSet()
        names -= name
        prefs.edit().remove("preset_$name").putStringSet(POOL_NAMES, names).apply()
        legacyFileFor(context, name)?.delete()
        fileFor(context, name).delete()
        // the deleted speaker is no longer "in use" (Sound screen, A/B switch)
        val state = context.getSharedPreferences(AVZ_PREFS, Context.MODE_PRIVATE)
        if (state.getString(KEY_LAST_PRESET, null) == name) state.edit().remove(KEY_LAST_PRESET).apply()
    }

    /**
     * Makes [preset] the active EQ, exactly like AutoEqActivity.applyProfile():
     * single channel (CSE off), Simple EQ off so all bands are visible, pre-gain
     * from the anti-clipping stage, and the Equalizer314 limiter armed as a
     * second safety net. Running DynamicsProcessing is updated live.
     */
    fun apply(context: Context, preset: AutoVybzPreset, enableLimiter: Boolean = true) {
        // the user's sound style + Studio tone are layered on top of the calibration (identity when neutral)
        VybzSound.onCalibrationApplied(context)
        applyComposed(context, SoundComposer.compose(preset, VybzSound.selection(context)), enableLimiter)
    }

    /**
     * Writes an already composed preset into the engine. A style-only base ([SoundComposer.STYLE_ONLY_NAME])
     * does not replace the remembered calibration, so switching back to the speaker profile is lossless.
     */
    fun applyComposed(context: Context, preset: AutoVybzPreset, enableLimiter: Boolean) {
        val styleOnly = preset.name == SoundComposer.STYLE_ONLY_NAME
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
        val state = context.getSharedPreferences(AVZ_PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_PENDING_APPLY, true)
        if (!styleOnly) state.putString(KEY_LAST_PRESET, preset.name)
        state.apply()
        runCatching {
            if (EqService.isDpRunning) {
                // Live update of the running DynamicsProcessing chain.
                context.startService(Intent(context, EqService::class.java).setAction(EqService.ACTION_APPLY_PERSISTED_EQ))
            } else {
                // "Activate" = make it audible: headless start loads exactly the prefs written above.
                EqBypass.resume(context)
            }
        }.onFailure { Log.w(TAG, "live apply failed", it) }
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
