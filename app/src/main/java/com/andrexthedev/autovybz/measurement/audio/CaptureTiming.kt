package com.andrexthedev.autovybz.measurement.audio

import com.andrexthedev.autovybz.measurement.dsp.MeasurementWarning
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Timing budget of one capture (pure Kotlin, unit-tested).
 *
 * The recording must contain the whole stimulus **plus** enough tail for
 *  * the output/Bluetooth latency (the response arrives that much later than the stimulus),
 *  * the impulse-response window used for the sweep magnitude ([irPostSeconds]),
 *  * a room-decay margin.
 *
 * The tail was a hard-coded 1.0 s. That is enough for wired routes but too short for laggy Bluetooth:
 * with a 0.8 s route latency plus the 0.3 s IR window the recording ends *inside* the response, so the
 * second (tail) noise-floor snapshot is skipped and the freshest part of the sweep is cut off. The tail
 * is therefore derived from the analysis budget instead of guessed.
 */
object CaptureTiming {

    /** Never record less than this after the stimulus (covers short wired routes generously). */
    const val MIN_TAIL_SECONDS = 1.0

    /** Upper bound: 2.5 s tail on top of the 10 s stimulus keeps a run at ~12.5 s. */
    const val MAX_TAIL_SECONDS = 2.5

    /** Extra slack on top of latency + IR window (scheduling jitter, reverb). */
    const val MARGIN_SECONDS = 0.20

    /** Time the engine gets to open the streams before the watchdog fires, on top of the recording time. */
    const val STARTUP_GRACE_MS = 4000L

    /**
     * Seconds recorded after the stimulus: latency + IR window + margin, clamped to
     * [MIN_TAIL_SECONDS]..[MAX_TAIL_SECONDS].
     */
    fun tailSeconds(maxLatencySeconds: Double, irPostSeconds: Double): Double {
        require(maxLatencySeconds >= 0.0) { "negative latency budget" }
        require(irPostSeconds > 0.0) { "IR window must be positive" }
        return (maxLatencySeconds + irPostSeconds + MARGIN_SECONDS)
            .coerceIn(MIN_TAIL_SECONDS, MAX_TAIL_SECONDS)
    }

    /** Frames the engine has to record for a stimulus of [stimulusFrames]. */
    fun totalFrames(stimulusFrames: Int, sampleRate: Int, tailSeconds: Double): Int {
        require(stimulusFrames > 0 && sampleRate > 0 && tailSeconds >= 0.0)
        return stimulusFrames + (tailSeconds * sampleRate).roundToInt()
    }

    /** Playback-only length of the stimulus in seconds (used for progress + engine timeouts). */
    fun stimulusSeconds(stimulusFrames: Int, sampleRate: Int): Double =
        stimulusFrames.toDouble() / sampleRate

    /** Watchdog for one capture: recording time plus the startup grace. */
    fun timeoutMillis(totalFrames: Int, sampleRate: Int): Long {
        require(totalFrames > 0 && sampleRate > 0)
        return totalFrames * 1000L / sampleRate + STARTUP_GRACE_MS
    }
}

/**
 * Turns the raw capture into the warnings the user sees (pure Kotlin, unit-tested).
 *
 * Two checks live here because they belong to the capture, not to the analysis:
 *
 *  * **Buffer over-/underruns.** Gaps in playback or recording corrupt the deconvolution in a way the
 *    analysis cannot see reliably (the sweep still "works", just with holes), so they are reported from
 *    the engine counters. A couple of xruns while the streams start up are normal.
 *  * **Clipping.** Counted on the *raw* samples, before the analyzer removes the DC offset - a clipped
 *    recording with an offset can end up below full scale after that correction and would then slip
 *    through the analyzer's peak check. A single hot sample is not clipping; a run of them (or many
 *    scattered ones) is.
 */
object CaptureAudit {

    /** A couple of xruns at stream start are normal; more mean audible gaps. */
    const val XRUN_TOLERANCE = 2

    /** |x| at or above this counts as full scale (float capture never reaches exactly 1.0). */
    const val CLIP_THRESHOLD = 0.98f

    /** Consecutive clipped samples that make a clip event (a clipped sine produces long runs). */
    const val CLIP_MIN_RUN = 3

    /** Scattered clipped samples that still count as clipping (a hard hit/click that hit full scale). */
    const val CLIP_MIN_SAMPLES = 8

    /** True when [recording] hit full scale hard enough to distort the measurement. */
    fun isClipped(
        recording: FloatArray,
        threshold: Float = CLIP_THRESHOLD,
        minRun: Int = CLIP_MIN_RUN,
        minSamples: Int = CLIP_MIN_SAMPLES,
    ): Boolean {
        require(threshold > 0f && minRun > 0 && minSamples > 0)
        var total = 0
        var run = 0
        for (v in recording) {
            if (abs(v) >= threshold) {
                total++
                run++
                if (run >= minRun) return true
            } else {
                run = 0
            }
        }
        return total >= minSamples
    }

    /**
     * Warnings contributed by the capture layer. [xrunTolerance] is exposed for tests; production uses
     * [XRUN_TOLERANCE].
     */
    fun warnings(
        xruns: Int,
        clipped: Boolean,
        xrunTolerance: Int = XRUN_TOLERANCE,
    ): List<MeasurementWarning> = buildList {
        if (xruns > xrunTolerance) add(MeasurementWarning.AUDIO_GLITCH)
        if (clipped) add(MeasurementWarning.CLIPPING)
    }
}
