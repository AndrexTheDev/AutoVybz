package com.andrexthedev.autovybz.measurement.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Complex FFT abstraction so the analysis pipeline can run on the native
 * C++ engine (see `NativeFft`, src/main/cpp/autovybz) or, as a fallback,
 * on the pure-Kotlin implementation below. Both must produce identical
 * results (verified in FftTest / on-device self test).
 */
interface FftEngine {
    val name: String

    /**
     * In-place complex FFT. [re] and [im] must have the same power-of-two size.
     * The forward transform is unscaled; the inverse is scaled by 1/n so that
     * inverse(forward(x)) == x.
     */
    fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean)
}

/** Iterative radix-2 Cooley-Tukey FFT with a precomputed twiddle table. */
object KotlinFft : FftEngine {
    override val name: String = "Kotlin"

    @Volatile
    private var cachedSize = 0
    @Volatile
    private var cachedCos = DoubleArray(0)
    @Volatile
    private var cachedSin = DoubleArray(0)

    override fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size
        require(n == im.size) { "re/im size mismatch" }
        require(isPowerOfTwo(n)) { "FFT size must be a power of two, was $n" }
        if (n == 1) return

        // Bit-reversal permutation.
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }

        val (cosT, sinT) = twiddles(n)
        val sign = if (inverse) 1.0 else -1.0
        var len = 2
        while (len <= n) {
            val half = len shr 1
            val stride = n / len
            var start = 0
            while (start < n) {
                var k = 0
                for (m in 0 until half) {
                    val wr = cosT[k]
                    val wi = sign * sinT[k]
                    val a = start + m
                    val b = a + half
                    val xr = re[b] * wr - im[b] * wi
                    val xi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - xr
                    im[b] = im[a] - xi
                    re[a] += xr
                    im[a] += xi
                    k += stride
                }
                start += len
            }
            len = len shl 1
        }

        if (inverse) {
            val scale = 1.0 / n
            for (i in 0 until n) {
                re[i] *= scale
                im[i] *= scale
            }
        }
    }

    /** cos/sin(2*pi*k/n) for k in [0, n/2). Cached for the last size used. */
    private fun twiddles(n: Int): Pair<DoubleArray, DoubleArray> {
        synchronized(this) {
            if (cachedSize != n) {
                val half = n / 2
                val c = DoubleArray(half)
                val s = DoubleArray(half)
                for (k in 0 until half) {
                    val a = 2.0 * PI * k / n
                    c[k] = cos(a)
                    s[k] = sin(a)
                }
                cachedCos = c
                cachedSin = s
                cachedSize = n
            }
            return cachedCos to cachedSin
        }
    }
}

fun isPowerOfTwo(n: Int): Boolean = n > 0 && (n and (n - 1)) == 0

fun nextPowerOfTwo(n: Int): Int {
    require(n in 1..(1 shl 30)) { "size out of range: $n" }
    var p = 1
    while (p < n) p = p shl 1
    return p
}

/** |X_k|^2 for k in [0, n/2] of a real signal (zero padded / truncated to [n]). */
fun FftEngine.powerSpectrum(signal: FloatArray, from: Int, length: Int, n: Int): DoubleArray {
    val re = DoubleArray(n)
    val im = DoubleArray(n)
    val count = minOf(length, n, signal.size - from).coerceAtLeast(0)
    for (i in 0 until count) re[i] = signal[from + i].toDouble()
    transform(re, im, inverse = false)
    return DoubleArray(n / 2 + 1) { k -> re[k] * re[k] + im[k] * im[k] }
}
