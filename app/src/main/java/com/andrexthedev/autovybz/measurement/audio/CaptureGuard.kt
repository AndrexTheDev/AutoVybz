package com.andrexthedev.autovybz.measurement.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Protects one 10 s capture against outside interference:
 *
 *  - **Audio focus** (`AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE`): music / video apps pause during the
 *    measurement instead of playing into the microphone, and are told to resume afterwards.
 *    Losing the focus (incoming call, alarm, another app) interrupts the measurement.
 *  - **Route watcher**: if an output device appears or disappears (Bluetooth speaker disconnects,
 *    headphones are plugged in) the stimulus no longer reaches the speaker that is being measured.
 *
 * The first problem wins and is reported via [interruption]; the controller aborts the capture.
 */
class CaptureGuard(context: Context) {
    private val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())

    @Volatile var interruption: CaptureInterruptedException.Reason? = null
        private set

    private var baselineOutputs: Set<Int> = emptySet()
    private var focusRequest: AudioFocusRequest? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> interrupt(CaptureInterruptedException.Reason.FOCUS_LOST)
            // A short notification ducking us is tolerated; the analysis flags disturbed results anyway.
            else -> Unit
        }
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = checkOutputs()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = checkOutputs()
    }

    /** Requests the focus and starts watching the routes. Returns false if the focus was denied (e.g. during a call). */
    fun start(): Boolean {
        interruption = null
        baselineOutputs = currentOutputs()
        // Registering immediately reports all existing devices; checkOutputs() compares against the baseline, so that is a no-op.
        runCatching { am.registerAudioDeviceCallback(deviceCallback, handler) }
            .onFailure { Log.w(TAG, "route watcher unavailable", it) }
        val granted = run {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
                )
                .setOnAudioFocusChangeListener(focusListener, handler)
                .setWillPauseWhenDucked(false)
                .build()
            focusRequest = req
            am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        if (!granted) interrupt(CaptureInterruptedException.Reason.FOCUS_LOST)
        return granted
    }

    /** Gives the focus back (paused apps may resume) and stops watching. Safe to call more than once. */
    fun stop() {
        runCatching { am.unregisterAudioDeviceCallback(deviceCallback) }
        focusRequest?.let { am.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    private fun checkOutputs() {
        val now = currentOutputs()
        if (now != baselineOutputs) interrupt(CaptureInterruptedException.Reason.ROUTE_CHANGED)
    }

    private fun interrupt(reason: CaptureInterruptedException.Reason) {
        if (interruption == null) {
            interruption = reason
            Log.w(TAG, "capture interrupted: $reason")
        }
    }

    /** Output devices that can carry media (ids are stable while a device stays connected). */
    private fun currentOutputs(): Set<Int> = runCatching {
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type !in IGNORED_OUTPUT_TYPES }
            .map { it.id }
            .toSet()
    }.getOrDefault(emptySet())

    companion object {
        private const val TAG = "AutoVybz"

        /** Telephony / system outputs that come and go without affecting media playback. */
        private val IGNORED_OUTPUT_TYPES = setOf(
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
            AudioDeviceInfo.TYPE_TELEPHONY,
            AudioDeviceInfo.TYPE_FM,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_IP,
            AudioDeviceInfo.TYPE_BUS,
        )
    }
}
