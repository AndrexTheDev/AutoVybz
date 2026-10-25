package com.andrexthedev.autovybz.measurement.eq

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Filter types the Auto-EQ emits. Codes follow Equalizer APO / AutoEq syntax. */
enum class PeqType(val apoCode: String) {
    PEAK("PK"),
    LOW_SHELF("LSC"),
    HIGH_SHELF("HSC"),

    /**
     * 2nd-order high pass (12 dB/oct). Never produced by [PeqFitter] - used only as the
     * protective sub-bass cut of the rescue / device-speaker strategies (see AutoEqRenderer).
     * [PeqBand.gainDb] is meaningless for it and kept at 0.
     */
    HIGH_PASS("HPQ");

    companion object {
        fun fromApoCode(code: String): PeqType? = when (code.uppercase()) {
            "PK", "PEQ" -> PEAK
            "LSC", "LS" -> LOW_SHELF
            "HSC", "HS" -> HIGH_SHELF
            "HPQ" -> HIGH_PASS
            else -> null
        }
    }
}

/** One parametric EQ band. [q] is the RBJ-cookbook Q (shelves use it as in Equalizer314's BiquadFilter). */
data class PeqBand(
    val type: PeqType,
    val frequency: Double,
    val gainDb: Double,
    val q: Double,
)

/**
 * RBJ biquad magnitude maths. Coefficients are *identical* to
 * com.bearinmind.equalizer314.dsp.BiquadFilter (BELL / LOW_SHELF / HIGH_SHELF /
 * HIGH_PASS, non-Vicanek bell), so a preset rendered here sounds exactly like the curve
 * the Equalizer314 engine plays (see BiquadParityTest).
 */
object BiquadMath {

    const val DEFAULT_SAMPLE_RATE = 48000.0

    /** [b0, b1, b2, a0, a1, a2] (not normalised). */
    fun coefficients(band: PeqBand, sampleRate: Double = DEFAULT_SAMPLE_RATE): DoubleArray {
        val w = 2.0 * PI * band.frequency / sampleRate
        val cw = cos(w)
        val sw = sin(w)
        val a = 10.0.pow(band.gainDb / 40.0)
        return when (band.type) {
            PeqType.PEAK -> {
                if (abs(band.gainDb) < 0.01) return doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
                val alpha = sw / (2.0 * band.q)
                doubleArrayOf(1 + alpha * a, -2 * cw, 1 - alpha * a, 1 + alpha / a, -2 * cw, 1 - alpha / a)
            }
            PeqType.LOW_SHELF -> {
                val beta = sqrt(a) / band.q
                doubleArrayOf(
                    a * ((a + 1) - (a - 1) * cw + beta * sw),
                    2 * a * ((a - 1) - (a + 1) * cw),
                    a * ((a + 1) - (a - 1) * cw - beta * sw),
                    (a + 1) + (a - 1) * cw + beta * sw,
                    -2 * ((a - 1) + (a + 1) * cw),
                    (a + 1) + (a - 1) * cw - beta * sw,
                )
            }
            PeqType.HIGH_SHELF -> {
                val beta = sqrt(a) / band.q
                doubleArrayOf(
                    a * ((a + 1) + (a - 1) * cw + beta * sw),
                    -2 * a * ((a - 1) + (a + 1) * cw),
                    a * ((a + 1) + (a - 1) * cw - beta * sw),
                    (a + 1) - (a - 1) * cw + beta * sw,
                    2 * ((a - 1) - (a + 1) * cw),
                    (a + 1) - (a - 1) * cw - beta * sw,
                )
            }
            PeqType.HIGH_PASS -> {
                val alpha = sw / (2.0 * band.q)
                doubleArrayOf(
                    (1.0 + cw) / 2.0,
                    -(1.0 + cw),
                    (1.0 + cw) / 2.0,
                    1.0 + alpha,
                    -2.0 * cw,
                    1.0 - alpha,
                )
            }
        }
    }

    /** Magnitude of one band at [frequency] in dB. */
    fun magnitudeDb(band: PeqBand, frequency: Double, sampleRate: Double = DEFAULT_SAMPLE_RATE): Double {
        return magnitudeDb(coefficients(band, sampleRate), phi(frequency, sampleRate))
    }

    /** Sum of all bands in dB at [frequency]. */
    fun totalDb(bands: List<PeqBand>, frequency: Double, sampleRate: Double = DEFAULT_SAMPLE_RATE): Double =
        bands.sumOf { magnitudeDb(it, frequency, sampleRate) }

    /** phi = 4 sin^2(w/2): the numerically stable variable for low frequencies. */
    internal fun phi(frequency: Double, sampleRate: Double): Double {
        val s = sin(PI * frequency / sampleRate)
        return 4.0 * s * s
    }

    /**
     * |H|^2 in the phi form (no cancellation at low w, unlike the cos(w) form):
     * |H|^2 = ((b0+b1+b2)^2 + (b0*b2*phi - (b1*(b0+b2) + 4*b0*b2)) * phi) / (same for a)
     */
    internal fun magnitudeDb(c: DoubleArray, phi: Double): Double {
        val b0 = c[0]; val b1 = c[1]; val b2 = c[2]
        val a0 = c[3]; val a1 = c[4]; val a2 = c[5]
        val bs = b0 + b1 + b2
        val asum = a0 + a1 + a2
        val num = bs * bs + (b0 * b2 * phi - (b1 * (b0 + b2) + 4 * b0 * b2)) * phi
        val den = asum * asum + (a0 * a2 * phi - (a1 * (a0 + a2) + 4 * a0 * a2)) * phi
        return 10.0 * log10(max(num, 1e-30) / max(den, 1e-30))
    }

    /** Precomputed cos(w), cos(2w) for fast repeated evaluation on a fixed grid. */
    class Grid(val frequencies: DoubleArray, val sampleRate: Double = DEFAULT_SAMPLE_RATE) {
        internal val phi = DoubleArray(frequencies.size) { phi(frequencies[it], sampleRate) }

        /** Response of [band] in dB on this grid, written to / added into [out]. */
        fun response(band: PeqBand, out: DoubleArray, accumulate: Boolean = false) {
            val c = coefficients(band, sampleRate)
            for (i in frequencies.indices) {
                val v = magnitudeDb(c, phi[i])
                out[i] = if (accumulate) out[i] + v else v
            }
        }

        fun response(bands: List<PeqBand>): DoubleArray {
            val out = DoubleArray(frequencies.size)
            for (b in bands) response(b, out, accumulate = true)
            return out
        }
    }
}
