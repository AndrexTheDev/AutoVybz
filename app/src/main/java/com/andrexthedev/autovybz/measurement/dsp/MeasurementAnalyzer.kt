package com.andrexthedev.autovybz.measurement.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt

enum class MeasurementWarning {
    /** Input reached full scale - the result is distorted. Lower the volume. */
    CLIPPING,
    /** Signal barely above the room noise - result unreliable. Raise volume / reduce noise. */
    LOW_SNR,
    /** Sweep and pink-noise results disagree strongly (noise burst, movement, speaker DSP). */
    SWEEP_NOISE_MISMATCH,
    /** Very high output latency (typical for Bluetooth) - informational. */
    HIGH_LATENCY,
}

class MeasurementException(val reason: Reason, message: String) : Exception(message) {
    enum class Reason { NO_SIGNAL, RECORDING_TOO_SHORT }
}

data class AnalyzerConfig(
    val smoothing: SmoothingProfile = SmoothingProfile.DYNAMIC,
    val pointsPerOctave: Int = 48,
    /** Longest round-trip latency searched for (Bluetooth can exceed 300 ms). */
    val maxLatencySeconds: Double = 0.8,
    /** Impulse-response window: before / after the main peak. */
    val irPreSeconds: Double = 0.004,
    val irPostSeconds: Double = 0.30,
    /** Weight of the sweep result vs. the pink-noise result when combining (0..1). */
    val sweepWeight: Double = 0.5,
)

/**
 * Result of one measurement. All dB curves share [frequencies] (log grid,
 * 20 Hz - 20 kHz) and are microphone-compensated and normalised so that the
 * 300 Hz - 3 kHz average is 0 dB.
 */
class MeasurementResult(
    val sampleRate: Int,
    val frequencies: DoubleArray,
    /** 1/48 octave, combined sweep + pink. Exported for REW. */
    val rawDb: DoubleArray,
    /** Dynamic octave smoothing - the curve the Auto-EQ works on. */
    val smoothedDb: DoubleArray,
    /** Fixed 1/3 octave smoothing - used for the speaker roll-off detection. */
    val thirdOctaveDb: DoubleArray,
    val sweepSmoothedDb: DoubleArray,
    val pinkSmoothedDb: DoubleArray,
    /** Signal-to-noise ratio per grid point (1/3 octave), dB. */
    val snrDb: DoubleArray,
    val latencyMs: Double,
    /** Peak absolute input sample (1.0 = full scale). */
    val inputPeak: Double,
    val medianSnrDb: Double,
    val warnings: List<MeasurementWarning>,
    /** Offset that was subtracted for normalisation (absolute level info). */
    val normalizationDb: Double,
)

/**
 * Turns the raw microphone recording of the AutoVybz stimulus into a
 * frequency response. Pure Kotlin (no Android dependencies) so it is fully
 * unit-testable; the heavy FFTs go through [fft] (native C++ when available).
 */
class MeasurementAnalyzer(private val fft: FftEngine = KotlinFft) {

