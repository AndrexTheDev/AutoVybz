package com.andrexthedev.autovybz.measurement.eq

import com.andrexthedev.autovybz.measurement.dsp.FrequencyGrid
import kotlin.math.ln

/** Selectable target curves for speaker correction. */
enum class TargetType {
    /** Laien-Default: gentle bass lift, flat mids & highs. */
    SMART,
    /** Neutral / flat in-room response. */
    FLAT,
    /** Clearly lifted low end for small party speakers. */
    BASS_BOOST,
    /** Harman-style preferred in-room response (bass shelf + gentle downward tilt) / loudness. */
    HARMAN,
    /** User-defined shelves / tilt or an imported CSV. */
    CUSTOM,
}

/** Parameters for [TargetType.CUSTOM]. If [points] is non-empty it overrides the parametric shape. */
data class CustomTarget(
    val bassGainDb: Double = 4.0,
    val bassCornerHz: Double = 105.0,
    val trebleGainDb: Double = 0.0,
    val trebleCornerHz: Double = 8000.0,
    val tiltDbPerOctave: Double = 0.0,
    val points: List<Pair<Double, Double>> = emptyList(),
)

/**
 * A target curve in dB over frequency. Shapes are built from the same RBJ
 * shelves as the EQ itself, so they are smooth and reproducible.
 *
 * Note on [TargetType.HARMAN]: this is an *approximation* of the listener-preferred
 * in-room loudspeaker response described by Olive et al. (Harman, 2013): a
 * low-frequency shelf of roughly +4 dB below ~105 Hz plus a downward tilt of
 * about 0.8 dB/octave. It is not a copy of any proprietary dataset.
 */
class TargetCurve(val type: TargetType, val custom: CustomTarget = CustomTarget()) {

    fun levelAt(frequency: Double): Double = when (type) {
        TargetType.FLAT -> 0.0
        TargetType.SMART -> shelf(PeqType.LOW_SHELF, 105.0, 3.5, frequency)
        TargetType.BASS_BOOST -> shelf(PeqType.LOW_SHELF, 110.0, 6.5, frequency)
        TargetType.HARMAN -> shelf(PeqType.LOW_SHELF, 105.0, 4.0, frequency) + tilt(-0.8, frequency)
        TargetType.CUSTOM -> customLevel(frequency)
    }

    /** Target sampled on [grid], normalised so the 300 Hz - 3 kHz mean is 0 dB (like measurements). */
    fun sample(grid: DoubleArray): DoubleArray {
        val v = DoubleArray(grid.size) { levelAt(grid[it]) }
        val mean = FrequencyGrid.bandMean(grid, v, 300.0, 3000.0)
        for (i in v.indices) v[i] -= mean
        return v
    }

    private fun customLevel(f: Double): Double {
        if (custom.points.size >= 2) {
            val sorted = custom.points.sortedBy { it.first }
            val xs = DoubleArray(sorted.size) { sorted[it].first }
            val ys = DoubleArray(sorted.size) { sorted[it].second }
            return FrequencyGrid.interpolateLog(xs, ys, f)
        }
        return shelf(PeqType.LOW_SHELF, custom.bassCornerHz, custom.bassGainDb, f) +
            shelf(PeqType.HIGH_SHELF, custom.trebleCornerHz, custom.trebleGainDb, f) +
            tilt(custom.tiltDbPerOctave, f)
    }

    private fun shelf(type: PeqType, fc: Double, gain: Double, f: Double): Double =
        if (gain == 0.0) 0.0 else BiquadMath.magnitudeDb(PeqBand(type, fc, gain, 0.707), f)

    /** Linear tilt in dB/octave, 0 dB at 1 kHz. */
    private fun tilt(dbPerOct: Double, f: Double): Double = dbPerOct * ln(f / 1000.0) / ln(2.0)
}
