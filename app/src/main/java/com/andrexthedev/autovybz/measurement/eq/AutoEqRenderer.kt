package com.andrexthedev.autovybz.measurement.eq

import com.andrexthedev.autovybz.measurement.dsp.FrequencyGrid
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt

/** Default start of the per-class high-frequency boost cap region (see [AutoEqSettings.highBoostCapDb]). */
const val HIGH_BOOST_CAP_START_HZ = 6000.0

data class AutoEqSettings(
    /** 5 (fast) or 10 (max precision, default - uses the full Equalizer314 engine). */
    val bandCount: Int = 10,
    /** Largest allowed boost of the combined correction. Small speakers can't do much more. */
    val maxBoostDb: Double = 6.0,
    val maxCutDb: Double = 12.0,
    /**
     * Fraction of a measured excess that is actually cut (0.1-1.0, default 1.0 = full correction).
     * Near-field self-measurements (a phone measuring its own speakers at centimetres) overstate peaks -
     * and every dB cut in the 1-4 kHz presence band costs several dB of perceived loudness. 0.75 keeps
     * the tonal correction while the sound stays as loud as before (see [PreampPlanner]).
     */
    val cutTrust: Double = 1.0,
    /**
     * Frequency-dependent cutTrust (DEVICE class): below [cutTrustLowHz] the near-field self-measurement
     * overstates the bass heavily (pressure zone + chassis vibration), so cuts there are scaled by
     * [cutTrustLow] instead of [cutTrust]. Above [cutTrustLowHz] the direct sound is more trustworthy
     * and [cutTrust] applies. 0 = disabled (uniform cutTrust).
     */
    val cutTrustLowHz: Double = 0.0,
    /** Low-band cutTrust fraction (0.1-1.0); used below [cutTrustLowHz] when [cutTrustLowHz] > 0. */
    val cutTrustLow: Double = 1.0,
    /** Upper correction limit: phone mics are unreliable above ~16 kHz. */
    val highLimitHz: Double = 16000.0,
    /** Detect the speaker's bass roll-off and never boost below it. */
    val autoLowLimit: Boolean = true,
    val manualLowLimitHz: Double = 40.0,
    /** Extra safety margin on top of the max filter boost for the pre-gain. */
    val headroomDb: Double = 0.5,
    /**
     * Compensate the *perceived* level loss of a correction, so a calibration makes the speaker better,
     * not quieter. Never lowers the pre-gain: a correction that gets louder is left alone. With
     * [preampHeadroomAllowanceDb] > 0 (limiter armed) the pre-gain is set loudness-neutral; with 0 it is
     * the legacy clipping-safe value. See [PreampPlanner]. On by default.
     */
    val loudnessMakeUp: Boolean = true,
    /** Upper bound of the loudness make-up (dB). */
    val maxMakeUpDb: Double = PreampPlanner.MAX_MAKE_UP_DB,
    /**
     * Static overshoot the pre-gain may leave when the engine limiter is armed: with a limiter the
     * pre-gain is loudness-neutral and may sit up to this much above the clipping-safe value (the
     * limiter is the peak protection). 0 (the default) keeps the legacy clipping-safe value exactly.
     * Only [com.andrexthedev.autovybz.measurement.CorrectionPlanner] sets this, and only for
     * calibrations whose preset forces the limiter on.
     */
    val preampHeadroomAllowanceDb: Double = 0.0,
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
     *
     * [protectiveHighPassHz] overrides the corner: the -6 dB point of a tiny driver sits in the
     * midrange (a phone speaker rolls off at 400-900 Hz), and cutting the bass the driver *does*
     * play would make the calibration sound thin. Such classes protect the sub-bass only.
     */
    val protectiveHighPass: Boolean = false,
    /** Fixed corner of the protective high pass in Hz; `null` derives it from the measured roll-off. */
    val protectiveHighPassHz: Double? = null,
    /** Absolute boost cap (dB) above [highBoostCapStartHz]; `null` = [maxBoostDb]. */
    val highBoostCapDb: Double? = null,
    /** Start of the [highBoostCapDb] region (Hz). */
    val highBoostCapStartHz: Double = HIGH_BOOST_CAP_START_HZ,
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
    /** Part of [preampDb] that compensates the perceived level loss of the correction (>= 0 dB). */
    val makeUpDb: Double = 0.0,
    /** Estimated perceived level change of the correction alone (dB, negative = quieter). */
    val loudnessDeltaDb: Double = 0.0,
    /** The classic anti-clipping pre-gain for this correction (<= 0); [preampDb] - [clippingSafeDb] = [makeUpDb]. */
    val clippingSafeDb: Double = 0.0,
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
 * 4. Gain staging ([PreampPlanner]): the clipping-safe pre-gain is `-(max combined boost + headroom)`,
 *    plus a loudness make-up that undoes the *perceived* level loss of a correction that only cuts (a
 *    device-speaker calibration must sound better, not quieter). Bounded by [AutoEqSettings.maxMakeUpDb]
 *    and, only while the engine limiter is armed, by [AutoEqSettings.preampHeadroomAllowanceDb].
 *
 * With [AutoEqSettings.protectiveHighPass] one of the fitted bands is replaced by a
 * high pass at the measured -6 dB roll-off point (or [AutoEqSettings.protectiveHighPassHz]
 * when set), so weak drivers stop wasting their excursion (and the listener's patience) on
 * bass they cannot play. The fitter is told about the pass (its slope inside the correction
 * band is compensated in the desired curve), so the band edge is not cut twice.
 */
object AutoEqRenderer {

    /** [detectLowLimit] reports the measured -6 dB point times this safety factor. */
    const val LOW_LIMIT_SAFETY = 1.25

    /** Below this corner a protective pass is pointless (the speaker plays (almost) full-range). */
    const val MIN_PROTECTIVE_HP_HZ = 35.0
    const val MAX_PROTECTIVE_HP_HZ = 1000.0

    /** Butterworth, 12 dB/oct. */
    const val PROTECTIVE_HP_Q = 0.707

    /**
     * Largest part of the protective pass' slope the fitter is asked to compensate (dB). Deeper than this
     * the pass is protection; see [render].
     */
    const val HP_COMPENSATION_LIMIT_DB = 6.0

    /** Protective-pass corner for a detected [lowLimitHz] (the -6 dB point), or null if pointless. */
    internal fun protectiveHighPassHz(lowLimitHz: Double): Double? =
        (lowLimitHz / LOW_LIMIT_SAFETY).takeIf { it >= MIN_PROTECTIVE_HP_HZ }?.coerceAtMost(MAX_PROTECTIVE_HP_HZ)

    /**
     * The corner of the protective pass actually used, or null when no pass should be added.
     *
     * [AutoEqSettings.protectiveHighPassHz] is a per-class *cap*, not a
     * target: the speaker classes are picked by the user ("weak speaker"), but only the measurement knows
     * where the driver really stops playing. The fixed 150 Hz of [com.andrexthedev.autovybz.measurement.SpeakerClass.RESCUE]
     * is right for a cheap box that rolls off at 200 Hz - and catastrophic for a party box that was
     * (wrongly) calibrated in that class while measuring down to 55 Hz: the pass then removes 1.5 octaves
     * of working bass (-17 dB at 40 Hz in the live-music probe, tools/probe/). Clamping the cap to the measured
     * -6 dB point keeps the protection where the driver really cannot play, and drops the pass entirely
     * when the speaker plays down to [MIN_PROTECTIVE_HP_HZ].
     */
    internal fun protectivePassFor(lowLimitHz: Double, classCornerHz: Double?): Double? {
        val rollOff = lowLimitHz / LOW_LIMIT_SAFETY
        val corner = if (classCornerHz == null) protectiveHighPassHz(lowLimitHz) else minOf(classCornerHz, rollOff)
        return corner?.takeIf { it >= MIN_PROTECTIVE_HP_HZ }?.coerceAtMost(MAX_PROTECTIVE_HP_HZ)
    }

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
        val highPassHz = if (settings.protectiveHighPass) {
            protectivePassFor(lowLimit, settings.protectiveHighPassHz)
        } else null

        val inRange = DoubleArray(frequencies.size) { rangeWeight(frequencies[it], lowLimit, highLimit) }
        val reliable = DoubleArray(frequencies.size) { i ->
            snrDb?.let { reliability(it[i], settings.minSnrDb, settings.fullSnrDb) } ?: 1.0
        }
        val broad = settings.broadBoostOctaves?.let { medianLog(frequencies, measuredDb, it) }
        // [cutTrust] scales cuts (a near-field self-measurement overstates peaks - correct only the
        // trusted fraction, the rest is what the manufacturer's tuning leaves in); boosts are already
        // limited by the excursion guard and the broad-only rule, and a missing treble is usually real
        // (a phone speaker really does roll off up there). The scaled cut is still bounded by
        // [cutLimit] = maxCutDb * cutTrust, so an excess beyond the limit cannot escape it.
        val cutTrust = settings.cutTrust.coerceIn(0.1, 1.0)
        val cutTrustLow = settings.cutTrustLow.coerceIn(0.1, 1.0)
        val desired = DoubleArray(frequencies.size) { i ->
            // Two independent boost limits: the excursion guard (scaled by the distance above the
            // roll-off) and, above [AutoEqSettings.highBoostCapStartHz], the per-class absolute cap
            // (the self-measurement of a tiny driver is untrustworthy up there - see DEVICE).
            val regionCap = if (frequencies[i] >= settings.highBoostCapStartHz) {
                minOf(settings.maxBoostDb, settings.highBoostCapDb ?: settings.maxBoostDb)
            } else settings.maxBoostDb
            val boostCap = minOf(
                settings.maxBoostDb * excursionGuard(frequencies[i], lowLimit, settings.excursionGuardOctaves),
                regionCap,
            )
            var raw = targetDb[i] - measuredDb[i]
            if (broad != null && raw > 0) raw = minOf(raw, maxOf(0.0, targetDb[i] - broad[i]))
            // Frequency-dependent cutTrust: below [AutoEqSettings.cutTrustLowHz] the near-field
            // self-measurement overstates the bass heavily (pressure zone + chassis vibration), so
            // cuts there are scaled by the lower trust fraction. The cut limit follows the same
            // frequency-dependent scaling so a deep cut cannot escape the lower-trust band.
            val ct = if (settings.cutTrustLowHz > 0.0 && frequencies[i] < settings.cutTrustLowHz) cutTrustLow else cutTrust
            val scaled = if (raw < 0.0) raw * ct else raw
            scaled.coerceIn(-settings.maxCutDb * ct, boostCap) * inRange[i] * reliable[i]
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
        val highPass = highPassHz?.let { PeqBand(PeqType.HIGH_PASS, it, 0.0, PROTECTIVE_HP_Q) }
        // The fitter must see the world *with* the protective pass in place: inside the correction
        // band its slope is part of the desired curve (otherwise the low edge ends up cut twice -
        // by the fit and by the pass, up to ~1.5 dB at the low limit). Outside the band nothing is
        // compensated: there the cut is the protection. The excursion guard was already applied to
        // [desired], so compensating the slope can never increase the electrical signal beyond
        // what the guard budgeted.
        val fitDesired = if (highPass != null) {
            val hp = DoubleArray(frequencies.size).also { grid.response(highPass, it) }
            // Only the *band edge* of the pass is compensated. Deeper into its stop band the pass is
            // protection, not a correction the fitter could or should undo - asking for it produces a
            // plan nobody can realise (the beta probe measured a 20 dB gap between plan and filters,
            // 2026-10-28) and burns the band budget on filters that fight the pass.
            DoubleArray(frequencies.size) { i ->
                desired[i] - hp[i].coerceIn(-HP_COMPENSATION_LIMIT_DB, HP_COMPENSATION_LIMIT_DB) * inRange[i] * reliable[i]
            }
        } else desired
        val fitted = PeqFitter(grid, fitDesired, weights, constraints).fit()
        val bands = if (highPass != null) {
            (fitted + highPass).sortedBy { it.frequency }
        } else fitted
        val response = grid.response(bands)
        val predicted = DoubleArray(frequencies.size) { measuredDb[it] + response[it] }

        val maxBoost = AntiClipping.maxBoostDb(bands, settings.sampleRate)
        // Clipping-safe pre-gain + loudness compensation (see PreampPlanner): a calibration that mostly
        // cuts must not make the speaker quieter instead of better.
        val preampPlan = PreampPlanner.plan(
            frequencies = frequencies,
            measuredDb = measuredDb,
            correctionDb = response,
            headroomDb = settings.headroomDb,
            maxMakeUpDb = settings.maxMakeUpDb,
            headroomAllowanceDb = settings.preampHeadroomAllowanceDb,
            loudnessMakeUp = settings.loudnessMakeUp,
            // The analytic maximum of the filter chain (24 points/octave), not just the render grid:
            // the clipping-safe value must cover the whole 20 Hz-20 kHz range.
            peakDb = maxBoost,
        )

        return AutoEqResult(
            bands = bands,
            preampDb = preampPlan.preampDb,
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
            makeUpDb = preampPlan.makeUpDb,
            loudnessDeltaDb = preampPlan.loudnessDeltaDb,
            clippingSafeDb = preampPlan.clippingSafeDb,
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
     * Speaker bass extension: the frequency where the (1/3 octave) response falls 6 dB below its
     * midrange reference. Correction starts a little above it ([LOW_LIMIT_SAFETY]) so the EQ
     * never fights the physical roll-off (driver excursion!).
     *
     * The crossing is the top of the below-threshold run that reaches the bottom of the grid -
     * an isolated room dip above the true roll-off must not be mistaken for it (this matters once
     * [scanFromHz] reaches into the midrange for tiny speakers). If measurement noise lifts the
     * deepest bins above the threshold, the classic scan-down from [scanFromHz] is the fallback.
     *
     * The reference band starts at max(300 Hz, 0.75 * [scanFromHz]): with the wide tiny-speaker
     * scan the classic 300 Hz edge would sit *inside* the roll-off it is supposed to find, which
     * depresses the reference and biases the result low (phone speaker: 519 Hz detected instead
     * of the true 665 Hz; with the scaled reference: 644 Hz). The defaults (scan from 200 Hz,
     * clamp 25..160 Hz) preserve the original external-speaker values exactly.
     */
    fun detectLowLimit(
        frequencies: DoubleArray,
        measuredDb: DoubleArray,
        scanFromHz: Double = 200.0,
        minHz: Double = 25.0,
        maxHz: Double = 160.0,
    ): Double {
        val ref = FrequencyGrid.bandMean(frequencies, measuredDb, maxOf(300.0, scanFromHz * 0.75), 3000.0)
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
    internal fun excursionGuard(f: Double, lowLimit: Double, octaves: Double = 1.0): Double {
        val t = (ln(f / lowLimit) / ln(2.0) / octaves.coerceAtLeast(0.1)).coerceIn(0.0, 1.0)
        // Smoothstep (S-curve) instead of a linear ramp: the boost ramps more gradually near the
        // roll-off (where excursion risk is highest) and more aggressively further away. The endpoints
        // and midpoint are identical to the linear ramp, so existing behaviour is preserved there.
        return t * t * (3.0 - 2.0 * t)
    }

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
