package com.andrexthedev.autovybz.measurement.eq

import com.andrexthedev.autovybz.measurement.dsp.FrequencyGrid
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt

data class AutoEqSettings(
    /** 5 (fast) or 10 (max precision, default - uses the full Equalizer314 engine). */
    val bandCount: Int = 10,
    /** Largest allowed boost of the combined correction. Small speakers can't do much more. */
    val maxBoostDb: Double = 6.0,
    val maxCutDb: Double = 12.0,
    /** Upper correction limit: phone mics are unreliable above ~16 kHz. */
    val highLimitHz: Double = 16000.0,
    /** Detect the speaker's bass roll-off and never boost below it. */
    val autoLowLimit: Boolean = true,
    val manualLowLimitHz: Double = 40.0,
    /** Extra safety margin on top of the max filter boost for the pre-gain. */
    val headroomDb: Double = 0.5,
    val sampleRate: Double = BiquadMath.DEFAULT_SAMPLE_RATE,
) {
    init {
        require(bandCount in 1..16)
    }
}

class AutoEqResult(
    val bands: List<PeqBand>,
    /** Negative pre-gain that compensates the largest boost (anti-clipping). */
    val preampDb: Double,
    val lowLimitHz: Double,
    val highLimitHz: Double,
    val frequencies: DoubleArray,
    val measuredDb: DoubleArray,
    val targetDb: DoubleArray,
    /** target - measured, limited & faded outside the correctable range. */
    val desiredCorrectionDb: DoubleArray,
    /** What the fitted filters actually do. */
    val filterResponseDb: DoubleArray,
    /** measured + filters: predicted corrected response. */
    val predictedDb: DoubleArray,
    val rmsErrorBeforeDb: Double,
    val rmsErrorAfterDb: Double,
    val maxFilterBoostDb: Double,
)

/**
 * Target-curve inversion: turns a (smoothed, normalised) speaker measurement
 * into an EQ preset.
 *
 * 1. Determine the correctable range: from the speaker's own bass roll-off
 *    (never try to "EQ in" bass a speaker physically can't play) up to
 *    [AutoEqSettings.highLimitHz].
 * 2. Desired correction = target - measured, limited to +maxBoost / -maxCut
 *    (boost ramps in over the first octave above the roll-off - excursion
 *    guard) and faded to 0 dB over half an octave outside the range.
 * 3. Fit [AutoEqSettings.bandCount] biquads ([PeqFitter]).
 * 4. Anti-clipping: pre-gain = -(max combined boost + headroom).
 */
object AutoEqRenderer {

