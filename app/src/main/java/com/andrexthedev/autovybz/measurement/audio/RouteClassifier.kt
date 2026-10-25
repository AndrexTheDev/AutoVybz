package com.andrexthedev.autovybz.measurement.audio

import kotlin.math.roundToInt

/**
 * Pure decision logic for "may we measure on this output route?".
 * Constants mirror android.media.AudioDeviceInfo.TYPE_* so this file has no
 * Android dependency and is JVM-unit-tested.
 *
 * Policy (AutoVybz measures *external speakers only*):
 *  - headphones / headsets / hearing aids -> blocked (pointless + hearing safety)
 *  - phone's own speaker / earpiece       -> blocked (mic sits millimetres from it)
 *  - Bluetooth A2DP / USB audio / broadcast -> allowed after the user confirms it is a speaker
 *    (Android cannot reliably tell BT headphones from BT speakers without extra permissions)
 *  - HDMI / line out / dock / BLE speaker -> allowed
 */
object RouteClassifier {

    const val TYPE_BUILTIN_EARPIECE = 1
    const val TYPE_BUILTIN_SPEAKER = 2
    const val TYPE_WIRED_HEADSET = 3
    const val TYPE_WIRED_HEADPHONES = 4
    const val TYPE_LINE_ANALOG = 5
    const val TYPE_LINE_DIGITAL = 6
    const val TYPE_BLUETOOTH_SCO = 7
    const val TYPE_BLUETOOTH_A2DP = 8
    const val TYPE_HDMI = 9
    const val TYPE_HDMI_ARC = 10
    const val TYPE_USB_DEVICE = 11
    const val TYPE_USB_ACCESSORY = 12
    const val TYPE_DOCK = 13
    const val TYPE_AUX_LINE = 19
    const val TYPE_USB_HEADSET = 22
    const val TYPE_HEARING_AID = 23
    const val TYPE_BUILTIN_SPEAKER_SAFE = 24
    const val TYPE_BLE_HEADSET = 26
    const val TYPE_BLE_SPEAKER = 27
    const val TYPE_HDMI_EARC = 29
    const val TYPE_BLE_BROADCAST = 30
    const val TYPE_DOCK_ANALOG = 31

    enum class Verdict { OK, NEEDS_SPEAKER_CONFIRMATION, BLOCKED_HEADPHONES, BLOCKED_BUILTIN_SPEAKER, UNKNOWN }

    enum class RouteKind { HEADPHONES, BUILTIN, BLUETOOTH, USB, HDMI, LINE, DOCK, BLE_SPEAKER, OTHER }

    data class Route(val type: Int, val name: String = "") {
        val kind: RouteKind get() = kindOf(type)
    }

    data class Decision(val verdict: Verdict, val route: Route?) {
        val canMeasure: Boolean get() = verdict == Verdict.OK || verdict == Verdict.NEEDS_SPEAKER_CONFIRMATION

        /**
         * True when media playback will come out of the phone's own speaker: the built-in route
         * won and no headphones are on the path. Blocks normal measurements, but is exactly the
         * route a device-speaker calibration ([com.andrexthedev.autovybz.measurement.SpeakerClass.DEVICE])
         * needs.
         */
        val builtinSpeakerActive: Boolean get() = verdict == Verdict.BLOCKED_BUILTIN_SPEAKER
    }

