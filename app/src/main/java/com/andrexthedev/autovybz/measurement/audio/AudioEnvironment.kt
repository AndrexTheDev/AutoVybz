package com.andrexthedev.autovybz.measurement.audio

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.andrexthedev.autovybz.measurement.mic.InputSource
import com.bearinmind.equalizer314.audio.EqService

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

    private fun AudioDeviceInfo.toRoute() = RouteClassifier.Route(type, productName?.toString() ?: "")
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
    fun findUsbMic(context: Context): AudioDeviceInfo? {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
    }
}

/**
 * Sets STREAM_MUSIC to the measurement level (~70 %) and restores the user's
 * volume afterwards. Fixed-volume devices (e.g. some TV boxes) are left alone.
 */
class VolumeGuard(context: Context) {
    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var saved: Int? = null

    val min: Int get() = if (Build.VERSION.SDK_INT >= 28) am.getStreamMinVolume(AudioManager.STREAM_MUSIC) else 0
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

    fun resume(context: Context) {
        val i = Intent(context, EqService::class.java).setAction(EqService.ACTION_AUTO_START)
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }.onFailure { Log.w("AutoVybz", "EQ resume failed", it) }
    }
}
