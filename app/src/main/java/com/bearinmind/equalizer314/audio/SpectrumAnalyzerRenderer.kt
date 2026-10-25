package com.bearinmind.equalizer314.audio

import android.graphics.*
import kotlin.math.*

/**
 * Waveform-based spectrum analyzer renderer. Pipeline per frame:
 * waveform bytes → Hann window → zero-pad → FFT → dBFS (WaveformFftProcessor);
 * dB→linear; asymmetric EMA in linear domain (fast attack, slow release); linear→dB;
 * log-freq mapping (interpolate sub-bin, peak multi-bin); +3 dB/oct tilt (pivot 1 kHz);
 * render with cubic Bézier paths + gradient fill.
 */
class SpectrumAnalyzerRenderer(
    private val sampleRate: Int = 48000
) {
    @Volatile // written by setFftSize() on the UI thread, read by updateWaveformData() on the audio thread
    private var fftProcessor = WaveformFftProcessor(fftSize = 4096, sampleRate = sampleRate)

    /** Change FFT size at runtime. Resets smoothing state. */
    fun setFftSize(size: Int) {
        if (size != fftProcessor.fftSize) {
            fftProcessor = WaveformFftProcessor(fftSize = size, sampleRate = sampleRate)
            smoothedLinear = null
        }
    }

    /** PPO (Points Per Octave) smoothing. 0 = disabled. Typical: 1, 3, 6, 12, 24, 48 */
    @Volatile
    var ppoSmoothing: Int = 0

    /** Spectrum color (ARGB). Set to change the fill/stroke tint. */
    fun setSpectrumColor(color: Int) {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        inputStrokePaint.color = Color.argb(70, r, g, b)
        outputStrokePaint.color = Color.argb(100, r, g, b)
        spectrumR = r; spectrumG = g; spectrumB = b
    }
    private var spectrumR = 180; private var spectrumG = 180; private var spectrumB = 180

    // Temporally smoothed LINEAR magnitudes per bin (not dB)
    // Smoothing in linear domain properly reduces noise floor by √N frames
    @Volatile
    private var smoothedLinear: FloatArray? = null

    // Pre-computed PPO-smoothed dB values (computed on Visualizer thread, read on UI thread)
    @Volatile
    private var ppoSmoothedDb: FloatArray? = null

    /** Expose smoothed linear magnitudes for compressor gain computation */
    fun getSmoothedLinear(): FloatArray? = smoothedLinear
    fun getBinWidthHz(): Float = fftProcessor.binWidthHz

    // MBC gain computer — computes per-band GR from actual spectrum levels each frame
    var mbcGainComputer: MbcGainComputer? = null
    var mbcBandSettings: Array<MbcGainComputer.BandSettings>? = null

    // Asymmetric ballistics — applied to linear magnitudes
    private val attackAlpha = 0.6f    // fast rise (~2-3 frames to reach 90%)
    @Volatile
    var releaseAlpha = 0.22f  // ~6 frames to decay. Lower = slower falloff.

    // Opacity for fade-out when music stops (1.0 = fully visible)
    @Volatile
    var opacity = 1f
        private set

    // Reusable drawing objects — input spectrum
    private val inputFillPath = Path()
    private val inputStrokePath = Path()

    // Reusable drawing objects — output spectrum
    private val outputFillPath = Path()
    private val outputStrokePath = Path()
    private val commonFillPath = Path()

    // Per-draw scratch buffers, reallocated only when the size changes (draw() is UI-thread only).
    private var scratchDb: FloatArray? = null
    private var scratchX: FloatArray? = null
    private var scratchInputY: FloatArray? = null
    private var scratchOutputY: FloatArray? = null
    private var scratchCommonY: FloatArray? = null

    // Fill gradients depend only on (top, bottom, spectrum color) — cache instead of rebuilding per frame.
    private var dimGradient: LinearGradient? = null
    private var brightGradient: LinearGradient? = null
    private var gradientTop = Float.NaN
    private var gradientBottom = Float.NaN
    private var gradientR = -1
    private var gradientG = -1
    private var gradientB = -1

    private fun fillGradient(top: Float, bottom: Float, dim: Boolean): LinearGradient {
        if (dimGradient == null || gradientTop != top || gradientBottom != bottom ||
            gradientR != spectrumR || gradientG != spectrumG || gradientB != spectrumB
        ) {
            dimGradient = LinearGradient(
                0f, top, 0f, bottom,
                intArrayOf(Color.argb(5, spectrumR, spectrumG, spectrumB), Color.argb(2, spectrumR / 2, spectrumG / 2, spectrumB / 2)),
                null, Shader.TileMode.CLAMP
            )
            brightGradient = LinearGradient(
                0f, top, 0f, bottom,
                intArrayOf(Color.argb(80, spectrumR, spectrumG, spectrumB), Color.argb(15, spectrumR / 2, spectrumG / 2, spectrumB / 2)),
                null, Shader.TileMode.CLAMP
            )
            gradientTop = top; gradientBottom = bottom
            gradientR = spectrumR; gradientG = spectrumG; gradientB = spectrumB
        }
        return if (dim) dimGradient!! else brightGradient!!
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val inputStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f
        color = Color.argb(70, 180, 180, 180)
    }

    private val outputStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = Color.argb(100, 220, 220, 220)
    }

    /**
     * Called from Visualizer callback with RAW WAVEFORM bytes.
     */
    fun updateWaveformData(waveform: ByteArray) {
        opacity = 1f  // reset fade on real audio

        val dbValues = fftProcessor.process(waveform)
        val n = dbValues.size

        var prev = smoothedLinear
        if (prev == null || prev.size != n) {
            prev = FloatArray(n)
        }
        // dB→linear fused into the ballistics loop (was a separate pass + array).
        val result = FloatArray(n)
        for (i in 0 until n) {
            val current = 10f.pow(dbValues[i] / 20f)
            val alpha = if (current > prev[i]) attackAlpha else releaseAlpha
            result[i] = alpha * current + (1f - alpha) * prev[i]
        }
        smoothedLinear = result

        // Pre-compute PPO smoothing on this thread (not UI thread)
        val ppo = ppoSmoothing
        if (ppo > 0) {
            val rawDb = FloatArray(n) { i ->
                if (result[i] > 1e-10f) (20f * log10(result[i])).coerceAtLeast(-96f)
                else -96f
            }
            val binHz = fftProcessor.binWidthHz
            val halfOctaves = 0.5f / ppo
            // Precompute power + prefix sum for O(1) range queries
            val power = FloatArray(n) { i -> 10f.pow(rawDb[i] / 10f) }
            val prefix = FloatArray(n + 1)
            for (i in 0 until n) prefix[i + 1] = prefix[i] + power[i]
            val smoothed = FloatArray(n)
            for (k in 0 until n) {
                val centerFreq = k * binHz
                if (centerFreq < 1f) { smoothed[k] = rawDb[k]; continue }
                val loBin = ((centerFreq * 2f.pow(-halfOctaves)) / binHz).toInt().coerceIn(0, n - 1)
                val hiBin = ((centerFreq * 2f.pow(halfOctaves)) / binHz).toInt().coerceIn(0, n - 1)
                val count = hiBin - loBin + 1
                if (count <= 1) { smoothed[k] = rawDb[k]; continue }
                val avg = (prefix[hiBin + 1] - prefix[loBin]) / count
                smoothed[k] = if (avg > 1e-20f) (10f * log10(avg)).coerceAtLeast(-96f) else -96f
            }
            ppoSmoothedDb = smoothed
        } else {
            ppoSmoothedDb = null
        }
    }

    /** Feed zeros into the EMA so it decays naturally toward silence */
    fun feedSilence() {
        val prev = smoothedLinear ?: return
        val n = prev.size
        val result = FloatArray(n)
        for (i in 0 until n) {
            // Slower decay than normal release for a gentle fall
            result[i] = 0.92f * prev[i]
        }
        smoothedLinear = result
        ppoSmoothedDb = null  // force draw() to recompute from decayed linear values
    }

    /** Restore opacity to full (called when playback resumes) */
    fun resetOpacity() { opacity = 1f }

    /** Reduce opacity by [amount] per call. At 0, draw() returns early. */
    fun fadeOut(amount: Float) {
        opacity = (opacity - amount).coerceAtLeast(0f)
        if (opacity <= 0f) {
            smoothedLinear = null  // fully gone
        }
    }

    /**
     * @param eqResponseDb Optional EQ frequency response in dB per display pixel.
     *                     When provided, draws dual spectrum: dim input + bright output.
     *                     When null, draws single spectrum (input only).
     */
    fun draw(
        canvas: Canvas,
        left: Float, top: Float, right: Float, bottom: Float,
        dbMin: Float, dbMax: Float,
        eqResponseDb: FloatArray? = null
    ) {
        val linear = smoothedLinear ?: return
        val n = linear.size
        if (n < 2) return
        if (opacity <= 0f) return

        val graphWidth = right - left
        val graphHeight = bottom - top
        val dbRange = dbMax - dbMin
        if (dbRange <= 0f || graphWidth <= 0f) return

        val binWidthHz = fftProcessor.binWidthHz

        // Use pre-computed PPO-smoothed dB if available, otherwise compute raw dB
        val precomputed = ppoSmoothedDb
        val db: FloatArray
        if (precomputed != null && precomputed.size == n) {
            db = precomputed
        } else {
            val arr = scratchDb?.takeIf { it.size == n } ?: FloatArray(n).also { scratchDb = it }
            for (i in 0 until n) {
                arr[i] = if (linear[i] > 1e-10f) (20f * log10(linear[i])).coerceAtLeast(-96f) else -96f
            }
            db = arr
        }

        // Compute MBC band gains from the ACTUAL spectrum levels this frame
        val mbcComputer = mbcGainComputer
        val mbcSettings = mbcBandSettings
        if (mbcComputer != null && mbcSettings != null) {
            mbcComputer.computeAllBandGains(db, sampleRate, fftProcessor.fftSize, mbcSettings)
        }

        // Log frequency mapping: 20 Hz – 20 kHz
        val logMin = log10(20f)
        val logMax = log10(20000f)
        val logRange = logMax - logMin

        // Collect display points — one per pixel column
        val displayWidth = graphWidth.toInt().coerceAtLeast(1)
        val xArr = scratchX?.takeIf { it.size == displayWidth } ?: FloatArray(displayWidth).also { scratchX = it }
        val inputY = scratchInputY?.takeIf { it.size == displayWidth } ?: FloatArray(displayWidth).also { scratchInputY = it }
        val outputY = if (eqResponseDb != null || mbcComputer != null) {
            scratchOutputY?.takeIf { it.size == displayWidth } ?: FloatArray(displayWidth).also { scratchOutputY = it }
        } else null

        for (x in 0 until displayWidth) {
            val logFreqLow = logMin + logRange * x / displayWidth
            val logFreqHigh = logMin + logRange * (x + 1) / displayWidth
            val freqLow = 10f.pow(logFreqLow)
            val freqHigh = 10f.pow(logFreqHigh)
            val freq = 10f.pow((logFreqLow + logFreqHigh) / 2f)

            val binLow = (freqLow / binWidthHz).toInt().coerceIn(1, n - 1)
            val binHigh = (freqHigh / binWidthHz).toInt().coerceIn(1, n - 1)

            val rawDb: Float
            if (binLow == binHigh) {
                val exactBin = freq / binWidthHz
                val lower = exactBin.toInt().coerceIn(1, n - 2)
                val frac = exactBin - lower
                rawDb = db[lower] * (1f - frac) + db[lower + 1] * frac
            } else {
                var peak = -96f
                for (b in binLow..binHigh.coerceAtMost(n - 1)) {
                    if (db[b] > peak) peak = db[b]
                }
                rawDb = peak
            }

            // +3 dB/octave tilt compensation (pivot at 1 kHz)
            val tiltDb = 3.0f * (log10(freq / 1000f) / 0.30103f)
            val inputDb = rawDb + tiltDb

            val inputNorm = ((inputDb - dbMin) / dbRange).coerceIn(0f, 1f)
            xArr[x] = left + x
            inputY[x] = top + graphHeight * (1f - inputNorm)

            // Output = input + EQ response only (MBC shown on its own GR trace view)
            if (outputY != null) {
                var outputDb = inputDb
                if (eqResponseDb != null && x < eqResponseDb.size) outputDb += eqResponseDb[x]
                val outputNorm = ((outputDb - dbMin) / dbRange).coerceIn(0f, 1f)
                outputY[x] = top + graphHeight * (1f - outputNorm)
            }
        }

        // Bass smoothing disabled — was killing low-end extension
        // val bass150Pixel = ((log10(150f) - logMin) / logRange * displayWidth).toInt()
        //     .coerceIn(0, displayWidth - 1)
        // val bass300Pixel = ((log10(300f) - logMin) / logRange * displayWidth).toInt()
        //     .coerceIn(0, displayWidth - 1)
        // for (pass in 0 until 3) {
        //     val tmp = inputY.copyOf()
        //     val tmpOut = outputY?.copyOf()
        //     for (x in 2 until bass300Pixel - 2) {
        //         val smoothed = (tmp[x - 2] + tmp[x - 1] + tmp[x] + tmp[x + 1] + tmp[x + 2]) / 5f
        //         val blend = if (x < bass150Pixel) 1f
        //                     else 1f - (x - bass150Pixel).toFloat() / (bass300Pixel - bass150Pixel)
        //         inputY[x] = blend * smoothed + (1f - blend) * tmp[x]
        //         if (tmpOut != null && outputY != null) {
        //             val sOut = (tmpOut[x - 2] + tmpOut[x - 1] + tmpOut[x] + tmpOut[x + 1] + tmpOut[x + 2]) / 5f
        //             outputY[x] = blend * sOut + (1f - blend) * tmpOut[x]
        //         }
        //     }
        // }

        val step = 2
        val lastIdx = displayWidth - 1

        // Clip to graph bounds + apply fade opacity
        val layerAlpha = (opacity * 255).toInt().coerceIn(0, 255)
        canvas.saveLayerAlpha(left, top, right, bottom, layerAlpha)
        canvas.clipRect(left, top, right, bottom)

        // ── Draw INPUT spectrum (background layer) ──
        inputFillPath.reset()
        inputStrokePath.reset()
        inputFillPath.moveTo(xArr[0], bottom)
        inputFillPath.lineTo(xArr[0], inputY[0])
        inputStrokePath.moveTo(xArr[0], inputY[0])
        var i = step
        while (i < displayWidth) {
            val midX = (xArr[i - step] + xArr[i]) / 2f
            inputFillPath.cubicTo(midX, inputY[i - step], midX, inputY[i], xArr[i], inputY[i])
            inputStrokePath.cubicTo(midX, inputY[i - step], midX, inputY[i], xArr[i], inputY[i])
            i += step
        }
        if (i - step < lastIdx) {
            inputFillPath.lineTo(xArr[lastIdx], inputY[lastIdx])
            inputStrokePath.lineTo(xArr[lastIdx], inputY[lastIdx])
        }
        inputFillPath.lineTo(xArr[lastIdx], bottom)
        inputFillPath.close()

        if (outputY != null) {
            // Dual mode: common overlap area = 80 alpha (main spectrum), difference areas
            // (boost/cut leftover) = 5 alpha. commonY uses LOWER of input/output (lower dB = higher Y).
            val commonY = scratchCommonY?.takeIf { it.size == displayWidth }
                ?: FloatArray(displayWidth).also { scratchCommonY = it }
            for (x in 0 until displayWidth) {
                commonY[x] = maxOf(inputY[x], outputY[x])  // max Y = lower on screen = lower dB
            }

            // Draw input fill at 5 alpha (covers cut leftover areas)
            fillPaint.shader = fillGradient(top, bottom, dim = true)
            canvas.drawPath(inputFillPath, fillPaint)

            // Draw output fill at 5 alpha (covers boost areas above input)
            outputFillPath.reset()
            outputStrokePath.reset()
            outputFillPath.moveTo(xArr[0], bottom)
            outputFillPath.lineTo(xArr[0], outputY[0])
            outputStrokePath.moveTo(xArr[0], outputY[0])
            i = step
            while (i < displayWidth) {
                val midX = (xArr[i - step] + xArr[i]) / 2f
                outputFillPath.cubicTo(midX, outputY[i - step], midX, outputY[i], xArr[i], outputY[i])
                outputStrokePath.cubicTo(midX, outputY[i - step], midX, outputY[i], xArr[i], outputY[i])
                i += step
            }
            if (i - step < lastIdx) {
                outputFillPath.lineTo(xArr[lastIdx], outputY[lastIdx])
                outputStrokePath.lineTo(xArr[lastIdx], outputY[lastIdx])
            }
            outputFillPath.lineTo(xArr[lastIdx], bottom)
            outputFillPath.close()
            canvas.drawPath(outputFillPath, fillPaint)

            // Draw common area at 80 alpha (the bright main spectrum)
            commonFillPath.reset()
            commonFillPath.moveTo(xArr[0], bottom)
            commonFillPath.lineTo(xArr[0], commonY[0])
            i = step
            while (i < displayWidth) {
                val midX = (xArr[i - step] + xArr[i]) / 2f
                commonFillPath.cubicTo(midX, commonY[i - step], midX, commonY[i], xArr[i], commonY[i])
                i += step
            }
            if (i - step < lastIdx) commonFillPath.lineTo(xArr[lastIdx], commonY[lastIdx])
            commonFillPath.lineTo(xArr[lastIdx], bottom)
            commonFillPath.close()

            fillPaint.shader = fillGradient(top, bottom, dim = false)
            canvas.drawPath(commonFillPath, fillPaint)

            // Draw outlines
            canvas.drawPath(inputStrokePath, inputStrokePaint)
            canvas.drawPath(outputStrokePath, outputStrokePaint)
        } else {
            // Single spectrum mode — normal brightness
            fillPaint.shader = fillGradient(top, bottom, dim = false)
            canvas.drawPath(inputFillPath, fillPaint)
            canvas.drawPath(inputStrokePath, inputStrokePaint)
        }

        canvas.restore()
    }

    fun release() {
        smoothedLinear = null
        opacity = 0f
    }
}
