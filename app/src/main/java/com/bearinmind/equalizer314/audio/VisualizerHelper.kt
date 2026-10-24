package com.bearinmind.equalizer314.audio

import android.content.Context
import android.media.AudioManager
import android.media.audiofx.Visualizer
import android.os.Build
import android.util.Log
import com.bearinmind.equalizer314.ui.EqGraphView

class VisualizerHelper {

    companion object {
        private const val TAG = "VisualizerHelper"

        /**
         * AutoVybz: Visualizer(0) construction / release are synchronous binder calls into audioserver that can
         * block for seconds while the EQ engine reconfigures session 0 (API 30 monkey: "Input dispatching timed
         * out" in MbcActivity). They run serially on this thread instead of the UI thread.
         */
        private val audioThread = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "avz-visualizer").apply { isDaemon = true }
        }
    }

    @Volatile private var visualizer: Visualizer? = null   // only touched on audioThread
    @Volatile private var generation = 0                   // bumped by every start()/stop() on the UI thread
    val renderer = SpectrumAnalyzerRenderer()
    @Volatile var isRunning = false                        // the requested state, set synchronously for the UI
        private set

    // Playback state detection (no permissions needed, API 26+)
    private var audioManager: AudioManager? = null
    private var playbackCallback: AudioManager.AudioPlaybackCallback? = null
    @Volatile
    var isMusicPlaying = true  // assume playing until told otherwise
    private var graphViewRef: EqGraphView? = null

    // Calibration: offset between normalized spectrum dB and absolute dBFS
    // Apply this to normalized per-band levels to get absolute dBFS for compressor math
    @Volatile
    var normToAbsoluteOffset = 0f
        private set

    fun start(graphView: EqGraphView) {
        stop()
        graphViewRef = graphView

        // Register AudioPlaybackCallback to detect play/pause (works on speaker AND Bluetooth)
        val context = graphView.context
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        isMusicPlaying = true  // assume playing on start

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            playbackCallback = object : AudioManager.AudioPlaybackCallback() {
                override fun onPlaybackConfigChanged(configs: MutableList<android.media.AudioPlaybackConfiguration>?) {
                    val wasPlaying = isMusicPlaying
                    // Check if ANY playback config exists (regardless of volume level)
                    isMusicPlaying = configs != null && configs.isNotEmpty()
                    if (wasPlaying && !isMusicPlaying) {
                        Log.d(TAG, "Playback stopped — fading spectrum")
                    }
                    if (!wasPlaying && isMusicPlaying) {
                        renderer.resetOpacity()
                        Log.d(TAG, "Playback started — showing spectrum")
                    }
                }
            }
            audioManager?.registerAudioPlaybackCallback(playbackCallback!!, null)
        }

        isRunning = true
        val gen = ++generation
        audioThread.execute { createVisualizer(gen, graphView) }
    }

    private fun createVisualizer(gen: Int, graphView: EqGraphView) {
        if (gen != generation) return  // stopped or restarted before we got here
        try {
            val v = Visualizer(0).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1]
                scalingMode = Visualizer.SCALING_MODE_NORMALIZED
                measurementMode = Visualizer.MEASUREMENT_MODE_PEAK_RMS

                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        v: Visualizer?, waveform: ByteArray?, samplingRate: Int
                    ) {
                        if (waveform == null || waveform.size < 64 || v == null) return

                        if (isMusicPlaying) {
                            renderer.updateWaveformData(waveform)

                            // Compute calibration offset: absolute dBFS vs normalized spectrum
                            val measurement = Visualizer.MeasurementPeakRms()
                            try {
                                v.getMeasurementPeakRms(measurement)
                            } catch (_: Exception) { return }
                            val absoluteRmsDb = measurement.mRms / 100f  // mB to dB

                            // Compute broadband RMS from the normalized spectrum
                            val specLinear = renderer.getSmoothedLinear()
                            if (specLinear != null && specLinear.isNotEmpty()) {
                                var sumPower = 0.0
                                var count = 0
                                for (i in 1 until specLinear.size) {
                                    if (specLinear[i] > 1e-10f) {
                                        sumPower += (specLinear[i] * specLinear[i]).toDouble()
                                        count++
                                    }
                                }
                                if (count > 0 && sumPower > 0) {
                                    val normalizedRmsDb = (10.0 * Math.log10(sumPower / count)).toFloat()
                                    normToAbsoluteOffset = absoluteRmsDb - normalizedRmsDb
                                }
                            }
                        } else {
                            renderer.feedSilence()
                            renderer.fadeOut(0.04f)
                        }
                        graphView.postInvalidate()
                    }

                    override fun onFftDataCapture(
                        v: Visualizer?, fft: ByteArray?, samplingRate: Int
                    ) { }
                },
                    Visualizer.getMaxCaptureRate(),
                    true, false
                )

                enabled = true
            }
            if (gen != generation) { // stop() was called while audioserver was busy
                runCatching { v.enabled = false; v.release() }
                return
            }
            visualizer = v
            Log.d(TAG, "Visualizer started (waveform mode), capture size: ${v.captureSize}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Visualizer", e)
            visualizer = null
            if (gen == generation) isRunning = false
        }
    }

    fun stop() {
        // Unregister playback callback
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            playbackCallback?.let { audioManager?.unregisterAudioPlaybackCallback(it) }
        }
        playbackCallback = null
        audioManager = null
        graphViewRef = null

        generation++
        isRunning = false
        audioThread.execute {
            val v = visualizer
            visualizer = null
            try {
                v?.enabled = false
                v?.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing Visualizer", e)
            }
        }
        // Don't call renderer.release() — it kills opacity and smoothedLinear.
        // The renderer will reset naturally when new waveform data arrives.
    }
}
