package com.andrexthedev.autovybz.measurement.eq

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * Upper bound on the number of residual extrema offered to the optimiser per greedy step.
 * Three covers the classic "boom + honk + presence" shape of a small speaker; more only cost CPU.
 */
private const val MAX_PEAK_CANDIDATES = 3

/** A candidate must be at least this large (dB) to be worth one of the [FitConstraints.maxBands]. */
private const val CANDIDATE_MIN_DB = 0.5

/** ... and this large to be offered as an *alternative* to the worst residual point. */
private const val CANDIDATE_SECONDARY_MIN_DB = 1.0

/** Bands closer than this (octaves) are duplicates and are merged after the refinement passes. */
private const val MERGE_DISTANCE_OCT = 0.28

/** ... as long as dropping one does not increase the loss by more than this fraction ... */
private const val MERGE_LOSS_TOLERANCE = 0.001

/** ... nor make the worst un-fitted gap more than this much bigger (dB). */
private const val MERGE_MAX_ERROR_GROWTH_DB = 0.05

/** Greedy acceptance: a band has to buy this fraction of the *current* error, but at least ... */
private const val GREEDY_MIN_GAIN_RATIO = 0.015

/** ... this absolute amount (dB^2), so the fit stops before chasing ripple. */
private const val GREEDY_MIN_GAIN_FLOOR_DB2 = 0.005

/** Constraints for [PeqFitter]. */
data class FitConstraints(
    val maxBands: Int = 10,
    val maxBoostDb: Double = 6.0,
    val maxCutDb: Double = 12.0,
    val minFrequency: Double = 20.0,
    val maxFrequency: Double = 16000.0,
    val minQ: Double = 0.4,
    val maxQ: Double = 4.0,
    val allowShelves: Boolean = true,
    /**
     * Excursion protection: below this frequency (well under the speaker's roll-off) the combined filters
     * must not boost - a low shelf would otherwise keep lifting down to DC, costing cone excursion for no
     * audible gain. 0 = off.
     */
    val noBoostBelowHz: Double = 0.0,
)

/**
 * Fits up to [FitConstraints.maxBands] biquads to a desired correction curve.
 *
 * Strategy (speaker-oriented, inspired by AutoEq's greedy approach):
 * 1. Greedy: in each step look at the remaining error and propose candidates -
 *    peaking filters at the [MAX_PEAK_CANDIDATES] largest weighted deviations (Q
 *    from their width) and, if allowed, low/high shelves for broad trends. Each
 *    candidate is locally optimised (Nelder-Mead over log f, gain, log Q) with all
 *    previous bands fixed; the best one is kept. Stops early when nothing improves
 *    enough *relative to what is still left* (not relative to the initial error).
 * 2. Refinement: every band is re-optimised against the others (2 passes).
 * 3. Clean-up: inaudible bands (|gain| < 0.2 dB) are dropped.
 *
 * The loss is a weighted MSE plus soft penalties that keep the *combined*
 * response within the boost / cut limits (protects small speakers from
 * aggressive boosts and the DSP from clipping).
 */
