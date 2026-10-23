package com.andrexthedev.autovybz.measurement.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import com.andrexthedev.autovybz.measurement.mic.InputSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.max

data class CaptureConfig(
    val sampleRate: Int,
    val source: InputSource,
    /** External USB mic (Pro), null = built-in microphone. */
    val inputDevice: AudioDeviceInfo? = null,
    val extraRecordSeconds: Double = 1.0,
    val outputGain: Float = 1.0f,
)

data class CaptureResult(
    val recording: FloatArray,
    val sampleRate: Int,
    val engineName: String,
    /** Source actually used after fallbacks. */
    val sourceUsed: InputSource,
    val xruns: Int,
)

class CaptureException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Plays [stimulus] on the current media route while recording the mic. */
interface MeasurementAudioEngine {
    val name: String
    suspend fun capture(stimulus: FloatArray, config: CaptureConfig, onProgress: (progress: Float, inputPeak: Float) -> Unit): CaptureResult
}

/* ------------------------------------------------------------------ Oboe -- */

class OboeMeasurementEngine : MeasurementAudioEngine {
    override val name = "Oboe (C++)"

    override suspend fun capture(
        stimulus: FloatArray, config: CaptureConfig, onProgress: (Float, Float) -> Unit,
    ): CaptureResult = withContext(Dispatchers.Default) {
        if (!NativeEngine.isAvailable) throw CaptureException("native engine unavailable")
        val h = NativeEngine.nativeCreate()
        try {
            val preset = when (config.source) {
                InputSource.UNPROCESSED, InputSource.USB -> NativeEngine.PRESET_UNPROCESSED
                InputSource.VOICE_RECOGNITION -> NativeEngine.PRESET_VOICE_RECOGNITION
                InputSource.MIC -> NativeEngine.PRESET_GENERIC
            }
            NativeEngine.nativeStart(
                h, stimulus, config.sampleRate, preset, config.inputDevice?.id ?: 0, 0,
                config.extraRecordSeconds, config.outputGain,
            )?.let { throw CaptureException(it) }

            val timeoutMs = ((stimulus.size.toDouble() / config.sampleRate + config.extraRecordSeconds) * 1000 + 4000).toLong()
            val t0 = System.currentTimeMillis()
            while (true) {
                if (!coroutineContext.isActive) throw CancellationException()
                when (NativeEngine.nativeState(h)) {
                    NativeEngine.STATE_FINISHED -> break
                    NativeEngine.STATE_ERROR -> throw CaptureException(NativeEngine.nativeLastError(h) ?: "audio error")
                }
                if (System.currentTimeMillis() - t0 > timeoutMs) throw CaptureException("recording timed out (no microphone data)")
                onProgress(NativeEngine.nativeProgress(h), NativeEngine.nativeInputPeak(h))
                delay(50)
            }
            val xruns = NativeEngine.nativeXruns(h)
            val actual = NativeEngine.nativeActualPreset(h)
            NativeEngine.nativeStop(h)
            onProgress(1f, NativeEngine.nativeInputPeak(h))
            CaptureResult(
                recording = NativeEngine.nativeRecording(h),
                sampleRate = config.sampleRate,
                engineName = name,
                sourceUsed = when {
                    config.source == InputSource.USB -> InputSource.USB
                    actual == NativeEngine.PRESET_UNPROCESSED -> InputSource.UNPROCESSED
                    actual == NativeEngine.PRESET_VOICE_RECOGNITION -> InputSource.VOICE_RECOGNITION
                    else -> InputSource.MIC
                },
                xruns = xruns,
            )
        } finally {
            NativeEngine.nativeStop(h)
            NativeEngine.nativeDestroy(h)
        }
    }
}

/* ------------------------------------------------- Java AudioRecord/Track -- */

/**
 * Fallback without native code: blocking AudioRecord/AudioTrack in float.
 * (android.media.audiofx.Visualizer is *not* used: it only taps the output mix
 * at 8-bit / <=1024 points and never sees the microphone, so it cannot measure
 * a room.)
 */
class JavaMeasurementEngine : MeasurementAudioEngine {
    override val name = "AudioRecord/AudioTrack (Java)"