    fun render(
        frequencies: DoubleArray,
        measuredDb: DoubleArray,
        target: TargetCurve,
        settings: AutoEqSettings = AutoEqSettings(),
        /** Less smoothed curve (1/3 oct) for roll-off detection; defaults to [measuredDb]. */
        rollOffDb: DoubleArray = measuredDb,
    ): AutoEqResult {
        require(frequencies.size == measuredDb.size && frequencies.size > 8 && rollOffDb.size == measuredDb.size)
        val targetDb = target.sample(frequencies)

        val lowLimit = if (settings.autoLowLimit) detectLowLimit(frequencies, rollOffDb)
        else settings.manualLowLimitHz.coerceIn(20.0, 500.0)
        val highLimit = settings.highLimitHz.coerceIn(2000.0, 20000.0)

        val inRange = DoubleArray(frequencies.size) { rangeWeight(frequencies[it], lowLimit, highLimit) }
        val desired = DoubleArray(frequencies.size) { i ->
            val boostCap = settings.maxBoostDb * excursionGuard(frequencies[i], lowLimit)
            val d = (targetDb[i] - measuredDb[i]).coerceIn(-settings.maxCutDb, boostCap)
            d * inRange[i]
        }
        // Outside the range we still ask for "0 dB" (keeps filters from acting there), just with less weight.
        val weights = DoubleArray(frequencies.size) { 0.3 + 0.7 * inRange[it] }

        val grid = BiquadMath.Grid(frequencies, settings.sampleRate)
        val constraints = FitConstraints(
            maxBands = settings.bandCount,
            maxBoostDb = settings.maxBoostDb,
            maxCutDb = settings.maxCutDb,
            minFrequency = (lowLimit * 0.8).coerceAtLeast(20.0),
            maxFrequency = highLimit,
            minQ = 0.4,
            // 1/3-oct smoothed data has no features narrower than Q ~4; higher Q would chase
            // position-dependent comb ripple from reflections.
            maxQ = if (settings.bandCount <= 5) 2.5 else 4.0,
            allowShelves = true,
        )
        val bands = PeqFitter(grid, desired, weights, constraints).fit()
        val response = grid.response(bands)
        val predicted = DoubleArray(frequencies.size) { measuredDb[it] + response[it] }

        val maxBoost = AntiClipping.maxBoostDb(bands, settings.sampleRate)
        val preamp = AntiClipping.preampFor(maxBoost, settings.headroomDb)

        return AutoEqResult(
            bands = bands,
            preampDb = preamp,
            lowLimitHz = lowLimit,
            highLimitHz = highLimit,
            frequencies = frequencies,
            measuredDb = measuredDb,
            targetDb = targetDb,
            desiredCorrectionDb = desired,
            filterResponseDb = response,
            predictedDb = predicted,
            rmsErrorBeforeDb = rmsDeviation(frequencies, measuredDb, targetDb, lowLimit, highLimit),
            rmsErrorAfterDb = rmsDeviation(frequencies, predicted, targetDb, lowLimit, highLimit),
            maxFilterBoostDb = maxBoost,
        )
    }

    /**
     * Speaker bass extension: scanning down from 200 Hz, the first frequency
     * where the (1/3 octave) response falls 6 dB below the 300 Hz - 3 kHz
     * reference. Correction starts a little above it (x1.25) so the EQ never
     * fights the physical roll-off (driver excursion!). Clamped to 25..160 Hz.
     */
    fun detectLowLimit(frequencies: DoubleArray, measuredDb: DoubleArray): Double {
        val ref = FrequencyGrid.bandMean(frequencies, measuredDb, 300.0, 3000.0)
        var start = frequencies.indexOfLast { it <= 200.0 }
        if (start < 0) return 25.0
        while (start >= 0) {
            if (measuredDb[start] < ref - 6.0) return (frequencies[start] * 1.25).coerceIn(25.0, 160.0)
            start--
        }
        return 25.0
    }

    /**
     * Excursion guard: boosts just above the speaker's roll-off cost a lot of
     * cone excursion for little gain. The allowed boost ramps from 0 at the
     * low limit to 100 % one octave above it (cuts are always allowed).
     */
    internal fun excursionGuard(f: Double, lowLimit: Double): Double =
        (ln(f / lowLimit) / ln(2.0)).coerceIn(0.0, 1.0)

    /** 1 inside [lo, hi], raised-cosine fade to 0 over half an octave outside. */
    internal fun rangeWeight(f: Double, lo: Double, hi: Double): Double {
        val fadeOct = 0.5
        val octBelow = ln(lo / f) / ln(2.0)
        val octAbove = ln(f / hi) / ln(2.0)
        val o = maxOf(octBelow, octAbove)
        return when {
            o <= 0 -> 1.0
            o >= fadeOct -> 0.0
            else -> 0.5 + 0.5 * cos(PI * o / fadeOct)
        }
    }

    private fun rmsDeviation(f: DoubleArray, a: DoubleArray, b: DoubleArray, lo: Double, hi: Double): Double {
        // Compare shapes, not absolute level: remove the mean offset first.
        val idx = f.indices.filter { f[it] in lo..hi }
        if (idx.isEmpty()) return 0.0
        val offset = idx.sumOf { a[it] - b[it] } / idx.size
        return sqrt(idx.sumOf { val d = a[it] - b[it] - offset; d * d } / idx.size)
    }
}
