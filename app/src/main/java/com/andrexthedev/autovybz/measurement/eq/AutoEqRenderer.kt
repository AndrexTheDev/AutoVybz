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
    /**
     * SNR gating (only when an SNR curve is passed to [AutoEqRenderer.render]): below [minSnrDb] the
     * measurement is dominated by background noise and is not corrected at all; full correction from
     * [fullSnrDb] on, linear in between.
     */
    val minSnrDb: Double = 10.0,
    val fullSnrDb: Double = 16.0,
    /**
     * "Cut narrow, boost broad": if set, boosts are limited to the deficit of the response smoothed over
     * this many octaves. Narrow dips (ground-reflection / interference notches - position-dependent and
     * unfillable) get no boost, while broad deficits and all peaks are still corrected.
     */
    val broadBoostOctaves: Double? = null,
    /** Width of the boost ramp above the bass roll-off (excursion guard), octaves. */
    val excursionGuardOctaves: Double = 1.0,
    /**
     * Where the bass roll-off scan starts ([AutoEqRenderer.detectLowLimit]) and its upper clamp.
     * The defaults suit normal external speakers (roll-off below 200 Hz); tiny drivers need a
     * wider scan or the detection saturates at the clamp (phone speakers roll off at 400-900 Hz).
     */
    val lowLimitScanHz: Double = 200.0,
    val lowLimitMaxHz: Double = 160.0,
    /**
     * Add a 2nd-order protective high pass at the measured bass roll-off (-6 dB point): signal the
     * driver cannot reproduce only costs excursion, rattling and intermodulation distortion.
     * Removing it is pure win for weak / tiny speakers (more clean headroom). Consumes one of the
     * [bandCount] bands; skipped when the speaker plays (almost) full-range anyway.
     */
    val protectiveHighPass: Boolean = false,
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
    /** Frequency ranges (Hz) inside the correction range that were skipped because of background noise. */
    val noiseGatedRanges: List<Pair<Double, Double>> = emptyList(),
    /** Corner frequency of the protective high pass in [bands], if one was added. */
    val highPassHz: Double? = null,
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
 *
 * With [AutoEqSettings.protectiveHighPass] one of the fitted bands is replaced by a
 * high pass at the measured -6 dB roll-off point, so weak drivers stop wasting their
 * excursion (and the listener's patience) on bass they cannot play.
 */
object AutoEqRenderer {

    /** [detectLowLimit] reports the measured -6 dB point times this safety factor. */
    const val LOW_LIMIT_SAFETY = 1.25

    /** Below this corner a protective pass is pointless (the speaker plays (almost) full-range). */
    const val MIN_PROTECTIVE_HP_HZ = 35.0
    const val MAX_PROTECTIVE_HP_HZ = 1000.0

    /** Butterworth, 12 dB/oct. */
    const val PROTECTIVE_HP_Q = 0.707

    /** Protective-pass corner for a detected [lowLimitHz] (the -6 dB point), or null if pointless. */
    internal fun protectiveHighPassHz(lowLimitHz: Double): Double? =
        (lowLimitHz / LOW_LIMIT_SAFETY).takeIf { it >= MIN_PROTECTIVE_HP_HZ }?.coerceAtMost(MAX_PROTECTIVE_HP_HZ)

    fun render(
        frequencies: DoubleArray,
        measuredDb: DoubleArray,
        target: TargetCurve,
        settings: AutoEqSettings = AutoEqSettings(),
        /** Less smoothed curve (1/3 oct) for roll-off detection; defaults to [measuredDb]. */
        rollOffDb: DoubleArray = measuredDb,
        /** Per-frequency signal-to-noise ratio of the measurement (dB); enables SNR gating. */
        snrDb: DoubleArray? = null,
    ): AutoEqResult {
        require(frequencies.size == measuredDb.size && frequencies.size > 8 && rollOffDb.size == measuredDb.size)
        require(snrDb == null || snrDb.size == measuredDb.size)
        require(measuredDb.all { it.isFinite() } && rollOffDb.all { it.isFinite() }) { "measurement contains NaN/Inf" }
        val targetDb = target.sample(frequencies)
        // Shapes only: the analyzer already normalises to 0 dB in 300 Hz - 3 kHz (then this is a no-op), but
        // the EQ must never depend on the absolute recording level (AutoEqPropertyTest).
        val measuredDb = normalise(frequencies, measuredDb)
        val rollOffDb = normalise(frequencies, rollOffDb)

        val lowLimit = if (settings.autoLowLimit) {
            detectLowLimit(frequencies, rollOffDb, settings.lowLimitScanHz, maxHz = settings.lowLimitMaxHz)
        } else settings.manualLowLimitHz.coerceIn(20.0, 500.0)
        val highLimit = settings.highLimitHz.coerceIn(2000.0, 20000.0)
        val highPassHz = if (settings.protectiveHighPass) protectiveHighPassHz(lowLimit) else null

        val inRange = DoubleArray(frequencies.size) { rangeWeight(frequencies[it], lowLimit, highLimit) }
        val reliable = DoubleArray(frequencies.size) { i ->
            snrDb?.let { reliability(it[i], settings.minSnrDb, settings.fullSnrDb) } ?: 1.0
        }
        val broad = settings.broadBoostOctaves?.let { medianLog(frequencies, measuredDb, it) }
        val desired = DoubleArray(frequencies.size) { i ->
            val boostCap = settings.maxBoostDb * excursionGuard(frequencies[i], lowLimit, settings.excursionGuardOctaves)
            var raw = targetDb[i] - measuredDb[i]
            if (broad != null && raw > 0) raw = minOf(raw, maxOf(0.0, targetDb[i] - broad[i]))
            raw.coerceIn(-settings.maxCutDb, boostCap) * inRange[i] * reliable[i]
        }
        // Outside the range (and where noise hides the speaker) we still ask for "0 dB" - keeps filters
        // from acting there - just with less weight.
        val weights = DoubleArray(frequencies.size) { 0.3 + 0.7 * inRange[it] * reliable[it] }

        val grid = BiquadMath.Grid(frequencies, settings.sampleRate)
        val constraints = FitConstraints(
            // The protective pass takes one slot, so presets stay within the promised band count.
            maxBands = if (highPassHz != null) (settings.bandCount - 1).coerceAtLeast(1) else settings.bandCount,
            maxBoostDb = settings.maxBoostDb,
            maxCutDb = settings.maxCutDb,
            minFrequency = (lowLimit * 0.8).coerceAtLeast(20.0),
            maxFrequency = highLimit,
            minQ = 0.4,
            // 1/3-oct smoothed data has no features narrower than Q ~4; higher Q would chase
            // position-dependent comb ripple from reflections.
            maxQ = if (settings.bandCount <= 5) 2.5 else 4.0,
            allowShelves = true,
            noBoostBelowHz = lowLimit / 2,
        )
        val fitted = PeqFitter(grid, desired, weights, constraints).fit()
        val bands = if (highPassHz != null) {
            (fitted + PeqBand(PeqType.HIGH_PASS, highPassHz, 0.0, PROTECTIVE_HP_Q)).sortedBy { it.frequency }
        } else fitted
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
            noiseGatedRanges = gatedRanges(frequencies, reliable, lowLimit, highLimit),
            highPassHz = highPassHz,
        )
    }

    private fun normalise(f: DoubleArray, v: DoubleArray): DoubleArray {
        val ref = FrequencyGrid.bandMean(f, v, 300.0, 3000.0)
        return if (ref == 0.0 || !ref.isFinite()) v else DoubleArray(v.size) { v[it] - ref }
    }

    internal fun reliability(snr: Double, min: Double, full: Double): Double =
        ((snr - min) / (full - min).coerceAtLeast(1e-6)).coerceIn(0.0, 1.0)

    /**
     * Running median in dB over +-octaves/2 on a log grid. Unlike a mean, a median ignores notches
     * narrower than half the window completely (they cannot shift it), while broad deficits pass through.
     */
    internal fun medianLog(f: DoubleArray, v: DoubleArray, octaves: Double): DoubleArray {
        val factor = Math.pow(2.0, octaves / 2.0)
        var lo = 0
        var hi = 0
        return DoubleArray(v.size) { i ->
            while (lo < f.size && f[lo] < f[i] / factor) lo++
            while (hi < f.size && f[hi] <= f[i] * factor) hi++
            val window = v.copyOfRange(lo, maxOf(hi, lo + 1)).sorted()
            val m = window.size
            if (m % 2 == 1) window[m / 2] else (window[m / 2 - 1] + window[m / 2]) / 2
        }
    }

    /** Contiguous ranges inside [lo, hi] with reliability < 0.5, at least 1/6 octave wide. */
    private fun gatedRanges(f: DoubleArray, reliable: DoubleArray, lo: Double, hi: Double): List<Pair<Double, Double>> {
        val out = mutableListOf<Pair<Double, Double>>()
        var start = -1
        for (i in f.indices) {
            val gated = f[i] in lo..hi && reliable[i] < 0.5
            if (gated && start < 0) start = i
            if ((!gated || i == f.lastIndex) && start >= 0) {
                val end = if (gated) i else i - 1
                if (ln(f[end] / f[start]) / ln(2.0) >= 1.0 / 6.0) out += f[start] to f[end]
                start = -1
            }
        }
        return out
    }

    /**
     * Speaker bass extension: the frequency where the (1/3 octave) response falls 6 dB below the
     * 300 Hz - 3 kHz reference. Correction starts a little above it ([LOW_LIMIT_SAFETY]) so the EQ
     * never fights the physical roll-off (driver excursion!).
     *
     * The crossing is the top of the below-threshold run that reaches the bottom of the grid -
     * an isolated room dip above the true roll-off must not be mistaken for it (this matters once
     * [scanFromHz] reaches into the midrange for tiny speakers). If measurement noise lifts the
     * deepest bins above the threshold, the classic scan-down from [scanFromHz] is the fallback.
     * Defaults (scan from 200 Hz, clamp 25..160 Hz) preserve the original external-speaker values.
     */
    fun detectLowLimit(
        frequencies: DoubleArray,
        measuredDb: DoubleArray,
        scanFromHz: Double = 200.0,
        minHz: Double = 25.0,
        maxHz: Double = 160.0,
    ): Double {
        val ref = FrequencyGrid.bandMean(frequencies, measuredDb, 300.0, 3000.0)
        val threshold = ref - 6.0
        val scanIdx = frequencies.indexOfLast { it <= scanFromHz }
        if (scanIdx < 0) return minHz
        var runTop = -1
        while (runTop + 1 <= scanIdx && measuredDb[runTop + 1] < threshold) runTop++
        if (runTop >= 0) return (frequencies[runTop] * LOW_LIMIT_SAFETY).coerceIn(minHz, maxHz)
        var i = scanIdx
        while (i >= 0) {
            if (measuredDb[i] < threshold) return (frequencies[i] * LOW_LIMIT_SAFETY).coerceIn(minHz, maxHz)
            i--
        }
        return minHz
    }

    /**
     * Excursion guard: boosts just above the speaker's roll-off cost a lot of
     * cone excursion for little gain. The allowed boost ramps from 0 at the
     * low limit to 100 % one octave (outdoors / party: 1.5) above it (cuts are always allowed).
     */
    internal fun excursionGuard(f: Double, lowLimit: Double, octaves: Double = 1.0): Double =
        (ln(f / lowLimit) / ln(2.0) / octaves.coerceAtLeast(0.1)).coerceIn(0.0, 1.0)

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