class PeqFitter(
    private val grid: BiquadMath.Grid,
    private val desired: DoubleArray,
    private val weights: DoubleArray,
    private val constraints: FitConstraints,
) {
    private val n = grid.frequencies.size
    private val weightSum = weights.sum().coerceAtLeast(1e-9)

    init {
        require(desired.size == n && weights.size == n)
    }

    fun fit(): List<PeqBand> {
        val bands = mutableListOf<PeqBand>()
        val fixed = DoubleArray(n)
        var currentLoss = loss(fixed, null)

        // A real loop (not `repeat`) so an early stop can `break` - and still run the refinement.
        var step = 0
        while (step < constraints.maxBands) {
            val residual = DoubleArray(n) { desired[it] - fixed[it] }
            // A band must buy at least 1.5 % of the *remaining* error (or 0.005 dB^2) - otherwise it is
            // fitting measurement noise / reflection ripple, not the speaker. Measured: with the
            // threshold frozen at 1.5 % of the *initial* loss (0.096 dB^2) the greedy stopped after 4 of
            // 10 bands while the next band still bought 0.060 dB^2 and 2.3 dB of the plan stayed
            // unfitted (587 Hz broad lift, mini box). Following the remaining error fixed that.
            val minGain = maxOf(currentLoss * GREEDY_MIN_GAIN_RATIO, GREEDY_MIN_GAIN_FLOOR_DB2)

            var best: PeqBand? = null
            var bestLoss = currentLoss
            for (candidate in candidates(residual)) {
                val opt = optimise(candidate, fixed)
                val l = loss(fixed, opt)
                if (l < bestLoss - minGain) { bestLoss = l; best = opt }
            }
            val chosen = best ?: break
            bands += chosen
            grid.response(chosen, fixed, accumulate = true)
            currentLoss = bestLoss
            step++
        }

        // Refinement passes: re-fit each band with all others fixed.
        repeat(2) {
            for (i in bands.indices) {
                val others = DoubleArray(n)
                for (j in bands.indices) if (j != i) grid.response(bands[j], others, accumulate = true)
                val opt = optimise(bands[i], others)
                if (loss(others, opt) < loss(others, bands[i])) bands[i] = opt
            }
        }
        return finish(mergeDuplicates(bands))
    }

    /**
     * Drops the weaker of two bands closer than [MERGE_DISTANCE_OCT] when doing so costs almost nothing.
     *
     * The refinement moves neighbouring bands towards each other (mini box: PK 123 Hz +3.7 and PK 133 Hz
     * +0.9 - two biquads doing one biquad's work). Both guards compare against the *original* fit, so a
     * series of individually harmless merges cannot add up to a visibly worse correction.
     */
    private fun mergeDuplicates(bands: List<PeqBand>): List<PeqBand> {
        var out = bands.toMutableList()
        val baselineMax = maxError(out)
        var changed = true
        while (changed && out.size > 1) {
            changed = false
            val order = out.indices.sortedBy { out[it].frequency }
            for (k in 0 until order.size - 1) {
                val i = order[k]
                val j = order[k + 1]
                val a = out[i]
                val b = out[j]
                if (abs(log2(b.frequency / a.frequency)) >= MERGE_DISTANCE_OCT) continue
                val weaker = if (abs(a.gainDb) < abs(b.gainDb)) i else j
                val trial = out.filterIndexed { index, _ -> index != weaker }
                val trialLoss = lossOf(trial)
                if (trialLoss > lossOf(out) * (1.0 + MERGE_LOSS_TOLERANCE)) continue
                if (maxError(trial) > baselineMax + MERGE_MAX_ERROR_GROWTH_DB) continue
                out = trial.toMutableList()
                changed = true
                break
            }
        }
        return out
    }

    private fun responseOf(bands: List<PeqBand>): DoubleArray {
        val r = DoubleArray(n)
        for (b in bands) grid.response(b, r, accumulate = true)
        return r
    }

    private fun lossOf(bands: List<PeqBand>): Double = loss(responseOf(bands), null)

    private fun maxError(bands: List<PeqBand>): Double {
        val r = responseOf(bands)
        var m = 0.0
        for (i in 0 until n) m = max(m, abs(desired[i] - r[i]))
        return m
    }

    private fun finish(bands: List<PeqBand>): List<PeqBand> =
        bands.filter { abs(it.gainDb) >= 0.2 }.sortedBy { it.frequency }

    /** Weighted MSE of (desired - fixed - candidate) + limit penalties. */
    fun loss(fixed: DoubleArray, candidate: PeqBand?): Double {
        val cand = if (candidate != null) DoubleArray(n).also { grid.response(candidate, it) } else null
        var err = 0.0
        var penalty = 0.0
        val boostLimit = constraints.maxBoostDb + 0.25
        val cutLimit = -(constraints.maxCutDb + 0.5)
        for (i in 0 until n) {
            val total = fixed[i] + (cand?.get(i) ?: 0.0)
            val e = desired[i] - total
            err += weights[i] * e * e
            if (total > boostLimit) penalty += (total - boostLimit).pow(2)
            if (total > 0.5 && grid.frequencies[i] < constraints.noBoostBelowHz) penalty += (total - 0.5).pow(2)
            if (total < cutLimit) penalty += (cutLimit - total).pow(2)
        }
        return err / weightSum + 10.0 * penalty / n
    }

    // ---- candidates ---------------------------------------------------------

    private fun candidates(residual: DoubleArray): List<PeqBand> {
        val out = mutableListOf<PeqBand>()
        val f = grid.frequencies
        // Up to [MAX_PEAK_CANDIDATES] *distinct* residual extrema (worst first, at least a third of an
        // octave apart). Offering only the single worst point made the greedy follow one lobe at a
        // time; several candidates let the optimiser pick the lobe that actually pays off.
        val picked = mutableListOf<Double>()
        while (picked.size < MAX_PEAK_CANDIDATES) {
            var idx = -1
            var best = 0.0
            for (i in 0 until n) {
                if (f[i] < constraints.minFrequency || f[i] > constraints.maxFrequency) continue
                if (picked.any { abs(log2(f[i] / it)) < 1.0 / 3.0 }) continue
                val v = weights[i] * abs(residual[i])
                if (v > best) { best = v; idx = i }
            }
            if (idx < 0) break
            // The first candidate only has to be audible; the alternatives must be worth the extra
            // Nelder-Mead run (two more candidates triple the CPU of a greedy step).
            val gate = if (picked.isEmpty()) CANDIDATE_MIN_DB else CANDIDATE_SECONDARY_MIN_DB
            if (abs(residual[idx]) <= gate) break
            picked += f[idx]
            val g0 = residual[idx]
            var l = idx
            while (l > 0 && sign(residual[l - 1]) == sign(g0) && abs(residual[l - 1]) > abs(g0) / 2) l--
            var r = idx
            while (r < n - 1 && sign(residual[r + 1]) == sign(g0) && abs(residual[r + 1]) > abs(g0) / 2) r++
            val bw = max(log2(f[r] / f[l]), 1.0 / 6.0)
            val q = sqrt(2.0.pow(bw)) / (2.0.pow(bw) - 1.0)
            out += clamp(PeqBand(PeqType.PEAK, f[idx], g0, q))
        }
        if (constraints.allowShelves) {
            // LOW_SHELF weighted-mean range: [minFrequency, max(150 Hz, minFrequency + 100 Hz)].
            // The upper bound 150 Hz is the classic threshold, but for high-roll-off classes
            // (RESCUE ~280 Hz, DEVICE ~560 Hz) minFrequency > 150 would make the range invalid
            // (lo > hi) and the shelf would never be proposed. Widening to at least 100 Hz
            // above minFrequency keeps the range valid and lets the shelf correct broad bass
            // trends in the correction band above the roll-off.
            val lowLo = constraints.minFrequency
            val lowHi = max(150.0, lowLo + 100.0)
            val low = weightedMean(residual, lowLo, lowHi)
            if (abs(low) > 0.5) out += clamp(PeqBand(PeqType.LOW_SHELF, 120.0, low, 0.7))
            val high = weightedMean(residual, 5000.0, constraints.maxFrequency)
            if (abs(high) > 0.5) out += clamp(PeqBand(PeqType.HIGH_SHELF, 6000.0, high, 0.7))
        }
        return out
    }

    private fun weightedMean(v: DoubleArray, lo: Double, hi: Double): Double {
        var s = 0.0
        var w = 0.0
        val f = grid.frequencies
        for (i in 0 until n) if (f[i] in lo..hi) { s += weights[i] * v[i]; w += weights[i] }
        return if (w > 0) s / w else 0.0
    }

    // ---- constrained local optimisation ----------------------------------------

    private fun freqBounds(type: PeqType): Pair<Double, Double> = when (type) {
        PeqType.PEAK -> constraints.minFrequency to constraints.maxFrequency
        // The shelf corner range is capped at 400 Hz for normal speakers, but a tiny driver's
        // correction band can start above that (minFrequency up to ~720 Hz) - never invert the
        // bounds, clamp() would throw on coerceIn(lo > hi).
        PeqType.LOW_SHELF -> max(constraints.minFrequency, 30.0).let { lo -> lo to max(400.0, lo) }
        PeqType.HIGH_SHELF -> min(2000.0, constraints.maxFrequency) to max(2500.0, constraints.maxFrequency)
        // Never fitted (candidates are peaks/shelves only); bounds exist for exhaustiveness.
        PeqType.HIGH_PASS -> constraints.minFrequency to max(1000.0, constraints.minFrequency)
    }

    private fun qBounds(type: PeqType): Pair<Double, Double> = when (type) {
        PeqType.PEAK -> constraints.minQ to constraints.maxQ
        else -> 0.5 to 1.0
    }

    private fun clamp(b: PeqBand): PeqBand {
        val (fLo, fHi) = freqBounds(b.type)
        val (qLo, qHi) = qBounds(b.type)
        return b.copy(
            frequency = b.frequency.coerceIn(fLo, fHi),
            gainDb = b.gainDb.coerceIn(-constraints.maxCutDb, constraints.maxBoostDb),
            q = b.q.coerceIn(qLo, qHi),
        )
    }

    private fun toVector(b: PeqBand) = doubleArrayOf(log2(b.frequency), b.gainDb, ln(b.q))
    private fun fromVector(type: PeqType, x: DoubleArray) =
        clamp(PeqBand(type, 2.0.pow(x[0]), x[1], exp(x[2])))

    private fun optimise(start: PeqBand, fixed: DoubleArray): PeqBand {
        val type = start.type
        val x = NelderMead.minimize(
            f = { v -> loss(fixed, fromVector(type, v)) },
            x0 = toVector(start),
            step = doubleArrayOf(0.25, 1.5, 0.35),
            maxIterations = 160,
        )
        return fromVector(type, x)
    }
}

