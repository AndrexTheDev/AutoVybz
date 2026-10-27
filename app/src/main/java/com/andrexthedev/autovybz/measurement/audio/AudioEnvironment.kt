package com.andrexthedev.autovybz.measurement.audio

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Build
import android.util.Log
import com.andrexthedev.autovybz.measurement.mic.InputSource
import com.bearinmind.equalizer314.audio.EqService
import com.bearinmind.equalizer314.state.EqPreferencesManager

/** Reads the current output route and decides whether a measurement is allowed. */
object RouteInspector {

    fun inspect(context: Context): RouteClassifier.Decision {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val connected = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.toRoute() }
        val active: List<RouteClassifier.Route>? = if (Build.VERSION.SDK_INT >= 33) {
            runCatching {
                val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
                am.getAudioDevicesForAttributes(attrs).map { RouteClassifier.Route(it.type, it.productName?.toString() ?: "") }
            }.getOrNull()
        } else {
            null
        }
        return RouteClassifier.decide(active, connected)
    }

    /**
     * The device Android currently uses for media playback, when the platform reports it (API 33+).
     * Used to pin the measurement output: the capture guard aborts on route changes, but between the
     * check and the stream start another device (headset, HDMI, a second speaker) can become active -
     * the stimulus would then be played somewhere else and the measured curve would be wrong.
     *
     * Returns null below API 33 (no reliable query) or when the platform gives no answer; the engines
     * then keep the system default route.
     */
    @SuppressLint("NewApi") // guarded by the SDK_INT check
    fun activeOutputDevice(context: Context): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < 33) return null
        val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return runCatching {
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            am.getAudioDevicesForAttributes(attrs).firstOrNull()
        }.getOrNull()
    }

    private fun AudioDeviceInfo.toRoute() = RouteClassifier.Route(type, productName?.toString() ?: "")
}

/**
 * Queries what the device can actually do, so the stimulus is generated for the rate the capture path
 * really runs at (see [CaptureRates]) instead of assuming 48 kHz.
 */
object AudioCapabilities {

    /** The platform's preferred output rate, in Hz, or null if the property is missing/garbled. */
    fun nativeOutputRate(context: Context): Int? {
        val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.trim()?.toIntOrNull()?.takeIf { it > 0 }
    }

    /**
     * Whether the capture HAL plausibly accepts [rate]. `AudioRecord.getMinBufferSize` is the cheapest
     * probe that does not open a stream (and thus never triggers a microphone permission prompt); a
     * negative result means the format combination is unsupported.
     */
    fun supportsCapture(rate: Int): Boolean = runCatching {
        AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT) > 0
    }.getOrDefault(false)

    /** Rate the stimulus should be generated at for this device. */
    fun preferredCaptureRate(context: Context, planRate: Int = CaptureRates.PRIMARY): Int =
        CaptureRates.choose(planRate, nativeOutputRate(context)) { supportsCapture(it) }
}

/** Chooses the capture path: UNPROCESSED if the HAL advertises it, else VOICE_RECOGNITION. */
object InputSourceResolver {

    fun supportsUnprocessed(context: Context): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)?.toBoolean() == true
    }

    fun preferred(context: Context, usbMic: AudioDeviceInfo?): InputSource = when {
        usbMic != null -> InputSource.USB
        supportsUnprocessed(context) -> InputSource.UNPROCESSED
        else -> InputSource.VOICE_RECOGNITION
    }

    /** First USB input (measurement mics such as miniDSP UMIK-1 / Dayton iMM-6C via USB-C). */
    // TYPE_USB_HEADSET (API 26) is an inlined int; on API 24-25 such mics report TYPE_USB_DEVICE, checked too.
    @SuppressLint("InlinedApi")
    fun findUsbMic(context: Context): AudioDeviceInfo? {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
    }

    /**
     * The phone's primary built-in microphone, pinned explicitly so the capture really uses the
     * microphone the compensation profiles describe - with the system default route an attached
     * accessory (headset/USB dongle with a mic) could silently take over the input. Returns null
     * when no unambiguous built-in mic is listed (system default is then kept).
     */
    fun findBuiltInMic(context: Context): AudioDeviceInfo? {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val candidates = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        val index = builtInMicIndex(candidates.map { it.address ?: "" }) ?: return null
        return candidates[index]
    }

    /**
     * Picks the primary built-in mic among [addresses] (AudioDeviceInfo.getAddress of every
     * TYPE_BUILTIN_MIC input): a single entry wins; with several (multi-mic phones list e.g.
     * "bottom"/"back"), the bottom/main one wins; anything ambiguous returns null (no pinning).
     * Pure for unit tests.
     */
    internal fun builtInMicIndex(addresses: List<String>): Int? {
        if (addresses.isEmpty()) return null
        if (addresses.size == 1) return 0
        val bottom = addresses.indexOfFirst { a ->
            val s = a.lowercase()
            "bottom" in s || "main" in s || "primary" in s
        }
        if (bottom >= 0) return bottom
        val unlabelled = addresses.indexOfFirst { it.isBlank() }
        return if (unlabelled >= 0) unlabelled else null
    }
}

/**
 * Sets STREAM_MUSIC to the measurement level (~70 %) and restores the user's
 * volume afterwards. Fixed-volume devices (e.g. some TV boxes) are left alone.
 */
class VolumeGuard(context: Context) {
    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var saved: Int? = null

    val min: Int get() = am.getStreamMinVolume(AudioManager.STREAM_MUSIC)
    val max: Int get() = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    val current: Int get() = am.getStreamVolume(AudioManager.STREAM_MUSIC)
    val currentFraction: Float get() = VolumeMath.fractionOf(current, min, max)
    val isFixed: Boolean get() = am.isVolumeFixed

    /** Applies [fraction] of the stream range (remembers the previous value once). */
    fun setFraction(fraction: Float) {
        if (isFixed) return
        if (saved == null) saved = current
        runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, VolumeMath.indexFor(fraction, min, max), 0) }
            .onFailure { Log.w("AutoVybz", "setStreamVolume failed (DND / policy?)", it) }
    }

    fun restore() {
        val v = saved ?: return
        runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0) }
        saved = null
    }
}

/**
 * The global Equalizer314 DynamicsProcessing would colour the measurement, so it
 * is paused for the 10 s and resumed afterwards ("EQ paused during measurement").
 */
object EqBypass {
    fun pauseIfRunning(context: Context): Boolean {
        if (!EqService.isDpRunning) return false
        EqService.stop(context, silentToast = true)
        return true
    }

    /** Mirrors MainActivity's app-open fallback: restart the EQ if it was on when the app was last used. */
    fun startIfPersistedOn(context: Context) {
        if (!EqService.isDpRunning && EqPreferencesManager(context).getPowerState()) resume(context)
    }

    fun resume(context: Context) {
        val i = Intent(context, EqService::class.java).setAction(EqService.ACTION_AUTO_START)
            .putExtra(EqService.EXTRA_SILENT_TOAST, true) // AutoVybz shows its own confirmation
        runCatching {
            context.startForegroundService(i)
        }.onFailure { Log.w("AutoVybz", "EQ resume failed", it) }
    }
}