    fun analyze(
        recording: FloatArray,
        stimulus: Stimulus,
        micCompensationDb: (Double) -> Double = { 0.0 },
        config: AnalyzerConfig = AnalyzerConfig(),
    ): MeasurementResult {
        val plan = stimulus.plan
        val fs = plan.sampleRate
        if (recording.size < plan.sweepStart + plan.sweepLength) {
            throw MeasurementException(
                MeasurementException.Reason.RECORDING_TOO_SHORT,
                "Recording has ${recording.size} samples, need at least ${plan.sweepStart + plan.sweepLength}",
            )
        }
        var peak = 0.0
        for (v in recording) peak = max(peak, abs(v.toDouble()))
        if (peak < 1e-4) {
            throw MeasurementException(MeasurementException.Reason.NO_SIGNAL, "Microphone recorded silence")
        }

        val grid = FrequencyGrid.log(pointsPerOctave = config.pointsPerOctave)

        // ---- 1. Sweep: regularised spectral division -> impulse response ----
        val ir = deconvolveSweep(recording, stimulus)
        val maxLag = (config.maxLatencySeconds * fs).roundToInt().coerceAtMost(ir.size - 1)
        var peakIdx = 0
        var peakVal = 0.0
        for (i in 0..maxLag) {
            val a = abs(ir[i])
            if (a > peakVal) { peakVal = a; peakIdx = i }
        }
        val latency = peakIdx
        val sweepPower = windowedIrPower(ir, peakIdx, fs, config)
        val sweepBinHz = fs.toDouble() / ((sweepPower.size - 1) * 2)

        // ---- 2. Pink noise: period-synchronous power average vs. noise floor ----
        val period = plan.pinkPeriod
        val pinkBinHz = fs.toDouble() / period
        val analysisStart = plan.pinkStart + latency + period / 2
        val analysisEnd = minOf(plan.pinkStart + plan.pinkLength - plan.fadeInSamples + latency, recording.size)
        val periods = ((analysisEnd - analysisStart) / period).coerceAtLeast(1)
        val signalPower = DoubleArray(period / 2 + 1)
        for (p in 0 until periods) {
            val ps = fft.powerSpectrum(recording, analysisStart + p * period, period, period)
            for (k in ps.indices) signalPower[k] += ps[k] / periods
        }
        val noiseStart = (0.05 * fs).roundToInt()
        val noisePower = fft.powerSpectrum(recording, noiseStart, period, period)
        val pinkTransfer = DoubleArray(signalPower.size) { k ->
            val ref = stimulus.pinkPeriodPower[k]
            val f = k * pinkBinHz
            if (ref <= 0.0 || f < plan.sweepStartHz || f > plan.sweepEndHz) 0.0 else max(signalPower[k] - noisePower[k], signalPower[k] * 0.01) / ref
        }

        // ---- 3. Log grid + smoothing ----
        val profile = config.smoothing
        val fLo = plan.sweepStartHz
        val fHi = plan.sweepEndHz
        fun sweepGrid(p: SmoothingProfile) = SpectrumBinning.toGrid(sweepPower, sweepBinHz, grid, p, fLo, fHi).powerToDb()
        fun pinkGrid(p: SmoothingProfile) = SpectrumBinning.toGrid(pinkTransfer, pinkBinHz, grid, p, fLo, fHi).powerToDb()
        val sweepRawDb = sweepGrid(SmoothingProfile.RAW)
        val sweepSmoothDb = sweepGrid(profile)
        val sweepThirdDb = sweepGrid(SmoothingProfile.THIRD_OCTAVE)
        val pinkRawDb = pinkGrid(SmoothingProfile.RAW)
        val pinkSmoothDb = pinkGrid(profile)
        val pinkThirdDb = pinkGrid(SmoothingProfile.THIRD_OCTAVE)
        val third = SmoothingProfile.THIRD_OCTAVE
        val sigThird = SpectrumBinning.toGrid(signalPower, pinkBinHz, grid, third, fLo, fHi)
        val noiseThird = SpectrumBinning.toGrid(noisePower, pinkBinHz, grid, third, fLo, fHi)
        val snr = DoubleArray(grid.size) { powerToDb(sigThird[it]) - powerToDb(noiseThird[it]) }

        // ---- 4. Combine (dB domain); fall back to sweep where pink SNR is poor ----
        fun combine(sweep: DoubleArray, pink: DoubleArray) = DoubleArray(grid.size) { i ->
            val w = if (snr[i] < 6.0) 1.0 else config.sweepWeight
            w * sweep[i] + (1 - w) * pink[i]
        }
        val rawDb = combine(sweepRawDb, pinkRawDb)
        val smoothDb = combine(sweepSmoothDb, pinkSmoothDb)
        val thirdDb = combine(sweepThirdDb, pinkThirdDb)

        // ---- 5. Microphone compensation + normalisation ----
        val mic = DoubleArray(grid.size) { micCompensationDb(grid[it]) }
        for (i in grid.indices) {
            rawDb[i] -= mic[i]; smoothDb[i] -= mic[i]; thirdDb[i] -= mic[i]
            sweepSmoothDb[i] -= mic[i]; pinkSmoothDb[i] -= mic[i]
        }
        val norm = FrequencyGrid.bandMean(grid, smoothDb, 300.0, 3000.0)
        for (arr in arrayOf(rawDb, smoothDb, thirdDb, sweepSmoothDb, pinkSmoothDb)) for (i in arr.indices) arr[i] -= norm

        // ---- 6. Quality checks ----
        val warnings = mutableListOf<MeasurementWarning>()
        if (peak >= 0.98) warnings += MeasurementWarning.CLIPPING
        val snrBand = grid.indices.filter { grid[it] in 100.0..10000.0 }.map { snr[it] }.sorted()
        val medianSnr = if (snrBand.isEmpty()) 0.0 else snrBand[snrBand.size / 2]
        if (medianSnr < 15.0) warnings += MeasurementWarning.LOW_SNR
        val mismatch = grid.indices.filter { grid[it] in 200.0..5000.0 }
            .map { abs(sweepSmoothDb[it] - pinkSmoothDb[it]) }.average()
        if (mismatch > 3.0) warnings += MeasurementWarning.SWEEP_NOISE_MISMATCH
        val latencyMs = latency * 1000.0 / fs
        if (latencyMs > 400.0) warnings += MeasurementWarning.HIGH_LATENCY

        return MeasurementResult(
            sampleRate = fs,
            frequencies = grid,
            rawDb = rawDb,
            smoothedDb = smoothDb,
            thirdOctaveDb = thirdDb,
            sweepSmoothedDb = sweepSmoothDb,
            pinkSmoothedDb = pinkSmoothDb,
            snrDb = snr,
            latencyMs = latencyMs,
            inputPeak = peak,
            medianSnrDb = medianSnr,
            warnings = warnings,
            normalizationDb = norm,
        )
    }

