package com.andrexthedev.autovybz.measurement.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Loudness of a finished programme, per ITU-R BS.1770-4: K-weighting, gated integrated LUFS, true peak.
 *
 * Why the app needs it: [com.andrexthedev.autovybz.measurement.eq.PreampPlanner] promises that a
 * calibration makes the speaker *better, not quieter*, and until now both the promise and its test rested
 * on an A-weighted long-term spectrum. A-weighting is only a rough stand-in for K-weighting (no +4 dB head
 * shelf, a steeper bass roll-off), so "the loudness did not change" was an estimate, not a measurement.
 * This is the measurement - and it is what the verification pass (W6) shows the user.
 *
 * The filters are the standard's analog prototypes mapped with `tan(π f0 / fs)` prewarping, not RBJ
 * cookbook biquads: at 48 kHz this reproduces the coefficient table BS.1770 prints, and at any other rate
 * it stays the same filter. (Using RBJ here puts the 1 kHz gain at +0.44 dB instead of +0.70 dB and shifts
 * every result by a quarter of a decibel - a mistake that survives a "does it look reasonable" review.)
 *
 * Mono by design: a phone measures one microphone. For multi-channel material the standard sums channel
 * powers with weights (1.0 L/R, 1.41 C, 0 LFE).
 */
object IntegratedLoudness {

    /** BS.1770 loudness offset: a full-scale 1 kHz sine reads -3.01 LUFS, not 0. */
    const val LOUDNESS_OFFSET_DB = -0.691

    /** Blocks quieter than this are silence, not programme. */
    const val ABSOLUTE_GATE_LUFS = -70.0

    /** A block must be within this many LU of the gated mean to count (removes intros, applause, silence). */
    const val RELATIVE_GATE_LU = -10.0

    const val BLOCK_SECONDS = 0.400
    const val BLOCK_OVERLAP = 0.75

    // Analog prototypes of the two filters (BS.1770-4, Table 1/2 and Annex 1).
    private const val PRE_F0_HZ = 1681.974450955533
    private const val PRE_GAIN_DB = 3.999843853973347
    private const val PRE_Q = 0.7071752369554196
    private const val RLB_F0_HZ = 38.13547087602444
    private const val RLB_Q = 0.5003270373238773

    /** Head-shelf (pre-filter) coefficients `[b0, b1, b2, a1, a2]`, a0 normalised to 1. */
    internal fun shelf(sampleRate: Int): DoubleArray {
        val k = tan(PI * PRE_F0_HZ / sampleRate)
        val vh = 10.0.pow(PRE_GAIN_DB / 20.0)
        val vb = vh.pow(0.4996667741545416)
        val a0 = 1.0 + k / PRE_Q + k * k
        return doubleArrayOf(
            (vh + vb * k / PRE_Q + k * k) / a0,
            2.0 * (k * k - vh) / a0,
            (vh - vb * k / PRE_Q + k * k) / a0,
            2.0 * (k * k - 1.0) / a0,
            (1.0 - k / PRE_Q + k * k) / a0,
        )
    }

    /** RLB (high-pass) coefficients `[b0, b1, b2, a1, a2]`, a0 normalised to 1. */
    internal fun rlb(sampleRate: Int): DoubleArray {
        val k = tan(PI * RLB_F0_HZ / sampleRate)
        val a0 = 1.0 + k / RLB_Q + k * k
        return doubleArrayOf(
            1.0, -2.0, 1.0,
            2.0 * (k * k - 1.0) / a0,
            (1.0 - k / RLB_Q + k * k) / a0,
        )
    }

    /** Magnitude of the K-weighting at one frequency (dB) - +0.70 dB at 1 kHz, +4 dB above 2 kHz. */
    fun kWeightingGainDb(frequencyHz: Double, sampleRate: Int = 48000): Double {
        val head = complexGain(shelf(sampleRate), frequencyHz, sampleRate)
        val rlb = complexGain(rlb(sampleRate), frequencyHz, sampleRate)
        // Product of the two complex gains, then its magnitude.
        val re = head.first * rlb.first - head.second * rlb.second
        val im = head.first * rlb.second + head.second * rlb.first
        return 20.0 * log10(max(sqrt(re * re + im * im), 1e-12))
    }

