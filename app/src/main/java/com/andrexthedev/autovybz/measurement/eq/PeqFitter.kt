package com.andrexthedev.autovybz.measurement.eq

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sqrt

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
)

/**
 * Fits up to [FitConstraints.maxBands] biquads to a desired correction curve.
 *
 * Strategy (speaker-oriented, inspired by AutoEq's greedy approach):
 * 1. Greedy: in each step look at the remaining error and propose candidates -
 *    a peaking filter at the largest weighted deviation (Q from its width) and,
 *    if allowed, low/high shelves for broad trends. Each candidate is locally
 *    optimised (Nelder-Mead over log f, gain, log Q) with all previous bands
 *    fixed; the best one is kept. Stops early when nothing improves.
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
        // A band must buy at least 1.5 % of the initial error (or 0.01 dB^2) - otherwise it's
        // fitting measurement noise / reflection ripple, not the speaker.
        val minGain = maxOf(currentLoss * 0.015, 0.01)

        repeat(constraints.maxBands) {
            val residual = DoubleArray(n) { desired[it] - fixed[it] }
            var best: PeqBand? = null
            var bestLoss = currentLoss
            for (candidate in candidates(residual)) {
                val opt = optimise(candidate, fixed)
                val l = loss(fixed, opt)
                if (l < bestLoss - minGain) { bestLoss = l; best = opt }
            }
            val chosen = best ?: return finish(bands)
            bands += chosen
            grid.response(chosen, fixed, accumulate = true)
            currentLoss = bestLoss
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
        return finish(bands)
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
            if (total < cutLimit) penalty += (cutLimit - total).pow(2)
        }
        return err / weightSum + 10.0 * penalty / n
    }

    // ---- candidates ---------------------------------------------------------

    private fun candidates(residual: DoubleArray): List<PeqBand> {
        val out = mutableListOf<PeqBand>()
        val f = grid.frequencies
        // Largest weighted deviation inside the allowed range.
        var idx = -1
        var best = 0.0
        for (i in 0 until n) {
            if (f[i] < constraints.minFrequency || f[i] > constraints.maxFrequency) continue
            val v = weights[i] * abs(residual[i])
            if (v > best) { best = v; idx = i }
        }
        if (idx >= 0 && abs(residual[idx]) > 0.3) {
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
            val low = weightedMean(residual, constraints.minFrequency, 150.0)
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
        PeqType.LOW_SHELF -> max(constraints.minFrequency, 30.0) to 400.0
        PeqType.HIGH_SHELF -> 2000.0 to max(2500.0, constraints.maxFrequency)
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
