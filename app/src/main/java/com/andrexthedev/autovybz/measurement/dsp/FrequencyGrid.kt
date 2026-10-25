package com.andrexthedev.autovybz.measurement.dsp

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/** Logarithmic frequency grids and log-frequency interpolation helpers. */
object FrequencyGrid {

    const val MIN_HZ = 20.0
    const val MAX_HZ = 20000.0

    /** Points from [fMin] to [fMax] (inclusive) with [pointsPerOctave] spacing. */
    fun log(fMin: Double = MIN_HZ, fMax: Double = MAX_HZ, pointsPerOctave: Int = 48): DoubleArray {
        require(fMin > 0 && fMax > fMin && pointsPerOctave > 0)
        val octaves = ln(fMax / fMin) / ln(2.0)
        val count = ceil(octaves * pointsPerOctave).toInt() + 1
        return DoubleArray(count) { i -> (fMin * 2.0.pow(i.toDouble() / pointsPerOctave)).coerceAtMost(fMax) }
    }

    /** Linear interpolation of [ys] over log10([xs]); clamps outside the table. [xs] ascending. */
    fun interpolateLog(xs: DoubleArray, ys: DoubleArray, x: Double): Double {
        require(xs.size == ys.size && xs.isNotEmpty())
        if (x <= xs.first()) return ys.first()
        if (x >= xs.last()) return ys.last()
        var lo = 0
        var hi = xs.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (xs[mid] <= x) lo = mid else hi = mid
        }
        val t = (log10(x) - log10(xs[lo])) / (log10(xs[hi]) - log10(xs[lo]))
        return ys[lo] + t * (ys[hi] - ys[lo])
    }

    /** Resample a (frequency, value) table onto [grid]. */
    fun resample(xs: DoubleArray, ys: DoubleArray, grid: DoubleArray): DoubleArray =
        DoubleArray(grid.size) { interpolateLog(xs, ys, grid[it]) }

    /** Mean of [values] over grid points whose frequency lies in [loHz, hiHz]. */
    fun bandMean(grid: DoubleArray, values: DoubleArray, loHz: Double, hiHz: Double): Double {
        var sum = 0.0
        var n = 0
        for (i in grid.indices) if (grid[i] in loHz..hiHz) { sum += values[i]; n++ }
        return if (n == 0) 0.0 else sum / n
    }
}

/**
 * Frequency-dependent ("dynamic") fractional-octave smoothing profile.
 *
 * Below [bassCornerHz] the window is [bassOctaves] wide (strong smoothing, so
 * narrow room modes are not over-corrected); above [detailStartHz] it is
 * [detailOctaves] wide (classic 1/3 octave); in between the width is
 * interpolated on a log-frequency axis.
 */
data class SmoothingProfile(
    val bassOctaves: Double = 1.0,
    val bassCornerHz: Double = 80.0,
    val detailOctaves: Double = 1.0 / 3.0,
    val detailStartHz: Double = 320.0,
) {
    init {
        require(bassOctaves > 0 && detailOctaves > 0 && detailStartHz > bassCornerHz)
    }

    fun widthOctavesAt(frequencyHz: Double): Double = when {
        frequencyHz <= bassCornerHz -> bassOctaves
        frequencyHz >= detailStartHz -> detailOctaves
        else -> {
            val t = ln(frequencyHz / bassCornerHz) / ln(detailStartHz / bassCornerHz)
            bassOctaves + t * (detailOctaves - bassOctaves)
        }
    }

    companion object {
        /** Default AutoVybz profile: 1/1 octave in the sub-bass -> 1/3 octave from ~320 Hz. */
        val DYNAMIC = SmoothingProfile()
        /** Gentler variant for well-behaved rooms (1/2 -> 1/6 octave). */
        val FINE = SmoothingProfile(bassOctaves = 0.5, detailOctaves = 1.0 / 6.0)
        /** Heavy variant for very boomy rooms (1.5 -> 1/2 octave). */
        val STRONG = SmoothingProfile(bassOctaves = 1.5, detailOctaves = 0.5)

        /**
         * Near-field self-measurement (a phone or tablet measuring its own speakers with its own
         * microphone at centimetres). The opposite trade-off of a room measurement:
         *
         * * **Bass is unreliable** - the microphone sits inside the radiator's pressure zone and picks up
         *   structure-borne chassis vibration, so below ~400 Hz one octave is smoothed (no over-correction
         *   of a measurement artefact).
         * * **Above ~700 Hz the direct sound is *more* trustworthy than any room measurement** - no room, no
         *   reflections, just the driver a few centimetres away. 1/3 octave (instead of the heavy 1/2
         *   octave used for boomy rooms) keeps the narrow midrange/treble features that carry detail, so
         *   the correction can actually restore them instead of averaging them away.
         */
        val DEVICE = SmoothingProfile(bassOctaves = 1.0, bassCornerHz = 400.0, detailOctaves = 1.0 / 3.0, detailStartHz = 700.0)
        /** Fixed 1/3 octave - used for roll-off detection and quality metrics. */
        val THIRD_OCTAVE = SmoothingProfile(bassOctaves = 1.0 / 3, bassCornerHz = 20.0, detailOctaves = 1.0 / 3, detailStartHz = 21.0)
        /** Effectively unsmoothed display resolution (1/48 octave everywhere). */
        val RAW = SmoothingProfile(bassOctaves = 1.0 / 48, bassCornerHz = 20.0, detailOctaves = 1.0 / 48, detailStartHz = 21.0)
    }
}

