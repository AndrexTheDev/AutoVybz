package com.andrexthedev.autovybz.measurement.eq

import com.andrexthedev.autovybz.measurement.dsp.FrequencyGrid

/**
 * Anti-clipping protection, two layers:
 *  1. Static pre-gain: the master level is lowered by the largest boost the
 *     filter chain produces anywhere in 20 Hz - 20 kHz (+ headroom), so a
 *     full-scale signal can never be pushed over 0 dBFS by the EQ. The policy
 *     (and its loudness make-up for cut-only corrections) lives in
 *     [PreampPlanner]; [AutoEqRenderer] and [com.andrexthedev.autovybz.sound.SoundComposer]
 *     both go through it.
 *  2. Dynamic safety net: Equalizer314's DynamicsProcessing limiter (and
 *     optionally a multiband "bass guard") catch what's left, e.g. inter-sample
 *     peaks or a user raising the pre-gain later.
 */
object AntiClipping {

    private val denseGrid = FrequencyGrid.log(pointsPerOctave = 24)

    fun maxBoostDb(bands: List<PeqBand>, sampleRate: Double = BiquadMath.DEFAULT_SAMPLE_RATE): Double {
        if (bands.isEmpty()) return 0.0
        val r = BiquadMath.Grid(denseGrid, sampleRate).response(bands)
        return r.maxOrNull() ?: 0.0
    }

    /** Settings for Equalizer314's limiter stage. */
    data class LimiterSettings(
        val thresholdDb: Float = -1.0f,
        val ratio: Float = 10f,
        val attackMs: Float = 1f,
        val releaseMs: Float = 60f,
        val postGainDb: Float = 0f,
    )

    /** Multiband compressor "bass guard" for strong bass boosts on small speakers. */
    data class BassGuard(
        val crossoverHz: Float = 150f,
        val thresholdDb: Float = -14f,
        val ratio: Float = 3f,
        val attackMs: Float = 5f,
        val releaseMs: Float = 120f,
        val kneeDb: Float = 6f,
    )

    val DEFAULT_LIMITER = LimiterSettings()

    /** A bass guard is recommended when the correction boosts below 150 Hz by more than 3 dB. */
    fun bassGuardFor(bands: List<PeqBand>, sampleRate: Double = BiquadMath.DEFAULT_SAMPLE_RATE): BassGuard? {
        val bassGrid = denseGrid.filter { it <= 150.0 }.toDoubleArray()
        if (bassGrid.isEmpty() || bands.isEmpty()) return null
        val r = BiquadMath.Grid(bassGrid, sampleRate).response(bands)
        return if ((r.maxOrNull() ?: 0.0) > 3.0) BassGuard() else null
    }
}
