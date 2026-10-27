package com.andrexthedev.autovybz.measurement.audio

import com.andrexthedev.autovybz.measurement.dsp.FftEngine
import com.andrexthedev.autovybz.measurement.dsp.KotlinFft

/**
 * JNI bindings to libautovybz (src/main/cpp/autovybz): Oboe full-duplex
 * measurement engine + native FFT. Every entry point degrades gracefully when
 * the library cannot be loaded (e.g. exotic ABI) - callers check [isAvailable].
 */
object NativeEngine : FftEngine {

    val isAvailable: Boolean by lazy {
        try {
            System.loadLibrary("autovybz"); true
        } catch (_: Throwable) {
            false
        }
    }

    override val name: String get() = if (isAvailable) "C++" else KotlinFft.name

    override fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        if (!isAvailable) return KotlinFft.transform(re, im, inverse)
        val ok = synchronized(this) { nativeFft(re, im, inverse) }
        require(ok) { "native FFT rejected size ${re.size}" }
    }

    /** Engine states, must match avz::EngineState. */
    const val STATE_IDLE = 0
    const val STATE_RUNNING = 1
    const val STATE_FINISHED = 2
    const val STATE_ERROR = 3

    /** oboe::InputPreset values. */
    const val PRESET_GENERIC = 1
    const val PRESET_VOICE_RECOGNITION = 6
    const val PRESET_UNPROCESSED = 9

    /**
     * Prefix of the start error when a stream cannot run at the requested sample rate; the rest of the
     * message is the rate the device offered (see `MeasurementEngine::start` in measurement_engine.cpp).
     * Kept in sync with `avz::kRateMismatchPrefix`.
     */
    const val RATE_MISMATCH_PREFIX = "rate-mismatch:"

    @JvmStatic external fun nativeFft(re: DoubleArray, im: DoubleArray, inverse: Boolean): Boolean
    @JvmStatic external fun nativeCreate(): Long
    @JvmStatic external fun nativeDestroy(handle: Long)
    @JvmStatic external fun nativeStart(
        handle: Long, stimulus: FloatArray, sampleRate: Int, inputPreset: Int,
        inputDeviceId: Int, outputDeviceId: Int, extraSeconds: Double, gain: Float,
    ): String?
    @JvmStatic external fun nativeStop(handle: Long)
    @JvmStatic external fun nativeState(handle: Long): Int
    @JvmStatic external fun nativeProgress(handle: Long): Float
    @JvmStatic external fun nativeInputPeak(handle: Long): Float
    @JvmStatic external fun nativeXruns(handle: Long): Int
    @JvmStatic external fun nativeActualPreset(handle: Long): Int
    @JvmStatic external fun nativeLastError(handle: Long): String?
    @JvmStatic external fun nativeRecording(handle: Long): FloatArray
}
