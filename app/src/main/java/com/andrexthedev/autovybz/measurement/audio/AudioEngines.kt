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
    /** Rate of the stimulus; the capture path must run at exactly this rate (see [CaptureRates]). */
    val sampleRate: Int,
    val source: InputSource,
    /** Input device to pin (external USB mic, or the built-in primary mic); null = system default route. */
    val inputDevice: AudioDeviceInfo? = null,
    /** Tail recorded after the stimulus - derived from the analysis budget, see [CaptureTiming]. */
    val extraRecordSeconds: Double = CaptureTiming.MIN_TAIL_SECONDS,
    val outputGain: Float = 1.0f,
    /**
     * Output device to pin. Known on API 33+ only (the platform then reports the *active* media device);
     * pinning keeps the stimulus on the route that was classified and verified before the start, even if
     * another device (headset, HDMI) shows up in the meantime. Both engines treat it as a preference.
     */
    val outputDevice: AudioDeviceInfo? = null,
)

data class CaptureResult(
    val recording: FloatArray,
    val sampleRate: Int,
    val engineName: String,
    /** Source actually used after fallbacks. */
    val sourceUsed: InputSource,
    val xruns: Int,
)

/**
 * A capture failure. [kind] is machine-readable on purpose: the message is an English technical string for
 * the log and the "technical details" line, while the *user-facing* text is chosen from [kind] and comes from
 * the string resources (see `WizardError`). Matching on the message text instead would break on the first
 * wording change and could never be translated.
 */
open class CaptureException(
    message: String,
    cause: Throwable? = null,
    val kind: Kind = Kind.OTHER,
) : Exception(message, cause) {
    enum class Kind {
        /** The native Oboe engine is missing (wrong ABI / build without the NDK module). */
        NATIVE_UNAVAILABLE,
        /** No microphone data arrived within the watchdog window (mic occupied, or a silent virtual device). */
        RECORD_TIMEOUT,
        /** The output device does not accept the stimulus sample rate. */
        SPEAKER_UNAVAILABLE,
        /** The microphone could not be opened at all. */
        MIC_OPEN_FAILED,
        /** Another app (or a call) holds the microphone. */
        MIC_BUSY,
        /** The capture path itself failed (negative read/write counts). */
        AUDIO_IO,
        /** The capture runs at a different sample rate than the stimulus (retried once by the controller). */
        SAMPLE_RATE,
        OTHER,
    }
}

/** The measurement was interrupted from outside: output route changed or another app took the audio focus. */
class CaptureInterruptedException(val reason: Reason, message: String) :
    CaptureException(message, kind = Kind.OTHER) {
    enum class Reason { ROUTE_CHANGED, FOCUS_LOST }
}

/**
 * The capture path does not run at the stimulus rate. The analysis depends on a sample-exact timeline, so
 * this is not repaired inside the engine: the controller regenerates the stimulus for [actualRate] and
 * retries once (see [CaptureRates]). [actualRate] is 0 when only the mismatch is known.
 */