/** Minimal Nelder-Mead simplex optimiser (unconstrained; callers clamp). */
internal object NelderMead {
    fun minimize(f: (DoubleArray) -> Double, x0: DoubleArray, step: DoubleArray, maxIterations: Int): DoubleArray {
        val dim = x0.size
        val pts = Array(dim + 1) { i -> x0.copyOf().also { if (i > 0) it[i - 1] += step[i - 1] } }
        val vals = DoubleArray(dim + 1) { f(pts[it]) }
        repeat(maxIterations) {
            val order = (0..dim).sortedBy { vals[it] }
            val sortedPts = order.map { pts[it] }
            val sortedVals = order.map { vals[it] }
            for (i in 0..dim) { pts[i] = sortedPts[i]; vals[i] = sortedVals[i] }
            if (abs(vals[dim] - vals[0]) < 1e-7) return pts[0]

            val centroid = DoubleArray(dim)
            for (i in 0 until dim) for (d in 0 until dim) centroid[d] += pts[i][d] / dim
            fun along(t: Double) = DoubleArray(dim) { d -> centroid[d] + t * (pts[dim][d] - centroid[d]) }

            val xr = along(-1.0); val fr = f(xr)
            when {
                fr < vals[0] -> {
                    val xe = along(-2.0); val fe = f(xe)
                    if (fe < fr) { pts[dim] = xe; vals[dim] = fe } else { pts[dim] = xr; vals[dim] = fr }
                }
                fr < vals[dim - 1] -> { pts[dim] = xr; vals[dim] = fr }
                else -> {
                    val xc = if (fr < vals[dim]) along(-0.5) else along(0.5)
                    val fc = f(xc)
                    if (fc < minOf(fr, vals[dim])) {
                        pts[dim] = xc; vals[dim] = fc
                    } else {
                        for (i in 1..dim) {
                            for (d in 0 until dim) pts[i][d] = pts[0][d] + 0.5 * (pts[i][d] - pts[0][d])
                            vals[i] = f(pts[i])
                        }
                    }
                }
            }
        }
        var bi = 0
        for (i in 1..dim) if (vals[i] < vals[bi]) bi = i
        return pts[bi]
    }
}