    /**
     * h = IFFT( Y * conj(X) / (|X|^2 + eps) ), Kirkeby-style regularisation:
     * small eps inside the sweep band, large eps outside so out-of-band noise
     * is not amplified. A system that passes the sweep unchanged yields a unit
     * impulse at lag 0.
     */
    internal fun deconvolveSweep(recording: FloatArray, stimulus: Stimulus): DoubleArray {
        val plan = stimulus.plan
        val segLen = recording.size - plan.sweepStart
        val n = nextPowerOfTwo(segLen + plan.sweepLength)
        val yr = DoubleArray(n); val yi = DoubleArray(n)
        for (i in 0 until segLen) yr[i] = recording[plan.sweepStart + i].toDouble()
        val xr = DoubleArray(n); val xi = DoubleArray(n)
        for (i in stimulus.sweep.indices) xr[i] = stimulus.sweep[i].toDouble()
        fft.transform(yr, yi, false)
        fft.transform(xr, xi, false)

        var maxPow = 0.0
        for (k in 0..n / 2) maxPow = max(maxPow, xr[k] * xr[k] + xi[k] * xi[k])
        val binHz = plan.sampleRate.toDouble() / n
        val fLo = plan.sweepStartHz
        val fHi = plan.sweepEndHz
        for (k in 0 until n) {
            val kk = if (k <= n / 2) k else n - k
            val f = kk * binHz
            val inBand = f >= fLo && f <= fHi
            val eps = if (inBand) maxPow * 1e-6 else maxPow * 1e-1
            val xp = xr[k] * xr[k] + xi[k] * xi[k]
            // Y * conj(X)
            val nr = yr[k] * xr[k] + yi[k] * xi[k]
            val ni = yi[k] * xr[k] - yr[k] * xi[k]
            val d = xp + eps
            yr[k] = nr / d
            yi[k] = ni / d
        }
        fft.transform(yr, yi, true)
        return yr
    }

    /** Asymmetric window around the IR peak, then |H(f)|^2 on a 2^k grid. */
    private fun windowedIrPower(ir: DoubleArray, peak: Int, fs: Int, config: AnalyzerConfig): DoubleArray {
        val pre = (config.irPreSeconds * fs).roundToInt().coerceAtLeast(1)
        val post = (config.irPostSeconds * fs).roundToInt().coerceAtLeast(16)
        val n = nextPowerOfTwo(maxOf(pre + post, 32768))
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        val taper = (post * 0.3).roundToInt().coerceAtLeast(1)
        for (j in -pre until post) {
            val src = peak + j
            if (src < 0 || src >= ir.size) continue
            val w = when {
                j < 0 -> 0.5 - 0.5 * cos(PI * (j + pre).toDouble() / pre)          // half-Hann rise
                j >= post - taper -> 0.5 + 0.5 * cos(PI * (j - (post - taper)).toDouble() / taper) // half-Hann fall
                else -> 1.0
            }
            re[j + pre] = ir[src] * w
        }
        fft.transform(re, im, false)
        return DoubleArray(n / 2 + 1) { k -> re[k] * re[k] + im[k] * im[k] }
    }
}
