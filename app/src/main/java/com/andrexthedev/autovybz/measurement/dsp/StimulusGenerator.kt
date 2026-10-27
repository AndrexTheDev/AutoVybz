package com.andrexthedev.autovybz.measurement.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Sample-exact layout of the 10 s AutoVybz measurement stimulus.
 *
 * ```
 * | silence 0.75 s | periodic pink noise 2.75 s | gap 0.5 s | log sweep 5.0 s | tail 1.0 s |
 *   noise floor      RTA / power average            decay      ESS deconvolution  reverb + BT latency
 * ```
 *
 * * The leading silence measures the room's noise floor (SNR per frequency).
 * * Periodic pink noise (one FFT period repeated) gives a leakage-free power
 *   average of the in-room response with good low-frequency robustness.
 * * The exponential sine sweep (Farina) gives an impulse response: precise
 *   magnitude response, system latency, and rejection of harmonic distortion.
 * * The tail leaves room for reverberation and Bluetooth latency (up to ~0.8 s).
 */
data class StimulusPlan(
    val sampleRate: Int,
    val leadSilence: Int,
    val pinkStart: Int,
    val pinkLength: Int,
    val pinkPeriod: Int,
    val sweepStart: Int,
    val sweepLength: Int,
    val totalLength: Int,
    val sweepStartHz: Double,
    val sweepEndHz: Double,
    val fadeInSamples: Int,
) {
    val durationSeconds: Double get() = totalLength.toDouble() / sampleRate

    /** Human readable phase for progress UI. */
    fun phaseAt(sample: Int): Phase = when {
        sample < pinkStart -> Phase.NOISE_FLOOR
        sample < pinkStart + pinkLength -> Phase.PINK_NOISE
        sample < sweepStart -> Phase.GAP
        sample < sweepStart + sweepLength -> Phase.SWEEP
        else -> Phase.TAIL
    }

    enum class Phase { NOISE_FLOOR, PINK_NOISE, GAP, SWEEP, TAIL }
}

class Stimulus(
    val plan: StimulusPlan,
    /** Complete mono playback buffer, [StimulusPlan.totalLength] samples. */
    val signal: FloatArray,
    /** The sweep alone (incl. fades) - reference for deconvolution. */
    val sweep: FloatArray,
    /** One period of the pink noise - reference for the power average. */
    val pinkPeriod: FloatArray,
    /** |X_k|^2 of [pinkPeriod] for k in [0, period/2]. */
    val pinkPeriodPower: DoubleArray,
)

object StimulusGenerator {

    const val DEFAULT_SAMPLE_RATE = 48000
    const val SWEEP_START_HZ = 20.0
    const val SWEEP_END_HZ = 20000.0

    /** Peak level of sweep and pink noise (-6 dBFS) - leaves headroom for the DAC / speaker DSP. */
    const val PEAK_LEVEL = 0.5f

    // Segment lengths in seconds - they add up to exactly 10.0 s.
    const val LEAD_SILENCE_S = 0.75
    const val PINK_S = 2.75
    const val GAP_S = 0.5
    const val SWEEP_S = 5.0
    const val TAIL_S = 1.0
    const val TOTAL_S = LEAD_SILENCE_S + PINK_S + GAP_S + SWEEP_S + TAIL_S

    fun plan(sampleRate: Int = DEFAULT_SAMPLE_RATE): StimulusPlan {
        require(sampleRate in 22050..192000) { "Unsupported sample rate $sampleRate" }
        fun s(seconds: Double) = (seconds * sampleRate).roundToInt()
        val lead = s(LEAD_SILENCE_S)
        val pink = s(PINK_S)
        val gap = s(GAP_S)
        val sweep = s(SWEEP_S)
        val tail = s(TAIL_S)
        // One period of the pink noise is also the analysis window, so it has to fit into the lead
        // silence (the noise-floor snapshot uses the same length). At 48 kHz that is 32768 samples
        // (0.68 s, 1.46 Hz resolution) - unchanged. A 44.1 kHz device gets the next smaller power of
        // two (16384 = 0.37 s, 2.7 Hz): coarser bass bins, but a valid measurement instead of a
        // stimulus that cannot be built for the rate the capture path actually runs at.
        val period = largestPowerOfTwoAtMost(lead - s(NOISE_WINDOW_MARGIN_S))
        require(period >= 4096) { "lead silence too short for a noise-floor window at $sampleRate Hz" }
        val nyquistSafeEnd = minOf(SWEEP_END_HZ, sampleRate * 0.45)
        return StimulusPlan(
            sampleRate = sampleRate,
            leadSilence = lead,
            pinkStart = lead,
            pinkLength = pink,
            pinkPeriod = period,
            sweepStart = lead + pink + gap,
            sweepLength = sweep,
            totalLength = lead + pink + gap + sweep + tail,
            sweepStartHz = SWEEP_START_HZ,
            sweepEndHz = nyquistSafeEnd,
            fadeInSamples = s(0.05),
        )
    }

