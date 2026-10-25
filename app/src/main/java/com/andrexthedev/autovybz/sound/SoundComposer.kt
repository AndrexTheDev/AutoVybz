package com.andrexthedev.autovybz.sound

import com.andrexthedev.autovybz.measurement.eq.AntiClipping
import com.andrexthedev.autovybz.measurement.eq.PeqBand
import com.andrexthedev.autovybz.measurement.eq.PeqType
import com.andrexthedev.autovybz.measurement.eq.TargetType
import com.andrexthedev.autovybz.preset.model.AutoVybzPreset
import kotlin.math.floor

/** What the user picked on the Sound / Studio screens. */
data class SoundSelection(
    val style: SoundStyle = SoundStyle.NEUTRAL,
    /** 0..100 % of the style curve. */
    val intensity: Int = SoundStyle.DEFAULT_INTENSITY,
    /** Studio "Bass" / "Höhen" in dB, each within ±[SoundComposer.MAX_TONE_DB]. */
    val bassDb: Double = 0.0,
    val trebleDb: Double = 0.0,
) {
    val isIdentity: Boolean get() = (style == SoundStyle.NEUTRAL || intensity == 0) && bassDb == 0.0 && trebleDb == 0.0
}

/**
 * Layers a [SoundSelection] on a calibration preset: calibration bands + style bands + tone shelves, with the
 * pre-gain recomputed by [AntiClipping] so the sum can never clip - keeping the calibration's own headroom margin.
 * Pure function (unit-tested in SoundComposerTest).
 */
object SoundComposer {
    const val MAX_TONE_DB = 6.0
    const val BASS_HZ = 105.0
    const val TREBLE_HZ = 8_000.0

    /** Same margin [com.andrexthedev.autovybz.measurement.eq.AutoEqSettings] uses for the pre-gain. */
    const val HEADROOM_DB = 0.5

    /** Name of the synthetic base when no calibration is used (style only). */
    const val STYLE_ONLY_NAME = "AutoVybz Sound"

    /** Engine limit (EqStateManager.MAX_BANDS default): 10 calibration + 4 style + 2 tone = 16. */
    const val ENGINE_MAX_BANDS = 16

    fun toneBands(selection: SoundSelection): List<PeqBand> = buildList {
        val bass = selection.bassDb.coerceIn(-MAX_TONE_DB, MAX_TONE_DB)
        val treble = selection.trebleDb.coerceIn(-MAX_TONE_DB, MAX_TONE_DB)
        if (kotlin.math.abs(bass) >= 0.05) add(PeqBand(PeqType.LOW_SHELF, BASS_HZ, bass, 0.71))
        if (kotlin.math.abs(treble) >= 0.05) add(PeqBand(PeqType.HIGH_SHELF, TREBLE_HZ, treble, 0.71))
    }

    /** Empty base for "no calibration": the style alone shapes the sound. */
    fun styleOnlyBase(now: Long = System.currentTimeMillis()) = AutoVybzPreset(
        name = STYLE_ONLY_NAME, createdAtMillis = now, deviceManufacturer = "", deviceModel = "", speakerName = null,
        target = TargetType.FLAT, bandCount = 0, bands = emptyList(), preampDb = 0.0,
        lowLimitHz = 20.0, highLimitHz = 20_000.0,
    )

    fun compose(base: AutoVybzPreset, selection: SoundSelection): AutoVybzPreset {
        if (selection.isIdentity) return base
        val extra = selection.style.bandsAt(selection.intensity) + toneBands(selection)
        val bands = (base.bands + extra).take(ENGINE_MAX_BANDS)
        // A style/tone layer must never undo the calibration's gain staging: only the clipping-safe part
        // is recomputed for the new peak, the calibration's own offset to it (loudness make-up, or the
        // extra safety margin of an imported/legacy preset) is carried over unchanged.
        val offset = preampOffsetDb(base)
        val peak = if (bands.isEmpty()) 0.0 else AntiClipping.maxBoostDb(bands)
        // One rounding step only: the clipping-safe part covers the new peak, the offset rides on top.
        val target = if (peak > 0.0) -(peak + HEADROOM_DB) + offset else offset
        return base.copy(
            bands = bands,
            preampDb = floor(target * 10.0 + 1e-9) / 10.0,
            bandCount = bands.size,
        )
    }

    /**
     * Part of [base]'s pre-gain that is loudness make-up (>= 0 dB) instead of clipping protection -
     * the inverse of [com.andrexthedev.autovybz.measurement.eq.PreampPlanner]: a calibration that only
     * cuts gets a positive pre-gain up to its allowance, and layering a style has to keep it.
     */
    fun makeUpDb(base: AutoVybzPreset): Double = preampOffsetDb(base).coerceAtLeast(0.0)

    /**
     * [AutoVybzPreset.preampDb] relative to the clipping-safe value of its own bands: `> 0` is the
     * loudness make-up, `< 0` an extra safety margin (a hand-made or imported preset that was more
     * conservative than `-(peak + headroom)`). Either way it must survive [compose].
     */
    private fun preampOffsetDb(base: AutoVybzPreset): Double {
        val peak = if (base.bands.isEmpty()) 0.0 else AntiClipping.maxBoostDb(base.bands)
        val clippingSafe = if (peak > 0.0) -(peak + HEADROOM_DB) else 0.0
        return base.preampDb - clippingSafe
    }
}
