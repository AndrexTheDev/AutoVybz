package com.andrexthedev.autovybz.measurement.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class FftTest {

    @Test
    fun matchesNaiveDft() {
        val n = 64
        val rnd = Random(1)
        val re = DoubleArray(n) { rnd.nextDouble(-1.0, 1.0) }
        val im = DoubleArray(n) { rnd.nextDouble(-1.0, 1.0) }
        val expRe = DoubleArray(n)
        val expIm = DoubleArray(n)
        for (k in 0 until n) for (t in 0 until n) {
            val a = -2 * PI * k * t / n
            expRe[k] += re[t] * cos(a) - im[t] * sin(a)
            expIm[k] += re[t] * sin(a) + im[t] * cos(a)
        }
        KotlinFft.transform(re, im, inverse = false)
        for (k in 0 until n) {
            assertEquals(expRe[k], re[k], 1e-9)
            assertEquals(expIm[k], im[k], 1e-9)
        }
    }

    @Test
    fun inverseRoundTrip() {
        val n = 1 shl 16
        val rnd = Random(2)
        val orig = DoubleArray(n) { rnd.nextDouble(-1.0, 1.0) }
        val re = orig.copyOf()
        val im = DoubleArray(n)
        KotlinFft.transform(re, im, false)
        KotlinFft.transform(re, im, true)
        for (i in 0 until n) assertEquals(orig[i], re[i], 1e-9)
    }

    @Test
    fun powerOfTwoHelpers() {
        assertTrue(isPowerOfTwo(1024))
        assertTrue(!isPowerOfTwo(1000))
        assertEquals(1024, nextPowerOfTwo(1000))
        assertEquals(1024, nextPowerOfTwo(1024))
    }
}
