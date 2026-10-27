package com.andrexthedev.autovybz.measurement.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stimulus is generated for one sample rate and the capture must run at exactly that rate. These
 * cases are the ones that used to fail with "Cannot open microphone at 48000 Hz" on 44.1 kHz-only
 * devices, or that silently made Oboe resample both directions.
 */
class CaptureRatesTest {

    @Test
    fun nativeRateWinsSoNothingIsResampled() {
        // A 44.1 kHz device: measuring at 44.1 kHz avoids SRC in playback *and* capture.
        val rate = CaptureRates.choose(CaptureRates.PRIMARY, 44100) { true }
        assertEquals(CaptureRates.FALLBACK, rate)
    }

    @Test
    fun planRateIsKeptWhenTheNativeRateIsExotic() {
        // 96000 would truncate headroom/analysis expectations; stay on the plan rate.
        assertEquals(CaptureRates.PRIMARY, CaptureRates.choose(CaptureRates.PRIMARY, 96000) { true })
        assertEquals(CaptureRates.PRIMARY, CaptureRates.choose(CaptureRates.PRIMARY, null) { true })
        assertEquals(CaptureRates.PRIMARY, CaptureRates.choose(CaptureRates.PRIMARY, 0) { true })
    }

    @Test
    fun unsupportedNativeRateFallsThroughToWhatTheCaptureHalAccepts() {
        val accepted = setOf(44100)
        assertEquals(CaptureRates.FALLBACK, CaptureRates.choose(CaptureRates.PRIMARY, 48000) { it in accepted })
    }

    @Test
    fun nothingSupportedKeepsThePlanRateForAFailureWithANumber() {
        // Returning the plan rate (not 0/null) keeps the error message meaningful; the controller then
        // retries with the rate the HAL reported.
        assertEquals(CaptureRates.PRIMARY, CaptureRates.choose(CaptureRates.PRIMARY, null) { false })
    }

    @Test
    fun supportedRatesAndAlternatives() {
        assertTrue(CaptureRates.isUsable(48000))
        assertTrue(CaptureRates.isUsable(44100))
        assertFalse(CaptureRates.isUsable(16000))
        assertFalse(CaptureRates.isUsable(0))
        assertEquals(44100, CaptureRates.alternativeTo(48000))
        assertEquals(48000, CaptureRates.alternativeTo(44100))
        assertNull(CaptureRates.alternativeTo(22050))
        assertEquals(2, CaptureRates.SUPPORTED.size)
    }
}
