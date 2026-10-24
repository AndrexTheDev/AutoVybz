package com.andrexthedev.autovybz.testutil

import com.andrexthedev.autovybz.measurement.eq.BiquadMath
import com.andrexthedev.autovybz.measurement.eq.PeqBand
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Time-domain "speaker + room + microphone" simulator for end-to-end tests:
 * a chain of biquads (speaker / room colouration), a delay (output + Bluetooth
 * latency), an early reflection, gain and additive noise.
 */
class SpeakerSimulator(
    val sampleRate: Int = 48000,
    val bands: List<PeqBand> = emptyList(),
    /** Optional 2nd-order high-pass modelling the speaker's bass roll-off. */
    val highPassHz: Double? = null,
    val latencySeconds: Double = 0.0,
    val gain: Double = 1.0,
    val noiseRms: Double = 0.0,
    val reflectionDelaySeconds: Double = 0.0,
    val reflectionGain: Double = 0.0,
    val extraTailSeconds: Double = 0.6,
    val seed: Int = 42,
) {
    fun play(stimulus: FloatArray): FloatArray {
        val coeffs = mutableListOf<DoubleArray>()
        highPassHz?.let { coeffs += highPass(it, 0.707) }
        for (b in bands) coeffs += BiquadMath.coefficients(b, sampleRate.toDouble())

        var x = DoubleArray(stimulus.size) { stimulus[it].toDouble() }
        for (c in coeffs) x = biquad(x, c)

        val delay = (latencySeconds * sampleRate).toInt()
        val refl = (reflectionDelaySeconds * sampleRate).toInt()
        val out = FloatArray(stimulus.size + (extraTailSeconds * sampleRate).toInt())
        val rnd = Random(seed)
        for (i in out.indices) {
            val src = i - delay
            var v = 0.0
            if (src in x.indices) v += x[src]
            if (reflectionGain != 0.0 && src - refl in x.indices) v += reflectionGain * x[src - refl]
            out[i] = (gain * v + noiseRms * gaussian(rnd)).toFloat()
        }
        return out
    }

    /** Ground-truth magnitude response in dB (without the reflection). */
    fun truthDb(f: Double): Double {
        var db = bands.sumOf { BiquadMath.magnitudeDb(it, f, sampleRate.toDouble()) }
        highPassHz?.let {
            val c = highPass(it, 0.707)
            val w = 2 * PI * f / sampleRate
            db += magnitude(c, w)
        }
        return db
    }

    private fun magnitude(c: DoubleArray, w: Double): Double {
        val num = c[0] * c[0] + c[1] * c[1] + c[2] * c[2] + 2 * (c[0] * c[1] + c[1] * c[2]) * cos(w) + 2 * c[0] * c[2] * cos(2 * w)
        val den = c[3] * c[3] + c[4] * c[4] + c[5] * c[5] + 2 * (c[3] * c[4] + c[4] * c[5]) * cos(w) + 2 * c[3] * c[5] * cos(2 * w)
        return 10 * kotlin.math.log10(num / den)
    }

    private fun highPass(fc: Double, q: Double): DoubleArray {
        val w = 2 * PI * fc / sampleRate
        val alpha = sin(w) / (2 * q)
        val cw = cos(w)
        return doubleArrayOf((1 + cw) / 2, -(1 + cw), (1 + cw) / 2, 1 + alpha, -2 * cw, 1 - alpha)
    }

    private fun biquad(x: DoubleArray, c: DoubleArray): DoubleArray {
        val a0 = c[3]
        val b0 = c[0] / a0; val b1 = c[1] / a0; val b2 = c[2] / a0
        val a1 = c[4] / a0; val a2 = c[5] / a0
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        return DoubleArray(x.size) { i ->
            val y = b0 * x[i] + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x[i]; y2 = y1; y1 = y
            y
        }
    }

    private fun gaussian(r: Random): Double {
        val u1 = r.nextDouble().coerceAtLeast(1e-12)
        val u2 = r.nextDouble()
        return sqrt(-2.0 * kotlin.math.ln(u1)) * cos(2 * PI * u2)
    }
}
