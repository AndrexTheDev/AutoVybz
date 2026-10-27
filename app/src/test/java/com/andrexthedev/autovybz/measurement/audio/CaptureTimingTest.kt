package com.andrexthedev.autovybz.measurement.audio

import com.andrexthedev.autovybz.measurement.dsp.AnalyzerConfig
import com.andrexthedev.autovybz.measurement.dsp.MeasurementWarning
import com.andrexthedev.autovybz.measurement.dsp.StimulusGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Recording tail budget and the capture-level warnings (xruns, clipping). */
class CaptureTimingTest {

    private val fs = 48000
    private val plan = StimulusGenerator.plan(fs)

    @Test
    fun defaultBudgetCoversHighLatencyBluetooth() {
        val config = AnalyzerConfig()
        val tail = CaptureTiming.tailSeconds(config.maxLatencySeconds, config.irPostSeconds)
        // 0.8 s latency + 0.3 s IR window + 0.2 s margin
        assertEquals(1.3, tail, 1e-9)
        // The old hard-coded 1.0 s tail ended inside the response of a 0.8 s route.
        assertTrue(tail > 1.0)
    }

    @Test
    fun tailStaysWithinTheTimeBudget() {
        assertEquals(CaptureTiming.MIN_TAIL_SECONDS, CaptureTiming.tailSeconds(0.0, 0.1), 1e-9)
        assertEquals(CaptureTiming.MAX_TAIL_SECONDS, CaptureTiming.tailSeconds(3.0, 1.0), 1e-9)
        val short = CaptureTiming.tailSeconds(0.25, 0.30)
        val long = CaptureTiming.tailSeconds(0.60, 0.30)
        assertTrue(long > short)
    }

    @Test
    fun framesAndTimeoutFollowFromTheTail() {
        val tail = CaptureTiming.tailSeconds(0.8, 0.3)
        val total = CaptureTiming.totalFrames(plan.totalLength, fs, tail)
        assertEquals((plan.totalLength + tail * fs).toDouble(), total.toDouble(), 1.0)
        assertEquals(11.3, total.toDouble() / fs, 0.01)
        // 11.3 s recording + 4 s startup grace
        assertEquals(11_300L + CaptureTiming.STARTUP_GRACE_MS, CaptureTiming.timeoutMillis(total, fs))
        assertEquals(10.0, CaptureTiming.stimulusSeconds(plan.totalLength, fs), 1e-9)
    }

    @Test
    fun aFewXrunsAreToleratedButMoreMeanAGlitch() {
        assertTrue(CaptureAudit.warnings(xruns = 0, clipped = false).isEmpty())
        assertTrue(CaptureAudit.warnings(xruns = CaptureAudit.XRUN_TOLERANCE, clipped = false).isEmpty())
        assertEquals(
            listOf(MeasurementWarning.AUDIO_GLITCH),
            CaptureAudit.warnings(xruns = CaptureAudit.XRUN_TOLERANCE + 1, clipped = false),
        )
    }

    @Test
    fun clippingIsReportedFromTheRawSamples() {
        assertEquals(listOf(MeasurementWarning.CLIPPING), CaptureAudit.warnings(xruns = 0, clipped = true))
        // Both problems at once, in a stable order (warnings are a set in the result anyway).
        assertEquals(
            listOf(MeasurementWarning.AUDIO_GLITCH, MeasurementWarning.CLIPPING),
            CaptureAudit.warnings(xruns = 10, clipped = true),
        )
    }

    @Test
    fun aSingleHotSampleIsNotClipping() {
        val quiet = FloatArray(10_000) { 0.2f }
        assertFalse(CaptureAudit.isClipped(quiet))
        val click = quiet.copyOf()
        click[5_000] = 1.0f
        assertFalse("one full-scale sample is a click, not clipping", CaptureAudit.isClipped(click))
        val spike = quiet.copyOf()
        for (i in 0 until 2) spike[5_000 + i] = 0.99f
        assertFalse(CaptureAudit.isClipped(spike))
    }

    @Test
    fun clippedRunsAndScatteredFullScaleSamplesAreDetected() {
        val quiet = FloatArray(10_000) { 0.2f }
        val run = quiet.copyOf()
        for (i in 0 until CaptureAudit.CLIP_MIN_RUN) run[4_000 + i] = -1.0f
        assertTrue("a clipped sine produces runs", CaptureAudit.isClipped(run))

        val scattered = quiet.copyOf()
        for (i in 0 until CaptureAudit.CLIP_MIN_SAMPLES) scattered[i * 500] = 0.985f
        assertTrue("many full-scale samples", CaptureAudit.isClipped(scattered))

        assertFalse(CaptureAudit.isClipped(FloatArray(0)))
    }
}