    @SuppressLint("MissingPermission") // RECORD_AUDIO is checked by the wizard before starting.
    override suspend fun capture(
        stimulus: FloatArray, config: CaptureConfig, onProgress: (Float, Float) -> Unit,
    ): CaptureResult = withContext(Dispatchers.IO) {
        val fs = config.sampleRate
        val (record, sourceUsed) = openRecord(config)
        val outMin = AudioTrack.getMinBufferSize(fs, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
            )
            .setAudioFormat(
                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(fs)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build(),
            )
            .setBufferSizeInBytes(max(outMin, fs / 10 * 8))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        val total = stimulus.size + (config.extraRecordSeconds * fs).toInt()
        val rec = FloatArray(total)
        val recPos = AtomicInteger(0)
        val peakBits = AtomicInteger(0f.toRawBits())
        val failure = AtomicReference<Throwable?>(null)

        val recorder = Thread({
            try {
                val chunk = FloatArray(1024)
                var pos = 0
                var peak = 0f
                while (pos < total && failure.get() == null) {
                    val n = record.read(chunk, 0, minOf(chunk.size, total - pos), AudioRecord.READ_BLOCKING)
                    if (n < 0) throw CaptureException("AudioRecord.read error $n")
                    for (i in 0 until n) {
                        rec[pos + i] = chunk[i]
                        peak = max(peak, abs(chunk[i]))
                    }
                    pos += n
                    peakBits.set(peak.toRawBits())
                    recPos.set(pos)
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            }
        }, "AutoVybz-rec")

        val player = Thread({
            try {
                val chunk = FloatArray(2048) // 1024 stereo frames
                var pos = 0
                while (pos < stimulus.size && failure.get() == null) {
                    val frames = minOf(1024, stimulus.size - pos)
                    for (i in 0 until frames) {
                        val s = stimulus[pos + i] * config.outputGain
                        chunk[2 * i] = s; chunk[2 * i + 1] = s
                    }
                    val w = track.write(chunk, 0, frames * 2, AudioTrack.WRITE_BLOCKING)
                    if (w < 0) throw CaptureException("AudioTrack.write error $w")
                    pos += frames
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            }
        }, "AutoVybz-play")

        try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw CaptureException("microphone busy (another app is recording?)")
            recorder.start()
            track.play()
            player.start()
            val timeoutMs = (total * 1000L / fs) + 4000
            val t0 = System.currentTimeMillis()
            while (recPos.get() < total) {
                failure.get()?.let { throw it as? CaptureException ?: CaptureException(it.message ?: "audio error", it) }
                if (!coroutineContext.isActive) throw CancellationException()
                if (System.currentTimeMillis() - t0 > timeoutMs) throw CaptureException("recording timed out")
                onProgress(recPos.get().toFloat() / total, Float.fromBits(peakBits.get()))
                delay(50)
            }
            onProgress(1f, Float.fromBits(peakBits.get()))
            CaptureResult(rec, fs, name, sourceUsed, xruns = track.underrunCount)
        } finally {
            failure.compareAndSet(null, CancellationException("capture finished"))
            runCatching { track.pause(); track.flush(); track.stop() }
            runCatching { record.stop() }
            recorder.join(500); player.join(500)
            track.release(); record.release()
        }
    }

    @SuppressLint("MissingPermission")
    private fun openRecord(config: CaptureConfig): Pair<AudioRecord, InputSource> {
        val chain = when (config.source) {
            InputSource.USB -> listOf(InputSource.USB, InputSource.VOICE_RECOGNITION)
            InputSource.UNPROCESSED -> listOf(InputSource.UNPROCESSED, InputSource.VOICE_RECOGNITION, InputSource.MIC)
            InputSource.VOICE_RECOGNITION -> listOf(InputSource.VOICE_RECOGNITION, InputSource.MIC)
            InputSource.MIC -> listOf(InputSource.MIC)
        }
        val fs = config.sampleRate
        val minBuf = AudioRecord.getMinBufferSize(fs, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        var lastError: Throwable? = null
        for (src in chain) {
            try {
                val r = AudioRecord.Builder()
                    .setAudioSource(androidSource(src))
                    .setAudioFormat(
                        AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(fs)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build(),
                    )
                    .setBufferSizeInBytes(max(minBuf, fs / 2 * 4))
                    .build()
                if (r.state != AudioRecord.STATE_INITIALIZED || r.sampleRate != fs) {
                    r.release(); continue
                }
                if (src == InputSource.USB && config.inputDevice != null) r.setPreferredDevice(config.inputDevice)
                return r to src
            } catch (t: Throwable) {
                lastError = t
            }
        }
        throw CaptureException("Cannot open microphone at $fs Hz", lastError)
    }

    private fun androidSource(src: InputSource) = when (src) {
        InputSource.UNPROCESSED, InputSource.USB -> MediaRecorder.AudioSource.UNPROCESSED
        InputSource.VOICE_RECOGNITION -> MediaRecorder.AudioSource.VOICE_RECOGNITION
        InputSource.MIC -> MediaRecorder.AudioSource.MIC
    }
}

/** Tries Oboe first, falls back to the Java engine if the native path fails to start. */
class FallbackMeasurementEngine(
    private val primary: MeasurementAudioEngine = OboeMeasurementEngine(),
    private val fallback: MeasurementAudioEngine = JavaMeasurementEngine(),
) : MeasurementAudioEngine {
    override val name: String get() = "${primary.name} -> ${fallback.name}"

    override suspend fun capture(stimulus: FloatArray, config: CaptureConfig, onProgress: (Float, Float) -> Unit): CaptureResult =
        try {
            primary.capture(stimulus, config, onProgress)
        } catch (c: CancellationException) {
            throw c
        } catch (e: CaptureException) {
            android.util.Log.w("AutoVybz", "Oboe capture failed (${e.message}), falling back to Java engine")
            fallback.capture(stimulus, config, onProgress)
        }
}