    fun kindOf(type: Int): RouteKind = when (type) {
        TYPE_WIRED_HEADSET, TYPE_WIRED_HEADPHONES, TYPE_USB_HEADSET, TYPE_HEARING_AID,
        TYPE_BLE_HEADSET, TYPE_BLUETOOTH_SCO -> RouteKind.HEADPHONES
        TYPE_BUILTIN_SPEAKER, TYPE_BUILTIN_EARPIECE, TYPE_BUILTIN_SPEAKER_SAFE -> RouteKind.BUILTIN
        TYPE_BLUETOOTH_A2DP, TYPE_BLE_BROADCAST -> RouteKind.BLUETOOTH
        TYPE_USB_DEVICE, TYPE_USB_ACCESSORY -> RouteKind.USB
        TYPE_HDMI, TYPE_HDMI_ARC, TYPE_HDMI_EARC -> RouteKind.HDMI
        TYPE_LINE_ANALOG, TYPE_LINE_DIGITAL, TYPE_AUX_LINE -> RouteKind.LINE
        TYPE_DOCK, TYPE_DOCK_ANALOG -> RouteKind.DOCK
        TYPE_BLE_SPEAKER -> RouteKind.BLE_SPEAKER
        else -> RouteKind.OTHER
    }

    fun verdictFor(route: Route): Verdict = when (route.kind) {
        RouteKind.HEADPHONES -> Verdict.BLOCKED_HEADPHONES
        RouteKind.BUILTIN -> Verdict.BLOCKED_BUILTIN_SPEAKER
        RouteKind.BLUETOOTH, RouteKind.USB, RouteKind.OTHER -> Verdict.NEEDS_SPEAKER_CONFIRMATION
        RouteKind.HDMI, RouteKind.LINE, RouteKind.DOCK, RouteKind.BLE_SPEAKER -> Verdict.OK
    }

    /**
     * Decides from the *active media route* when the platform tells us
     * (API 33+ getAudioDevicesForAttributes), else from all connected outputs
     * using Android's usual routing priority (headset > BT > USB > ... > speaker).
     */
    fun decide(activeRoutes: List<Route>?, connectedOutputs: List<Route>): Decision {
        val candidates = activeRoutes?.takeIf { it.isNotEmpty() } ?: connectedOutputs
        if (candidates.isEmpty()) return Decision(Verdict.UNKNOWN, null)
        // Any headphone on the active path wins (e.g. dual routing) -> block.
        candidates.firstOrNull { it.kind == RouteKind.HEADPHONES }?.let { return Decision(Verdict.BLOCKED_HEADPHONES, it) }
        val best = candidates.minByOrNull { priority(it.kind) }!!
        return Decision(verdictFor(best), best)
    }

    private fun priority(kind: RouteKind): Int = when (kind) {
        RouteKind.HEADPHONES -> 0
        RouteKind.BLUETOOTH, RouteKind.BLE_SPEAKER -> 1
        RouteKind.USB -> 2
        RouteKind.HDMI -> 3
        RouteKind.LINE, RouteKind.DOCK -> 4
        RouteKind.OTHER -> 5
        RouteKind.BUILTIN -> 6
    }
}

/** Volume helpers (pure): target ~70 % of STREAM_MUSIC as required by the measurement spec. */
object VolumeMath {
    const val MEASUREMENT_FRACTION = 0.70f

    /**
     * Device-speaker self-measurement: the microphone sits centimetres from the driver, so
     * ~50 % already gives a huge SNR; 70 % could clip the mic and excite chassis rattles
     * that a listener never hears.
     */
    const val DEVICE_MEASUREMENT_FRACTION = 0.50f

    /** Quieter start volume after a clipped device-speaker measurement (floor: still plenty at cm distance). */
    const val DEVICE_MIN_FRACTION = 0.20f

    /** Next start volume after the device mic clipped: −30 % steps down to [DEVICE_MIN_FRACTION]. */
    fun reduceAfterClipping(fraction: Float): Float =
        (fraction * 0.7f).coerceAtLeast(DEVICE_MIN_FRACTION)

    fun indexFor(fraction: Float, min: Int, max: Int): Int {
        require(max >= min)
        val f = fraction.coerceIn(0f, 1f)
        return (min + (max - min) * f).roundToInt().coerceIn(min, max)
    }

    fun fractionOf(index: Int, min: Int, max: Int): Float =
        if (max <= min) 0f else ((index - min).toFloat() / (max - min)).coerceIn(0f, 1f)
}