    fun generate(sampleRate: Int = DEFAULT_SAMPLE_RATE, seed: Int = 0x5EED): Stimulus {
        val plan = plan(sampleRate)
        val sweep = logSweep(plan)
        val pinkPeriod = periodicPinkNoise(plan.pinkPeriod, sampleRate, seed)
        val signal = FloatArray(plan.totalLength)

        // Pink-noise segment: tiled period with short raised-cosine fades.
        val fade = plan.fadeInSamples
        for (i in 0 until plan.pinkLength) {
            var g = 1.0
            if (i < fade) g = raisedCosine(i.toDouble() / fade)
            val fromEnd = plan.pinkLength - 1 - i
            if (fromEnd < fade) g = minOf(g, raisedCosine(fromEnd.toDouble() / fade))
            signal[plan.pinkStart + i] = (pinkPeriod[i % plan.pinkPeriod] * g).toFloat()
        }
        System.arraycopy(sweep, 0, signal, plan.sweepStart, sweep.size)

        val ref = KotlinFft.powerSpectrum(pinkPeriod, 0, pinkPeriod.size, plan.pinkPeriod)
        return Stimulus(plan, signal, sweep, pinkPeriod, ref)
    }

    /** Exponential sine sweep with a 50 ms fade-in and 5 ms fade-out. */
    fun logSweep(plan: StimulusPlan): FloatArray {
        val n = plan.sweepLength
        val fs = plan.sampleRate.toDouble()
        val f1 = plan.sweepStartHz
        val f2 = plan.sweepEndHz
        val duration = n / fs
        val rate = ln(f2 / f1)
        val k = 2.0 * PI * f1 * duration / rate
        val fadeIn = plan.fadeInSamples
        val fadeOut = (0.005 * fs).roundToInt()
        return FloatArray(n) { i ->
            val t = i / fs
            var g = 1.0
            if (i < fadeIn) g = raisedCosine(i.toDouble() / fadeIn)
            val fromEnd = n - 1 - i
            if (fromEnd < fadeOut) g = minOf(g, raisedCosine(fromEnd.toDouble() / fadeOut))
            (PEAK_LEVEL * g * sin(k * (exp(t * rate / duration) - 1.0))).toFloat()
        }
    }

    /**
     * One period of band-limited (20 Hz - 20 kHz) pink noise synthesised in the
     * frequency domain: |X(f)|^2 ~ 1/f with random phases, so the period is
     * perfectly periodic (no spectral leakage when analysed period-synchronously).
     *
     * Random phases give a ~12 dB crest factor, i.e. little average power for the
     * allowed peak. [reduceCrestFactor] re-shapes the phases (magnitudes - and
     * thus the exact 1/f spectrum - unchanged) down to ~5.5 dB crest, roughly
     * doubling the RMS level at the same peak: ~7 dB more SNR on the pink-noise
     * analysis path for free.
     */
    fun periodicPinkNoise(period: Int, sampleRate: Int, seed: Int): FloatArray {
        require(isPowerOfTwo(period))
        val rnd = Random(seed)
        val re = DoubleArray(period)
        val im = DoubleArray(period)
        val binHz = sampleRate.toDouble() / period
        for (k in 1 until period / 2) {
            val f = k * binHz
            if (f < SWEEP_START_HZ || f > minOf(SWEEP_END_HZ, sampleRate * 0.45)) continue
            val mag = 1.0 / sqrt(f)
            val phase = rnd.nextDouble() * 2.0 * PI
            re[k] = mag * cos(phase)
            im[k] = mag * sin(phase)
            re[period - k] = re[k]
            im[period - k] = -im[k]
        }
        val magnitudes = DoubleArray(period) { sqrt(re[it] * re[it] + im[it] * im[it]) }
        KotlinFft.transform(re, im, inverse = true)
        reduceCrestFactor(re, im, magnitudes)
        var peak = 0.0
        for (v in re) peak = maxOf(peak, abs(v))
        val scale = if (peak > 0) PEAK_LEVEL / peak else 0.0
        return FloatArray(period) { (re[it] * scale).toFloat() }
    }

    /**
     * Iterative crest-factor reduction on the time signal in [re] ([im] is scratch):
     * clip to [CREST_CLIP_RATIO] of the initial peak, then restore the exact target
     * magnitude spectrum [magnitudes], repeat. Only the phases change, so the
     * 1/f power spectrum is preserved bit-for-bit and out-of-band bins stay zero.
     * Deterministic for a fixed input.
     */
    private fun reduceCrestFactor(re: DoubleArray, im: DoubleArray, magnitudes: DoubleArray) {
        var initialPeak = 0.0
        for (v in re) initialPeak = maxOf(initialPeak, abs(v))
        if (initialPeak <= 0.0) return
        val clip = initialPeak * CREST_CLIP_RATIO
        repeat(CREST_ITERATIONS) {
            for (i in re.indices) re[i] = re[i].coerceIn(-clip, clip)
            im.fill(0.0)
            KotlinFft.transform(re, im, inverse = false)
            for (k in re.indices) {
                val mag = magnitudes[k]
                val cur = sqrt(re[k] * re[k] + im[k] * im[k])
                if (cur > 0.0) {
                    val s = mag / cur
                    re[k] *= s
                    im[k] *= s
                } else {
                    re[k] = mag // phase is arbitrary where the bin had no energy; keep it real-axis
                    im[k] = 0.0
                }
            }
            KotlinFft.transform(re, im, inverse = true)
        }
    }

    private const val CREST_CLIP_RATIO = 0.45
    private const val CREST_ITERATIONS = 20

    /** Margin between the noise-floor window and the start of the pink-noise segment. */
    private const val NOISE_WINDOW_MARGIN_S = 0.05

    /** Largest power of two that still fits into [available] samples (at least 1). */
    private fun largestPowerOfTwoAtMost(available: Int): Int {
        var p = 1
        while (p * 2 <= available) p *= 2
        return p
    }

    /** 0 -> 0, 1 -> 1 with a half-cosine shape. */
    private fun raisedCosine(x: Double): Double = 0.5 - 0.5 * cos(PI * x.coerceIn(0.0, 1.0))
}
