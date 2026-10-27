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
    private const val KEY_CALIBRATION_NAMES = "calibration_names"
    private const val KEY_CALIBRATION_INDEX = "calibration_index"
    /** Bump when the way names are derived from files changes; forces a one-time re-index. */
    private const val CALIBRATION_INDEX_VERSION = 1
    /** The writer puts format, formatVersion and name first (see [AutoVybzPreset.toInternalJson]). */
    private const val HEAD_LINES = 4

    fun existingNames(context: Context): Set<String> =
        context.getSharedPreferences(POOL_PREFS, Context.MODE_PRIVATE).getStringSet(POOL_NAMES, emptySet()) ?: emptySet()

    /* ---- calibration scope -------------------------------------------------- */

    /**
     * Names of the calibrations **AutoVybz** created (the `.avz.json` files), as opposed to every preset of
     * the shared Equalizer314 pool that [existingNames] reports.
     *
     * The distinction matters in two places:
     *  * the Free tier limit ("one saved profile") must only count profiles the user can actually see and
     *    delete in the AutoVybz screens. Counting the pool blocked saving a calibration for anyone who had
     *    ever saved an AutoEQ import or a studio preset - with a message about a profile limit while the
     *    Sound screen showed no profiles at all;
     *  * the "first start" decision (wizard vs. Sound screen) must not depend on unrelated presets.
     *
     * The index lives in preferences, so the check that runs on every keystroke of the result screen never
     * touches the preset files. It is rebuilt once (migration from existing files) and maintained by
     * [save] / [delete].
     */
    fun calibrationNames(context: Context): Set<String> {
        ensureCalibrationIndex(context)
        val stored = context.getSharedPreferences(AVZ_PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_CALIBRATION_NAMES, emptySet()) ?: emptySet()
        return stored.toSet()
    }

    /** True when no AutoVybz calibration exists yet (the wizard is then the natural start screen). */
    fun isFirstCalibration(context: Context): Boolean = calibrationNames(context).isEmpty()

    private fun ensureCalibrationIndex(context: Context) {
        val prefs = context.getSharedPreferences(AVZ_PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_CALIBRATION_INDEX, 0) >= CALIBRATION_INDEX_VERSION) return
        val onDisk = runCatching {
            dir(context).listFiles { f -> f.name.endsWith(AutoVybzPreset.FILE_EXTENSION) }.orEmpty()
                .mapNotNull { storedName(it) }
        }.getOrDefault(emptyList())
        val merged = (prefs.getStringSet(KEY_CALIBRATION_NAMES, emptySet()) ?: emptySet()) + onDisk
        prefs.edit()
            .putStringSet(KEY_CALIBRATION_NAMES, merged.toMutableSet())
            .putInt(KEY_CALIBRATION_INDEX, CALIBRATION_INDEX_VERSION)
            .apply()
    }

    /**
     * Name of a stored preset file, read from its first few lines; full parse only as a fallback.
     *
     * Deliberately line-by-line and not `readLines()`: the measurement arrays sit on single lines of several
     * hundred kilobytes, so "read everything, keep four lines" would defeat the point of the head parse.
     */
    private fun storedName(file: File): String? = runCatching {
        val head = file.bufferedReader().use { reader ->
            val lines = ArrayList<String>(HEAD_LINES)
            while (lines.size < HEAD_LINES) lines += (reader.readLine() ?: break)
            lines.joinToString("\n")
        }
        PresetFiles.nameFromHead(head) ?: AutoVybzPresetCodec.fromInternalJson(file.readText()).name
    }.getOrNull()

    private fun updateCalibrationIndex(context: Context, name: String, present: Boolean) {
        // Adding before the one-time migration ran would hide the files that already exist, so make sure
        // the index reflects the directory first.
        if (present) ensureCalibrationIndex(context)
        // Read-modify-write on its own instance: the set returned by getStringSet must not be mutated.
        val prefs = context.getSharedPreferences(AVZ_PREFS, Context.MODE_PRIVATE)
        val names = (prefs.getStringSet(KEY_CALIBRATION_NAMES, emptySet()) ?: emptySet()).toMutableSet()
        if (present) names += name else names -= name
        prefs.edit()
            .putStringSet(KEY_CALIBRATION_NAMES, names)
            .putInt(KEY_CALIBRATION_INDEX, CALIBRATION_INDEX_VERSION) // a write makes a later scan unnecessary
            .apply()
    }

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

    /**
     * Saves (or overwrites) [preset] under its name. Returns the stored name.
     *
     * Throws when the measurement file cannot be written (no space, revoked storage): the AutoVybz screens
     * list calibrations from those files, so a silently missing file would show up as "saved" and then be
     * invisible - and the Free tier would count a profile nobody can apply. The file is therefore written
     * *before* the shared engine pool is updated.
     */
    fun save(context: Context, preset: AutoVybzPreset): String {
        require(PresetNaming.isValid(preset.name)) { "invalid preset name" }
        fileFor(context, preset.name).writeText(preset.toInternalJson())
        runCatching { legacyFileFor(context, preset.name)?.delete() } // migrated
            .onFailure { Log.w(TAG, "could not remove the migrated measurement file", it) }
        val prefs = context.getSharedPreferences(POOL_PREFS, Context.MODE_PRIVATE)
        val names = (prefs.getStringSet(POOL_NAMES, emptySet()) ?: emptySet()).toMutableSet()
        names += preset.name
        prefs.edit()
            .putString("preset_${preset.name}", preset.toPoolJson())
            .putStringSet(POOL_NAMES, names)
            .apply()
        updateCalibrationIndex(context, preset.name, present = true)
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
        updateCalibrationIndex(context, name, present = false)
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
        // One gain authority only: AutoVybz already balances the calibration (clipping-safe pre-gain plus
        // loudness make-up, see PreampPlanner). The engine's own auto-gain shifts every band down by the
        // largest boost *again* — with both active a device-speaker calibration lost up to 9 dB of level
        // (i.e. all the quiet detail) for nothing.
        p.saveAutoGainEnabled(false)
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

    /** Marks [name] as the speaker profile in use (before its engine update has been written). */
    fun markActive(context: Context, name: String) {
        context.getSharedPreferences(AVZ_PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LAST_PRESET, name).apply()
    }

    fun lastAppliedName(context: Context): String? =
        context.getSharedPreferences(AVZ_PREFS, Context.MODE_PRIVATE).getString(KEY_LAST_PRESET, null)
}
