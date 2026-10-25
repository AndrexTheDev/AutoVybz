package com.andrexthedev.autovybz.testutil

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Synthetic outdoor disturbances added to a simulated microphone recording. */
object OutdoorNoise {

    /**
     * Wind on the microphone: low-passed (2nd order, ~[cornerHz]) noise with a slow gust envelope
     * (0.3 … 1.0, changing over ~1 s). [rms] is the long-term RMS.
     */
    fun addWind(rec: FloatArray, fs: Int, rms: Double, seed: Int = 1, cornerHz: Double = 60.0): FloatArray {
        val rnd = Random(seed)
        val a = exp(-2 * PI * cornerHz / fs)
        var y1 = 0.0
        var y2 = 0.0
        val raw = DoubleArray(rec.size)
        // gust envelope: heavily smoothed random walk
        val g = exp(-2 * PI * 0.7 / fs)
        var env = 0.6
        var envTarget = 0.6
        for (i in rec.indices) {
            if (i % (fs / 4) == 0) envTarget = 0.3 + 0.7 * rnd.nextDouble()
            env = g * env + (1 - g) * envTarget
            y1 = a * y1 + (1 - a) * gaussian(rnd)
            y2 = a * y2 + (1 - a) * y1
            raw[i] = y2 * env
        }
        val cur = sqrt(raw.sumOf { it * it } / raw.size)
        return FloatArray(rec.size) { (rec[it] + raw[it] / cur * rms).toFloat() }
    }

    /** Birdsong: random 60 ms chirps between 3 and 6 kHz, ~[perSecond] per second. */
    fun addBirds(rec: FloatArray, fs: Int, amplitude: Double, perSecond: Double = 4.0, seed: Int = 2): FloatArray {
        val rnd = Random(seed)
        val out = rec.copyOf()
        val len = (0.06 * fs).toInt()
        val count = (rec.size.toDouble() / fs * perSecond).toInt()
        repeat(count) {
            val start = rnd.nextInt(0, rec.size - len)
            val f0 = 3000.0 + 3000.0 * rnd.nextDouble()
            var phase = 0.0
            for (i in 0 until len) {
                val f = f0 * (1.0 + 0.3 * i / len)
                phase += 2 * PI * f / fs
                val w = sin(PI * i / len)
                out[start + i] = (out[start + i] + amplitude * w * sin(phase)).toFloat()
            }
        }
        return out
    }

    /** Bluetooth dropout: [seconds] of silence (plus the mic noise floor that remains) starting at [atSecond]. */
    fun addDropout(rec: FloatArray, fs: Int, atSecond: Double, seconds: Double): FloatArray {
        val out = rec.copyOf()
        val s = (atSecond * fs).toInt()
        val e = minOf(rec.size, s + (seconds * fs).toInt())
        for (i in s until e) out[i] = out[i] * 0.001f
        return out
    }

    /** Burst of extra broadband noise (passing car / voices) in [fromSecond, toSecond). */
    fun addNoiseBurst(rec: FloatArray, fs: Int, fromSecond: Double, toSecond: Double, rms: Double, seed: Int = 3): FloatArray {
        val rnd = Random(seed)
        val out = rec.copyOf()
        for (i in (fromSecond * fs).toInt() until minOf(rec.size, (toSecond * fs).toInt())) {
            out[i] = (out[i] + rms * gaussian(rnd)).toFloat()
        }
        return out
    }

    private fun gaussian(r: Random): Double {
        var u = 0.0
        while (u == 0.0) u = r.nextDouble()
        return sqrt(-2.0 * kotlin.math.ln(u)) * kotlin.math.cos(2 * PI * r.nextDouble())
    }

}
