package com.andrexthedev.autovybz.e2e

import com.andrexthedev.autovybz.measurement.audio.CaptureConfig
import com.andrexthedev.autovybz.measurement.audio.CaptureException
import com.andrexthedev.autovybz.measurement.audio.CaptureInterruptedException
import com.andrexthedev.autovybz.measurement.audio.CaptureResult
import com.andrexthedev.autovybz.measurement.audio.MeasurementAudioEngine
import com.andrexthedev.autovybz.measurement.eq.PeqBand
import com.andrexthedev.autovybz.measurement.eq.PeqType
import com.andrexthedev.autovybz.testutil.OutdoorNoise
import com.andrexthedev.autovybz.testutil.SpeakerSimulator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Replaces speaker + room + microphone for the on-device end-to-end tests: the stimulus is "played"
 * through the same time-domain simulator the JVM tests use, so the real analyzer, auto-EQ, preset store
 * and UI run unchanged. Each capture consumes one [Mode] from [script] (default [Mode.NORMAL]).
 */
class SimulatedRoomEngine : MeasurementAudioEngine {

    enum class Mode {
        /** Typical small Bluetooth speaker in a living room. */
        NORMAL,
        /** Strong low-frequency wind noise (outdoors). */
        WINDY,
        /** 80 ms Bluetooth dropout during the pink noise. */
        DROPOUT,
        /** Microphone records silence (muted mic / privacy switch). */
        SILENCE,
        /** Audio driver error. */
        FAIL,
        /** Output route changes during the capture (Bluetooth speaker disconnects). */
        ROUTE_CHANGE,
        /** Capture works, but with buffer under-/overruns. */
        GLITCH,
        /** Dreadful cheap Bluetooth box: no bass below ~260 Hz, boom, shout, dull top (rescue mode). */
        CHEAP_SPEAKER,
    }

    override val name = "Simulated room (E2E)"

    val script = ConcurrentLinkedQueue<Mode>()
    val captures = AtomicInteger()

    /** If set, every capture waits here after the first half (to inspect / interrupt the "Measuring" state). */
    @Volatile var hold: CompletableDeferred<Unit>? = null

    /** Progress pacing; keeps the measuring screen visible long enough for screenshots. */
    @Volatile var stepDelayMs: Long = 25

    fun reset() {
        script.clear(); captures.set(0); hold = null; stepDelayMs = 25
    }

    private val speaker = listOf(
        PeqBand(PeqType.PEAK, 140.0, 5.0, 1.1),
        PeqBand(PeqType.PEAK, 2500.0, -4.0, 1.6),
        PeqBand(PeqType.HIGH_SHELF, 9000.0, -3.0, 0.7),
    )

    /** The [Mode.CHEAP_SPEAKER] colouration on top of its 260 Hz bass cut-off. */
    private val cheapSpeaker = listOf(
        PeqBand(PeqType.PEAK, 320.0, 7.0, 1.0),
        PeqBand(PeqType.PEAK, 3200.0, 6.0, 1.5),
        PeqBand(PeqType.HIGH_SHELF, 7000.0, -8.0, 0.7),
    )

    override suspend fun capture(
        stimulus: FloatArray,
        config: CaptureConfig,
        onProgress: (progress: Float, inputPeak: Float) -> Unit,
    ): CaptureResult = withContext(Dispatchers.Default) {
        val n = captures.incrementAndGet()
        val mode = script.poll() ?: Mode.NORMAL
        val peak = if (mode == Mode.SILENCE) 0f else 0.32f
        for (i in 1..10) { onProgress(i / 20f, peak); delay(stepDelayMs) }
        hold?.await()
        when (mode) {
            Mode.FAIL -> throw CaptureException("simulated audio driver error")
            Mode.ROUTE_CHANGE -> throw CaptureInterruptedException(CaptureInterruptedException.Reason.ROUTE_CHANGED, "simulated route change")
            else -> Unit
        }
        for (i in 11..20) { onProgress(i / 20f, peak); delay(stepDelayMs) }
        val fs = config.sampleRate
        val recording = if (mode == Mode.SILENCE) {
            FloatArray(stimulus.size + fs)
        } else {
            // A slightly different ground reflection per capture = a different microphone position.
            val (bands, cutOffHz) = if (mode == Mode.CHEAP_SPEAKER) cheapSpeaker to 260.0 else speaker to 65.0
            var rec = SpeakerSimulator(
                sampleRate = fs, bands = bands, highPassHz = cutOffHz, gain = 0.3, latencySeconds = 0.18,
                reflectionDelaySeconds = 0.0015 + 0.0007 * (n % 5), reflectionGain = 0.5, noiseRms = 1e-4,
                extraTailSeconds = 1.0, seed = 40 + n,
            ).play(stimulus)
            if (mode == Mode.WINDY) rec = OutdoorNoise.addWind(rec, fs, rms = 0.03, seed = n)
            if (mode == Mode.DROPOUT) rec = OutdoorNoise.addDropout(rec, fs, atSecond = 2.2, seconds = 0.08)
            rec
        }
        CaptureResult(recording, fs, name, config.source, xruns = if (mode == Mode.GLITCH) 6 else 0)
    }
}
