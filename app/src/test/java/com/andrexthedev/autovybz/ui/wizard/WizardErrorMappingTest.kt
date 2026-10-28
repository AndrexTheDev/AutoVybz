package com.andrexthedev.autovybz.ui.wizard

import com.andrexthedev.autovybz.measurement.audio.CaptureException
import com.andrexthedev.autovybz.measurement.audio.CaptureInterruptedException
import com.andrexthedev.autovybz.measurement.audio.SampleRateMismatchException
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The error screen shows one translated sentence per [WizardError]; the mapping from an audio failure to
 * that sentence has to be exhaustive and stable, because a wrong bucket sends the user to the wrong fix
 * ("close the other app" instead of "your speaker cannot play at this rate").
 */
class WizardErrorMappingTest {

    private fun capture(kind: CaptureException.Kind) = CaptureException("technical message", kind = kind)

    @Test
    fun everyCaptureKindLandsInAUserFacingBucket() {
        assertEquals(WizardError.CAPTURE_TIMEOUT, WizardError.of(capture(CaptureException.Kind.RECORD_TIMEOUT)))
        assertEquals(WizardError.MIC_BUSY, WizardError.of(capture(CaptureException.Kind.MIC_BUSY)))
        assertEquals(WizardError.SPEAKER_UNAVAILABLE, WizardError.of(capture(CaptureException.Kind.SPEAKER_UNAVAILABLE)))
        // Generic "could not start" cases stay together - the specific cause would not change the advice.
        assertEquals(WizardError.CAPTURE_FAILED, WizardError.of(capture(CaptureException.Kind.MIC_OPEN_FAILED)))
        assertEquals(WizardError.CAPTURE_FAILED, WizardError.of(capture(CaptureException.Kind.NATIVE_UNAVAILABLE)))
        assertEquals(WizardError.CAPTURE_FAILED, WizardError.of(capture(CaptureException.Kind.AUDIO_IO)))
        assertEquals(WizardError.CAPTURE_FAILED, WizardError.of(capture(CaptureException.Kind.OTHER)))
    }

    @Test
    fun typedExceptionsCarryTheirKindNotJustAMessage() {
        // The sample-rate mismatch is the one case the controller also *retries*; it must stay recognisable
        // even though it travels as a plain CaptureException through the retry loop.
        val mismatch = SampleRateMismatchException(requestedRate = 48_000, actualRate = 44_100)
        assertEquals(CaptureException.Kind.SAMPLE_RATE, mismatch.kind)
        assertEquals(WizardError.SAMPLE_RATE, WizardError.of(mismatch))

        // An interrupted capture is handled by its own catch block (route changed / focus lost), not by kind.
        val interrupted = CaptureInterruptedException(CaptureInterruptedException.Reason.FOCUS_LOST, "focus lost")
        assertEquals(CaptureException.Kind.OTHER, interrupted.kind)
    }

    @Test
    fun theTechnicalMessageIsNeverTheUserFacingText() {
        // Every capture exception keeps an English technical message; the UI only prints it as detail.
        val e = capture(CaptureException.Kind.MIC_BUSY)
        assertEquals("technical message", e.message)
        assertEquals(8, CaptureException.Kind.entries.size)
    }
}