/**
 * Maps a linear-frequency power spectrum (FFT bins) onto a log grid, averaging
 * *power* inside a fractional-octave window centred on each grid frequency.
 * Combined resampling + smoothing in one O(bins + grid) pass using prefix sums.
 */
object SpectrumBinning {

    /**
     * @param validLowHz / validHighHz only bins inside this range carry signal
     *   (e.g. the sweep band). Windows are clipped to it, so band edges are not
     *   dragged down by empty bins (or blown up by numerically tiny references).
     */
    fun toGrid(
        power: DoubleArray,
        binHz: Double,
        grid: DoubleArray,
        validLowHz: Double = 0.0,
        validHighHz: Double = Double.MAX_VALUE,
        widthOctaves: (Double) -> Double,
    ): DoubleArray {
        require(power.size >= 3 && binHz > 0)
        val prefix = DoubleArray(power.size + 1)
        for (i in power.indices) prefix[i + 1] = prefix[i] + power[i]
        val firstValid = max(1, ceil(validLowHz / binHz).toInt()).coerceAtMost(power.size - 2)
        val lastValid = minOf(power.size - 1, floor(minOf(validHighHz, 1e12) / binHz).toInt()).coerceAtLeast(firstValid + 1)
        return DoubleArray(grid.size) { gi ->
            val f = grid[gi]
            val half = widthOctaves(f) / 2.0
            val lo = f * 2.0.pow(-half)
            val hi = f * 2.0.pow(half)
            val kLo = max(firstValid, ceil(lo / binHz).toInt())
            val kHi = minOf(lastValid, floor(hi / binHz).toInt())
            if (kHi >= kLo) {
                (prefix[kHi + 1] - prefix[kLo]) / (kHi - kLo + 1)
            } else {
                // Window narrower than the bin spacing (or outside the valid band): interpolate neighbours.
                val pos = (f / binHz).coerceIn(firstValid.toDouble(), lastValid.toDouble())
                val k0 = floor(pos).toInt().coerceAtMost(lastValid - 1)
                val t = pos - k0
                power[k0] * (1 - t) + power[k0 + 1] * t
            }
        }
    }

    fun toGrid(
        power: DoubleArray,
        binHz: Double,
        grid: DoubleArray,
        profile: SmoothingProfile,
        validLowHz: Double = 0.0,
        validHighHz: Double = Double.MAX_VALUE,
    ): DoubleArray = toGrid(power, binHz, grid, validLowHz, validHighHz) { profile.widthOctavesAt(it) }
}

/** 10*log10 with a floor to keep silent bins finite. */
fun powerToDb(p: Double): Double = 10.0 * log10(max(p, 1e-20))

fun DoubleArray.powerToDb(): DoubleArray = DoubleArray(size) { powerToDb(this[it]) }
