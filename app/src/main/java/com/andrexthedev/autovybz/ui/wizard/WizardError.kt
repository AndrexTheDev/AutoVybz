package com.andrexthedev.autovybz.ui.wizard

import com.andrexthedev.autovybz.measurement.audio.CaptureException

enum class WizardError {
    PERMISSION_DENIED,
    NO_SIGNAL,
    /** Generic capture failure; the technical message stays in [WizardStep.Error.detail]. */
    CAPTURE_FAILED,
    /** No microphone data within the watchdog window. */
    CAPTURE_TIMEOUT,
    /** Another app / a call holds the microphone. */
    MIC_BUSY,
    /** Microphone and speaker cannot agree on the stimulus sample rate. */
    SAMPLE_RATE,
    /** The output device refused the stimulus rate (no playback possible at all). */
    SPEAKER_UNAVAILABLE,
    ROUTE_CHANGED,
    INTERRUPTED,
    UNKNOWN,
    ;

    companion object {
        /**
         * Maps a capture failure to the text the *user* sees. The exception messages are English technical
         * strings; putting them into a translated sentence produced a German screen with "Cannot open
         * microphone at 48000 Hz" in the middle of it. The raw message is still shown, but as a labelled
         * technical detail line (see the error screen).
         */
        fun of(e: CaptureException): WizardError = when (e.kind) {
            CaptureException.Kind.NATIVE_UNAVAILABLE -> CAPTURE_FAILED
            CaptureException.Kind.RECORD_TIMEOUT -> CAPTURE_TIMEOUT
            CaptureException.Kind.SPEAKER_UNAVAILABLE -> SPEAKER_UNAVAILABLE
            CaptureException.Kind.MIC_OPEN_FAILED -> CAPTURE_FAILED
            CaptureException.Kind.MIC_BUSY -> MIC_BUSY
            CaptureException.Kind.AUDIO_IO -> CAPTURE_FAILED
            CaptureException.Kind.SAMPLE_RATE -> SAMPLE_RATE
            CaptureException.Kind.OTHER -> CAPTURE_FAILED
        }
    }
}
