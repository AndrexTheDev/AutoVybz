package com.andrexthedev.autovybz.measurement.audio

import com.andrexthedev.autovybz.measurement.audio.RouteClassifier.Route
import com.andrexthedev.autovybz.measurement.audio.RouteClassifier.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteClassifierTest {

    private val speaker = Route(RouteClassifier.TYPE_BUILTIN_SPEAKER, "Phone speaker")
    private val bt = Route(RouteClassifier.TYPE_BLUETOOTH_A2DP, "JBL Flip 6")
    private val wired = Route(RouteClassifier.TYPE_WIRED_HEADPHONES)
    private val hdmi = Route(RouteClassifier.TYPE_HDMI, "TV")

    @Test
    fun headphonesAreAlwaysBlocked() {
        assertEquals(Verdict.BLOCKED_HEADPHONES, RouteClassifier.decide(null, listOf(speaker, wired)).verdict)
        assertEquals(Verdict.BLOCKED_HEADPHONES, RouteClassifier.decide(listOf(Route(RouteClassifier.TYPE_BLE_HEADSET)), emptyList()).verdict)
        assertEquals(Verdict.BLOCKED_HEADPHONES, RouteClassifier.decide(listOf(Route(RouteClassifier.TYPE_USB_HEADSET)), emptyList()).verdict)
        assertFalse(RouteClassifier.decide(listOf(speaker, wired), emptyList()).canMeasure)
    }

    @Test
    fun builtInSpeakerIsBlockedExternalAllowed() {
        assertEquals(Verdict.BLOCKED_BUILTIN_SPEAKER, RouteClassifier.decide(null, listOf(speaker)).verdict)
        val btDecision = RouteClassifier.decide(null, listOf(speaker, bt))
        assertEquals(Verdict.NEEDS_SPEAKER_CONFIRMATION, btDecision.verdict)
        assertEquals("JBL Flip 6", btDecision.route!!.name)
        assertTrue(btDecision.canMeasure)
        assertEquals(Verdict.OK, RouteClassifier.decide(listOf(hdmi), listOf(speaker, hdmi)).verdict)
        // active route reported by the platform beats the connected list
        assertEquals(Verdict.BLOCKED_BUILTIN_SPEAKER, RouteClassifier.decide(listOf(speaker), listOf(speaker, bt)).verdict)
        assertEquals(Verdict.UNKNOWN, RouteClassifier.decide(emptyList(), emptyList()).verdict)
    }

    @Test
    fun builtinSpeakerActiveOnlyForBuiltinRoute() {
        // Exactly the route a device-speaker calibration needs (its own speaker will play).
        assertTrue(RouteClassifier.decide(null, listOf(speaker)).builtinSpeakerActive)
        assertTrue(RouteClassifier.decide(listOf(speaker), listOf(speaker, bt)).builtinSpeakerActive)
        // Anything external will take the playback instead.
        assertFalse(RouteClassifier.decide(null, listOf(speaker, bt)).builtinSpeakerActive)
        assertFalse(RouteClassifier.decide(null, listOf(speaker, wired)).builtinSpeakerActive)
        assertFalse(RouteClassifier.decide(emptyList(), emptyList()).builtinSpeakerActive)
    }

    @Test
    fun deviceVolumeStepsDownAfterClipping() {
        assertEquals(0.35f, VolumeMath.reduceAfterClipping(VolumeMath.DEVICE_MEASUREMENT_FRACTION), 1e-6f)
        assertEquals(0.2f, VolumeMath.reduceAfterClipping(0.25f), 1e-6f) // floor
        var v = VolumeMath.DEVICE_MEASUREMENT_FRACTION
        repeat(6) { v = VolumeMath.reduceAfterClipping(v) }
        assertEquals(VolumeMath.DEVICE_MIN_FRACTION, v, 1e-6f) // never below the floor
    }

    @Test
    fun volumeMathTargetsSeventyPercent() {
        assertEquals(11, VolumeMath.indexFor(VolumeMath.MEASUREMENT_FRACTION, 0, 15))
        assertEquals(105, VolumeMath.indexFor(0.7f, 0, 150))
        assertEquals(1, VolumeMath.indexFor(-1f, 1, 25))
        assertEquals(0.5f, VolumeMath.fractionOf(5, 0, 10), 1e-6f)
        assertEquals(0f, VolumeMath.fractionOf(3, 4, 4), 0f)
    }
}