    private fun complexGain(coefficients: DoubleArray, frequencyHz: Double, sampleRate: Int): Pair<Double, Double> {
        val (b0, b1, b2, a1, a2) = coefficients
        val w = 2.0 * PI * frequencyHz / sampleRate
        val c1 = kotlin.math.cos(w)
        val s1 = kotlin.math.sin(w)
        val c2 = kotlin.math.cos(2 * w)
        val s2 = kotlin.math.sin(2 * w)
        val numRe = b0 + b1 * c1 + b2 * c2
        val numIm = -(b1 * s1 + b2 * s2)
        val denRe = 1.0 + a1 * c1 + a2 * c2
        val denIm = -(a1 * s1 + a2 * s2)
        val den = denRe * denRe + denIm * denIm
        return Pair((numRe * denRe + numIm * denIm) / den, (numIm * denRe - numRe * denIm) / den)
    }

    /** K-weighted copy of [samples] (direct form I, zero initial state, as the standard specifies). */
    fun kWeight(samples: FloatArray, sampleRate: Int): DoubleArray {
        var signal = DoubleArray(samples.size) { samples[it].toDouble() }
        for (coefficients in listOf(shelf(sampleRate), rlb(sampleRate))) {
            signal = biquad(signal, coefficients)
        }
        return signal
    }

    private fun biquad(x: DoubleArray, c: DoubleArray): DoubleArray {
        val (b0, b1, b2, a1, a2) = c
        val out = DoubleArray(x.size)
        var x1 = 0.0
        var x2 = 0.0
        var y1 = 0.0
        var y2 = 0.0
        for (i in x.indices) {
            val v = x[i]
            val y = b0 * v + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = v
            y2 = y1; y1 = y
            out[i] = y
        }
        return out
    }

    /** Gated integrated loudness in LUFS, `null` when the signal is shorter than one block or all gated. */
    fun integrated(samples: FloatArray, sampleRate: Int): Double? = integrate(kWeight(samples, sampleRate), sampleRate)

    /** Loudness of an already K-weighted signal (kept separate so a caller can reuse the weighting). */
    internal fun integrate(weighted: DoubleArray, sampleRate: Int): Double? {
        val block = (BLOCK_SECONDS * sampleRate).roundToInt().coerceAtLeast(1)
        val hop = (block * (1.0 - BLOCK_OVERLAP)).roundToInt().coerceAtLeast(1)
        if (weighted.size < block) return null
        val loudness = ArrayList<Double>()
        var start = 0
        while (start + block <= weighted.size) {
            var sum = 0.0
            for (i in start until start + block) sum += weighted[i] * weighted[i]
            val meanSquare = sum / block
            loudness += LOUDNESS_OFFSET_DB + 10.0 * log10(max(meanSquare, 1e-20))
            start += hop
        }
        if (loudness.isEmpty()) return null
        val aboveAbsolute = loudness.filter { it > ABSOLUTE_GATE_LUFS }
        if (aboveAbsolute.isEmpty()) return null
        val relativeGate = meanLoudness(aboveAbsolute) + RELATIVE_GATE_LU
        val gated = aboveAbsolute.filter { it > relativeGate }
        return if (gated.isEmpty()) null else meanLoudness(gated)
    }

    /** The standard averages *powers*, not decibels. */
    private fun meanLoudness(values: List<Double>): Double {
        var sum = 0.0
        for (v in values) sum += 10.0.pow(v / 10.0)
        return 10.0 * log10(max(sum / values.size, 1e-20))
    }

    /** Integrated loudness *and* BS.1770 true peak of the same buffer. */
    data class Result(val integratedLufs: Double, val truePeakDbtp: Double, val blocks: Int)

    fun analyse(samples: FloatArray, sampleRate: Int): Result? {
        val weighted = kWeight(samples, sampleRate)
        val lufs = integrate(weighted, sampleRate) ?: return null
        val block = (BLOCK_SECONDS * sampleRate).roundToInt().coerceAtLeast(1)
        val hop = (block * (1.0 - BLOCK_OVERLAP)).roundToInt().coerceAtLeast(1)
        val blocks = if (weighted.size < block) 0 else (weighted.size - block) / hop + 1
        return Result(lufs, TruePeak.peakDbtp(samples), blocks)
    }

    /** Convenience for the probe/tests: loudness difference in LU (positive = B is louder). */
    fun loudnessDifferenceLu(a: FloatArray, b: FloatArray, sampleRate: Int): Double? {
        val la = integrated(a, sampleRate) ?: return null
        val lb = integrated(b, sampleRate) ?: return null
        return lb - la
    }

    /** Absolute sample peak in dBFS (kept next to the true peak so the pair is easy to compare). */
    fun samplePeakDbfs(samples: FloatArray): Double {
        var peak = 0.0
        for (v in samples) peak = max(peak, abs(v.toDouble()))
        return if (peak <= 1e-12) Double.NEGATIVE_INFINITY else 20.0 * log10(peak)
    }
}