class SampleRateMismatchException(
    val requestedRate: Int,
    val actualRate: Int,
) : CaptureException(
    "Microphone runs at ${if (actualRate > 0) "$actualRate Hz" else "a different rate"}, " +
        "the measurement needs $requestedRate Hz",
    kind = CaptureException.Kind.SAMPLE_RATE,
)

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
        if (!NativeEngine.isAvailable) throw CaptureException("native engine unavailable", kind = CaptureException.Kind.NATIVE_UNAVAILABLE)
        val h = NativeEngine.nativeCreate()
        try {
            val preset = when (config.source) {
                InputSource.UNPROCESSED, InputSource.USB -> NativeEngine.PRESET_UNPROCESSED
                InputSource.VOICE_RECOGNITION -> NativeEngine.PRESET_VOICE_RECOGNITION
                InputSource.MIC -> NativeEngine.PRESET_GENERIC
            }
            NativeEngine.nativeStart(
                h, stimulus, config.sampleRate, preset,
                config.inputDevice?.id ?: 0, config.outputDevice?.id ?: 0,
                config.extraRecordSeconds, config.outputGain,
            )?.let { throw startFailure(it, config.sampleRate) }

            val totalFrames = CaptureTiming.totalFrames(stimulus.size, config.sampleRate, config.extraRecordSeconds)
            val timeoutMs = CaptureTiming.timeoutMillis(totalFrames, config.sampleRate)
            val t0 = System.currentTimeMillis()
            while (true) {
                if (!coroutineContext.isActive) throw CancellationException()
                when (NativeEngine.nativeState(h)) {
                    NativeEngine.STATE_FINISHED -> break
                    NativeEngine.STATE_ERROR -> throw startFailure(
                        NativeEngine.nativeLastError(h) ?: "audio error", config.sampleRate,
                    )
                }
                if (System.currentTimeMillis() - t0 > timeoutMs) throw CaptureException(
                    "recording timed out (no microphone data)",
                    kind = CaptureException.Kind.RECORD_TIMEOUT,
                )
                onProgress(NativeEngine.nativeProgress(h), NativeEngine.nativeInputPeak(h))
                delay(50)
            }
            // Read the counters and the buffer while the streams are still open; the native engine keeps
            // them valid until stop(), and stop() is idempotent (finally).
            val xruns = NativeEngine.nativeXruns(h)
            val actual = NativeEngine.nativeActualPreset(h)
            val recording = NativeEngine.nativeRecording(h)
            onProgress(1f, NativeEngine.nativeInputPeak(h))
            CaptureResult(
                recording = recording,
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

    /** Maps the native engine's rate-mismatch marker to the typed exception the controller can act on. */
    private fun startFailure(message: String, requestedRate: Int): CaptureException {
        if (message.startsWith(NativeEngine.RATE_MISMATCH_PREFIX)) {
            val actual = message.removePrefix(NativeEngine.RATE_MISMATCH_PREFIX).trim().toIntOrNull() ?: 0
            return SampleRateMismatchException(requestedRate, actual)
        }
        // The native side reports everything else as a free-form string; the UI keeps it as technical detail.
        return CaptureException(message)
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
            .setBufferSizeInBytes(max(outMin, fs / 4 * 8)) // ~0.25 s: no underruns over UI hiccups
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            runCatching { record.release() }
            runCatching { track.release() }
            throw CaptureException("speaker output unavailable at $fs Hz", kind = CaptureException.Kind.SPEAKER_UNAVAILABLE)
        }
        // Keep the stimulus on the route that was verified before the start (preference, see CaptureConfig).
        config.outputDevice?.let { runCatching { track.setPreferredDevice(it) } }

        val total = CaptureTiming.totalFrames(stimulus.size, fs, config.extraRecordSeconds)
        val rec = FloatArray(total)
        val recPos = AtomicInteger(0)
        val peakBits = AtomicInteger(0f.toRawBits())
        val inputStalls = AtomicInteger(0)
        val failure = AtomicReference<Throwable?>(null)

        val recorder = Thread({
            try {
                val chunk = FloatArray(1024)
                var pos = 0
                var peak = 0f
                while (pos < total && failure.get() == null) {
                    val want = minOf(chunk.size, total - pos)
                    val t0 = System.nanoTime()
                    val n = record.read(chunk, 0, want, AudioRecord.READ_BLOCKING)
                    val elapsedMs = (System.nanoTime() - t0) / 1_000_000.0
                    if (n < 0) throw CaptureException("AudioRecord.read error $n", kind = CaptureException.Kind.AUDIO_IO)
                    for (i in 0 until n) {
                        rec[pos + i] = chunk[i]
                        peak = max(peak, abs(chunk[i]))
                    }
                    pos += n
                    peakBits.set(peak.toRawBits())
                    recPos.set(pos)
                    // AudioRecord has no xrun counter. A blocking read that returns *far* later than the
                    // frames it delivered mean the capture thread was stalled; the ring buffer (0.5 s) has
                    // then overflowed and audio was dropped. Deliberately conservative (3x + 40 ms) so a
                    // normal scheduling hiccup does not get reported as a glitch.
                    if (n > 0) {
                        val expectedMs = n * 1000.0 / fs
                        if (elapsedMs > 3.0 * expectedMs + INPUT_STALL_MS) inputStalls.incrementAndGet()
                    }
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
                    if (w < 0) throw CaptureException("AudioTrack.write error $w", kind = CaptureException.Kind.AUDIO_IO)
                    pos += frames
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            }
        }, "AutoVybz-play")

        try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw CaptureException("microphone busy (another app is recording?)", kind = CaptureException.Kind.MIC_BUSY)
            recorder.start()
            track.play()
            player.start()
            val timeoutMs = CaptureTiming.timeoutMillis(total, fs)
            val t0 = System.currentTimeMillis()
            while (recPos.get() < total) {
                failure.get()?.let { throw it as? CaptureException ?: CaptureException(it.message ?: "audio error", it) }
                if (!coroutineContext.isActive) throw CancellationException()
                if (System.currentTimeMillis() - t0 > timeoutMs) throw CaptureException("recording timed out", kind = CaptureException.Kind.RECORD_TIMEOUT)
                onProgress(recPos.get().toFloat() / total, Float.fromBits(peakBits.get()))
                delay(50)
            }
            onProgress(1f, Float.fromBits(peakBits.get()))
            CaptureResult(rec, fs, name, sourceUsed, xruns = track.underrunCount + inputStalls.get())
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
        var observedRate = 0
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
                if (r.state != AudioRecord.STATE_INITIALIZED) {
                    r.release(); continue
                }
                // A HAL that cannot record at the stimulus rate silently returns another rate. Never
                // resample here: the whole analysis is sample-exact. Report the rate instead so the
                // controller can regenerate the stimulus for it.
                if (r.sampleRate != fs) {
                    if (observedRate == 0) observedRate = r.sampleRate
                    r.release(); continue
                }
                // Pin the requested input (USB measurement mic or the built-in primary mic) - a
                // preference, so a device that vanished in the meantime just falls back to default.
                config.inputDevice?.let { runCatching { r.setPreferredDevice(it) } }
                return r to src
            } catch (t: Throwable) {
                lastError = t
            }
        }
        if (observedRate > 0) throw SampleRateMismatchException(fs, observedRate)
        throw CaptureException("Cannot open microphone at $fs Hz", lastError, CaptureException.Kind.MIC_OPEN_FAILED)
    }

    private fun androidSource(src: InputSource) = when (src) {
        InputSource.UNPROCESSED, InputSource.USB -> MediaRecorder.AudioSource.UNPROCESSED
        InputSource.VOICE_RECOGNITION -> MediaRecorder.AudioSource.VOICE_RECOGNITION
        InputSource.MIC -> MediaRecorder.AudioSource.MIC
    }

    private companion object {
        /** Extra read latency (ms) tolerated on top of 3x the delivered frames before counting a stall. */
        const val INPUT_STALL_MS = 40.0
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
        } catch (t: Throwable) {
            // Not just CaptureException: a broken native library surfaces as LinkageError
            // (UnsatisfiedLinkError / NoClassDefFoundError) from the JNI layer, and that must not cost the
            // user the measurement. Cancellation and JVM-level failures are not recoverable, so they are
            // rethrown; everything else gets the Java engine.
            if (t is CancellationException || t is VirtualMachineError) throw t
            android.util.Log.w(
                "AutoVybz",
                "native capture failed (${t::class.java.simpleName}: ${t.message}), falling back to the Java engine",
                t,
            )
            onProgress(0f, 0f) // reset the progress bar for the second attempt
            fallback.capture(stimulus, config, onProgress)
        }
}
