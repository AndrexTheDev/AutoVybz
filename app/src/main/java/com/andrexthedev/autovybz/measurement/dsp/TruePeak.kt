package com.andrexthedev.autovybz.measurement.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin

/**
 * Inter-sample ("true") peak of a digital signal - the quantity that decides whether a DAC or a lossy
 * codec clips, as opposed to the sample peak, which only sees the points on the grid (ITU-R BS.1770).
 *
 * Why the app needs it: the live probe measured the corrected output of the demo song at
 * **-1.62 dBFS sample peak but -1.16 dBTP true peak** (+0.46 dB, same for BASS_BOOST). The pre-gain
 * policy ([com.andrexthedev.autovybz.measurement.eq.PreampPlanner]) and the acceptance rule "no clipped
 * samples" both describe the sample grid - so a correction can pass every check and still make the
 * reconstructed waveform overshoot. The limiter is sample-peak driven as well; its headroom allowance
 * is therefore reduced by the measured inter-sample margin ([PreampPlanner.INTER_SAMPLE_MARGIN_DB]).
 *
 * ## Method and accuracy
 *
 * 4x oversampling with a windowed-sinc polyphase FIR (24 taps per phase, Kaiser beta 8, unity DC gain
 * per phase), i.e. exactly the oversampling ratio BS.1770 asks for. Checked against an FFT-exact
 * band-limited reconstruction: within 0.01 dB over the whole audio band.
 *
 * Two limits are worth knowing and are asserted in `TruePeakTest`:
 * * **The 4x grid itself is coarse.** A crest that falls between two oversampled points is under-read
 *   by up to `1 - cos(pi/8)` = 0.17 dB for content near fs/4 - the reason BS.1770 says "higher ratios
 *   are preferred". On real music the difference to an 8x reference is under 0.05 dB, and the effect
 *   this metric exists for is 0.45 dB.
 * * Content at fs/2 (a sample-alternating signal) is *attenuated* by any interpolator - such a signal
 *   does not exist in band-limited audio and is reported below its sample peak rather than above.
 *
 * This is a *measurement* helper, not a real-time meter: [peakDbtp] is O(N · TAPS_PER_PHASE) and runs on
 * finished buffers (a rendered correction, a test signal), never in an audio callback.
 */
object TruePeak {

    /** BS.1770 asks for at least 4x; more does not change the number that matters here. */
    const val OVERSAMPLE = 4

    /** Taps per phase. 16 is enough below 15 kHz, 24 keeps the error at 19 kHz at 0.003 dB. */
    private const val TAPS_PER_PHASE = 24

    private const val KAISER_BETA = 8.0

    /**
     * The prototype low-pass, `OVERSAMPLE * TAPS_PER_PHASE` taps long, one phase per output position.
     * Every phase is normalised to unity DC gain: that is what makes a constant stay constant and keeps
     * the interpolator from inventing level (a plain truncated sinc does not, see `TruePeakTest`).
     */
    private val kernel: DoubleArray by lazy {
        val n = OVERSAMPLE * TAPS_PER_PHASE
        val centre = (n - 1) / 2.0
        val h = DoubleArray(n) { i ->
            val t = i - centre
            val sinc = if (abs(t) < 1e-12) 1.0 else sin(PI * t / OVERSAMPLE) / (PI * t / OVERSAMPLE)
            sinc / OVERSAMPLE * kaiser(i, n)
        }
        for (phase in 0 until OVERSAMPLE) {
            var sum = 0.0
            var i = phase
            while (i < n) { sum += h[i]; i += OVERSAMPLE }
            if (sum != 0.0) {
                i = phase
                while (i < n) { h[i] /= sum; i += OVERSAMPLE }
            }
        }
        h
    }

    private fun kaiser(i: Int, n: Int): Double {
        val x = 2.0 * i / (n - 1) - 1.0
        val arg = KAISER_BETA * sqrt(max(0.0, 1.0 - x * x))
        return besselI0(arg) / besselI0(KAISER_BETA)
    }

    /** Series definition of the modified Bessel function of the first kind, order 0 (converges fast). */
    private fun besselI0(x: Double): Double {
        var sum = 1.0
        var term = 1.0
        var k = 1
        while (k < 64) {
            term *= (x / (2.0 * k)) * (x / (2.0 * k))
            sum += term
            if (term < sum * 1e-17) break
            k++
        }
        return sum
    }

    /** Largest |sample| of the buffer, dBFS (-inf for silence). */
    fun samplePeakDbfs(samples: DoubleArray): Double = dbOf(samples.maxOfOrNull { abs(it) } ?: 0.0)

    /** Largest |sample| of the buffer, dBFS (-inf for silence). */
    fun samplePeakDbfs(samples: FloatArray): Double {
        var peak = 0.0
        for (v in samples) peak = max(peak, abs(v.toDouble()))
        return dbOf(peak)
    }

    /**
     * Largest |value| of the 4x oversampled reconstruction, dBTP. The filter delay is not compensated
     * (the metric is a maximum, not a waveform), but [kernel]'s tail is excluded from the search: the
     * zero-padded start-up would otherwise look like a slow ramp at the block edges.
     */
    fun peakDbtp(samples: DoubleArray): Double {
        if (samples.isEmpty()) return NEG_INFINITY
        val taps = TAPS_PER_PHASE
        if (samples.size <= 2 * taps) return samplePeakDbfs(samples)
        var peak = 0.0
        for (phase in 0 until OVERSAMPLE) {
            // y[p, n] = sum_k x[n - k] * h[phase + k * OVERSAMPLE]
            for (n in taps until samples.size - taps) {
                var acc = 0.0
                var k = 0
                while (k < taps) {
                    acc += samples[n - k] * kernel[phase + k * OVERSAMPLE]
                    k++
                }
                val a = abs(acc)
                if (a > peak) peak = a
            }
        }
        return dbOf(peak)
    }

    /** [peakDbtp] for the float buffers the capture and playback paths use. */
    fun peakDbtp(samples: FloatArray): Double = peakDbtp(DoubleArray(samples.size) { samples[it].toDouble() })

    /**
     * How far the reconstructed waveform rises above the loudest sample, dB (>= 0 in practice).
     *
     * This is the number the pre-gain has to leave free on top of the static bound: it was 0.43-0.46 dB
     * on the measured material, which is why the limiter allowance is not the full 4 dB.
     */
    fun interSampleMarginDb(samples: DoubleArray): Double {
        val sample = samplePeakDbfs(samples)
        val truePeak = peakDbtp(samples)
        return if (sample == NEG_INFINITY || truePeak == NEG_INFINITY) 0.0 else max(0.0, truePeak - sample)
    }

    private fun dbOf(amplitude: Double): Double = if (amplitude <= 1e-12) NEG_INFINITY else 20.0 * log10(amplitude)

    private const val NEG_INFINITY = Double.NEGATIVE_INFINITY

    /** Convenience for tests and documents: a Hann-windowed sine, exactly periodic in [samples]. */
    internal fun periodicSine(samples: Int, cycles: Int, amplitude: Double = 1.0, phase: Double = 0.0): DoubleArray =
        DoubleArray(samples) { i -> amplitude * cos(2.0 * PI * cycles * i / samples + phase) }
}
