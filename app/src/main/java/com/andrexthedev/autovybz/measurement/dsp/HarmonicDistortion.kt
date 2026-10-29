package com.andrexthedev.autovybz.measurement.dsp

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Harmonic distortion of the loudspeaker, read out of the deconvolved sweep.
 *
 * Farina's exponential-sweep trick: a memoryless nonlinearity of order n maps the *instantaneous* sweep
 * frequency to its n-th harmonic, and in the deconvolved impulse response every order therefore lands at a
 * fixed — negative — lag,
 *
 * ```
 * Δt(n) = T · ln(n) / ln(f2/f1)
 * ```
 *
 * with `T` the sweep length. A 5 s sweep that already contains the room's answer also contains H2…H5, at
 * −0.6 s / −1.0 s / −1.3 s (for 30 Hz → 18 kHz): no extra playback, no extra measurement second.
 *
 * Two things this *cannot* do, and both are why the windows are narrow:
 *
 * * it measures the distortion of the whole chain (speaker **and** microphone), so a clipped capture shows
 *   up here as well — `CLIPPING` and the capture audit still decide that case;
 * * it only sees *smooth* nonlinearity. A rattling cabinet produces broadband, order-independent energy,
 *   which is why [RATTLE_RATIO_DB] looks at how slowly the harmonics fall rather than at their level.
 *
 * The windows (±1 ms) sit entirely outside the ±4 ms / +300 ms window the magnitude response is taken
 * from, so adding this measurement cannot bias the frequency response — that is the property
 * `pipeline_findings.py` finding 1 established, and the reason this is free.
 */
object HarmonicDistortion {

    /** Orders that are read. H2 and H3 carry most of the energy; H5 is what separates rattle from clipping. */
    val ORDERS = intArrayOf(2, 3, 4, 5)

    /** Half-width of the search window around the predicted lag (s). */
    const val WINDOW_SECONDS = 0.001

    /** The linear peak must clear the local floor by this much, else the deconvolution is noise. */
    const val MIN_PEAK_ABOVE_FLOOR_DB = 12.0

    /** A harmonic window must clear the floor by this much to count as observed. */
    const val HARMONIC_FLOOR_MARGIN_DB = 6.0

    /**
     * Control points in `ln(order)` units, deliberately *between* the harmonics (ln 2, ln 3, ln 4, ln 5 =
     * 0.69, 1.10, 1.39, 1.61): whatever sits there is the room's own decay, not a distortion product. The
     * local floor is the maximum of these, i.e. the most pessimistic one.
     */
    private val FLOOR_LN_OFFSETS = doubleArrayOf(0.55, 0.90, 1.25, 1.50, 1.85)

    /** THD from which the measurement level is suspect or the driver is damaged (roadmap W3). */
    const val THD_WARN_PERCENT = 3.0

    /**
     * Rattle signature: H5 within 6 dB of H3, i.e. the harmonic series stops falling. A symmetric soft
     * clip — what a driver past its excursion limit does — produces *only* odd orders and falls by
     * 10-40 dB per order, so it never trips this. An impulsive buzz (a loose screw, a torn surround)
     * excites every order about equally and does.
     */
    const val RATTLE_RATIO_DB = -6.0

    /** H2…H5 relative to the linear peak, THD and the rattle ratio; `null` when nothing was measurable. */
    data class Result(
        /** dB relative to the linear impulse-response peak, per order (only observed orders). */
        val harmonicsDb: Map<Int, Double>,
        /** `sqrt(Σ a_n²)` in percent, over the observed orders. */
        val thdPercent: Double,
        /** H3 − H2 in dB, `null` unless both were observed. */
        val h3MinusH2Db: Double?,
        /** How far the linear peak sits above the local floor (dB): the confidence behind the numbers. */
        val peakAboveFloorDb: Double,
    ) {
        /** Rattle-like harmonic series (see [RATTLE_RATIO_DB]). */
        val isRattleLike: Boolean
            get() {
                val h3 = harmonicsDb[3] ?: return false
                val h5 = harmonicsDb[5] ?: return false
                return h5 - h3 > RATTLE_RATIO_DB
            }
    }

    /**
     * Reads H2…H5 out of [ir] around its [peak] sample.
     *
     * @param sweepSeconds length of the excitation sweep (T in the lag formula)
     * @param startHz / @param endHz the sweep's frequency range (f1, f2)
     */
    fun analyse(ir: DoubleArray, peak: Int, sweepSeconds: Double, startHz: Double, endHz: Double, sampleRate: Int): Result? {
        if (ir.isEmpty() || sweepSeconds <= 0.0 || startHz <= 0.0 || endHz <= startHz) return null
        val half = max(1, (WINDOW_SECONDS * sampleRate).roundToInt())
        val span = ln(endHz / startHz)
        fun lagIndex(lnOrder: Double): Int = (peak - sweepSeconds * lnOrder / span * sampleRate).roundToInt()

        val linear = windowPeak(ir, peak, half)
        if (linear <= 0.0) return null
        var floor = 1e-20
        for (u in FLOOR_LN_OFFSETS) floor = max(floor, windowPeak(ir, lagIndex(u), half))
        val peakAboveFloor = 20.0 * log10(linear / floor)
        if (peakAboveFloor < MIN_PEAK_ABOVE_FLOOR_DB) return null

        val harmonics = LinkedHashMap<Int, Double>()
        for (order in ORDERS) {
            val level = windowPeak(ir, lagIndex(ln(order)), half)
            if (20.0 * log10(max(level, 1e-20) / floor) < HARMONIC_FLOOR_MARGIN_DB) continue
            harmonics[order] = 20.0 * log10(max(level, 1e-20) / linear)
        }
        if (harmonics.isEmpty()) return null
        var power = 0.0
        for (db in harmonics.values) power += 10.0.pow(db / 10.0)
        val h3MinusH2 = if (harmonics.containsKey(2) && harmonics.containsKey(3)) harmonics[3]!! - harmonics[2]!! else null
        return Result(harmonics, 100.0 * sqrt(power), h3MinusH2, peakAboveFloor)
    }

    /** Largest |x| within ±[half] samples of [index], wrapped (the deconvolution is circular). */
    private fun windowPeak(x: DoubleArray, index: Int, half: Int): Double {
        var best = 0.0
        val n = x.size
        for (k in -half..half) {
            val i = ((index + k) % n + n) % n
            val a = abs(x[i])
            if (a > best) best = a
        }
        return best
    }
}
