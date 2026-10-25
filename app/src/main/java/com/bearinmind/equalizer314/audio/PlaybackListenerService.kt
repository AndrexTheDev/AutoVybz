package com.bearinmind.equalizer314.audio

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * "Now playing" detection path. Never reads notifications — declaring this service is only
 * to gain notification-listener access, which Android requires for [MediaSessionManager.getActiveSessions].
 * On listener grant, registers [AudioManager.AudioPlaybackCallback] + [MediaSessionManager.OnActiveSessionsChangedListener];
 * either fires on any app audio start/stop. Debounces 100 ms then runs [AudioPolicyDumpParser.dump]
 * on a worker thread to recover session IDs public APIs hide; ships `Map<packageName, Set<sessionId>>`
 * to [EqService] via `startService`. All callbacks and dump-parse run on a dedicated [HandlerThread]
 * (the binder thread delivering `onListenerConnected` hops there immediately).
 */
class PlaybackListenerService : NotificationListenerService() {

    private var detectorThread: HandlerThread? = null
    private var detectorHandler: Handler? = null

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            scheduleSnapshot("playbackConfigChanged")
        }
    }

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { _: List<MediaController>? ->
            scheduleSnapshot("activeSessionsChanged")
        }

    private val snapshotRunnable = Runnable { runSnapshot() }

    /** Periodic re-snapshot for cold app exits (force-stop, swipe-from-recents) where neither
     *  callback fires — otherwise stale `detectedKeys` rows linger in "Now playing" until another
     *  media app pokes a callback. 3 s: short enough that stale rows clear fast, negligible battery. */
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            scheduleSnapshot("heartbeat")
            detectorHandler?.postDelayed(this, HEARTBEAT_MS)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "onListenerConnected — starting playback detection")
        val thread = HandlerThread("PlaybackDetector").also { it.start() }
        detectorThread = thread
        val handler = Handler(thread.looper)
        detectorHandler = handler

        // Hop off the binder thread immediately. Registration touches
        // system services that prefer to be called from a Looper thread.
        handler.post {
            // The first snapshot after (re)connect always runs in full.
            lastSnapshotEmpty = false
            idleSkipCount = 0
            registerCallbacks(handler)
            // Initial scan once the listener is alive so the UI sees
            // whatever was already playing at the moment of bind.
            scheduleSnapshot("listenerConnected")
            // Heartbeat to clean up entries left behind when an app
            // dies without firing a teardown callback.
            handler.postDelayed(heartbeatRunnable, HEARTBEAT_MS)
        }
    }

    override fun onListenerDisconnected() {
        Log.d(TAG, "onListenerDisconnected — stopping playback detection")
        // Tell EqService to drop every per-session effect attached via the DETECTED path.
        // Broadcast effects survive — they have their own CLOSE lifecycle. Fired BEFORE quitting
        // the detector thread so the dispatch rides the still-live handler.
        dispatchReleaseDetected()
        val handler = detectorHandler
        val thread = detectorThread
        detectorHandler = null
        detectorThread = null
        if (handler != null) {
            handler.removeCallbacks(snapshotRunnable)
            handler.removeCallbacks(heartbeatRunnable)
            handler.post {
                unregisterCallbacks()
                thread?.quitSafely()
            }
        } else {
            unregisterCallbacks()
            thread?.quitSafely()
        }
        super.onListenerDisconnected()
    }

    /** Active media controllers, fetched once per snapshot (was: one binder round-trip
     *  per collector). Empty on any failure — callers degrade to the dump/fallback paths. */
    private fun activeControllers(): List<MediaController> {
        return try {
            val msm = getSystemService(MediaSessionManager::class.java) ?: return emptyList()
            val component = ComponentName(this, PlaybackListenerService::class.java)
            msm.getActiveSessions(component) ?: emptyList()
        } catch (t: Throwable) {
            Log.w(TAG, "getActiveSessions failed", t)
            emptyList()
        }
    }

    /** Packages whose active MediaController reports [PlaybackState.STATE_PLAYING] now. Drives the
     *  per-row speaker-pulse animation. Apps without a MediaSession (some games, custom players) are
     *  absent even if outputting — acceptable since nearly every EQ-relevant app uses MediaSession. */
    private fun collectActivelyPlayingPackages(controllers: List<MediaController>): Set<String> {
        return try {
            val own = packageName
            controllers.asSequence()
                .filter { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                .mapNotNull { it.packageName }
                .filter { it != own && it.isNotBlank() }
                .toSet()
        } catch (t: Throwable) {
            Log.w(TAG, "playbackState lookup failed", t)
            emptySet()
        }
    }

    /** Public-API fallback when audioserver's `dumpAsync` is denied. Returns packages owning an
     *  active media session — no session IDs (`AudioSessionId` is gated by `@SystemApi`). User sees
     *  "YouTube is playing" but can't per-session EQ it on this device. */
    private fun collectActiveSessionPackages(controllers: List<MediaController>): Set<String> {
        return try {
            val own = packageName
            controllers.asSequence()
                .mapNotNull { it.packageName }
                .filter { it != own && it.isNotBlank() }
                .toSet()
        } catch (t: Throwable) {
            Log.w(TAG, "getActiveSessions fallback failed", t)
            emptySet()
        }
    }

    // Idle-skip state (detector thread only): after an all-empty snapshot, heartbeats
    // re-verify with two cheap public-API probes before paying for a full audioserver
    // dump + EqService dispatch. Every IDLE_BACKSTOP-th idle tick still runs the full
    // snapshot, bounding worst-case detection lag if a public signal ever misses a player.
    private var lastSnapshotEmpty = false
    private var idleSkipCount = 0

    private fun hasActivePlayback(): Boolean = try {
        // On any doubt (null service, exception) answer "yes" so the full snapshot runs.
        getSystemService(AudioManager::class.java)?.activePlaybackConfigurations?.isNotEmpty() ?: true
    } catch (t: Throwable) {
        true
    }

    /** Stable negative session id per package, marking "detected but no recoverable session id".
     *  Negative so it can't collide with real audioserver ids (always positive); stable so the
     *  observe-diff doesn't churn between snapshots. */
    private fun syntheticSessionId(pkg: String): Int {
        val h = pkg.hashCode()
        return if (h == Int.MIN_VALUE) -1 else -kotlin.math.abs(h)
    }

    private fun dispatchReleaseDetected() {
        val intent = Intent(this, EqService::class.java)
            .setAction(EqService.ACTION_RELEASE_DETECTED)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "could not dispatch ACTION_RELEASE_DETECTED", t)
        }
    }

    private fun registerCallbacks(handler: Handler) {
        val audio = getSystemService(AudioManager::class.java)
        audio?.registerAudioPlaybackCallback(playbackCallback, handler)
        val msm = getSystemService(MediaSessionManager::class.java)
        if (msm != null) {
            val component = ComponentName(this, PlaybackListenerService::class.java)
            // addOnActiveSessionsChangedListener throws SecurityException if the listener isn't
            // bound yet. Guaranteed bound here (onListenerConnected), but a slow/buggy OEM might
            // race — catch and degrade.
            try {
                msm.addOnActiveSessionsChangedListener(sessionsListener, component, handler)
            } catch (t: Throwable) {
                Log.w(TAG, "addOnActiveSessionsChangedListener failed", t)
            }
        }
    }

    private fun unregisterCallbacks() {
        val audio = getSystemService(AudioManager::class.java)
        try { audio?.unregisterAudioPlaybackCallback(playbackCallback) } catch (_: Throwable) {}
        val msm = getSystemService(MediaSessionManager::class.java)
        try { msm?.removeOnActiveSessionsChangedListener(sessionsListener) } catch (_: Throwable) {}
    }

    private fun scheduleSnapshot(reason: String) {
        val handler = detectorHandler ?: return
        // Coalesce bursts of callbacks (BT route flip, codec change,
        // ExoPlayer rebuild) into one snapshot.
        handler.removeCallbacks(snapshotRunnable)
        handler.postDelayed(snapshotRunnable, DEBOUNCE_MS)
    }

    private fun runSnapshot() {
        // Runs on the detector HandlerThread — safe to block on the
        // dumpAsync pipe read.
        val controllers = activeControllers()

        // Idle skip: the previous snapshot found nothing at all and both cheap public
        // signals still say "nothing" (no media sessions, no active playback configs),
        // so a full dump could only produce another empty snapshot — EqService's
        // observe-diff and playing-set handling are no-ops for repeated empty input.
        // A playback START can't be missed for long: it fires onPlaybackConfigChanged
        // (a non-heartbeat snapshot) and makes the probes non-empty; the 20-tick
        // backstop (~60 s) covers even a hypothetical probe blind spot.
        if (lastSnapshotEmpty && controllers.isEmpty() && !hasActivePlayback()) {
            if (++idleSkipCount < IDLE_BACKSTOP_TICKS) return
            idleSkipCount = 0
        } else {
            idleSkipCount = 0
        }

        val dumpResult = AudioPolicyDumpParser.dump(applicationContext)

        // Stock Android grants audioserver's dumpAsync to any binder caller; Samsung One UI + several
        // OEM ROMs deny it, so dump returns empty and we fall back to public MediaSessionManager.
        // That gives no session IDs (@SystemApi from API 29+) but surfaces playing packages
        // ("Detected (no session)" badge). Fallback assigns a stable synthetic negative session id
        // per package so observe-diff still computes correctly.
        val merged = mutableMapOf<String, MutableSet<Int>>()
        for ((pkg, sids) in dumpResult) {
            merged.getOrPut(pkg) { mutableSetOf() }.addAll(sids)
        }
        if (dumpResult.isEmpty()) {
            val fallback = collectActiveSessionPackages(controllers)
            for (pkg in fallback) {
                if (pkg in merged) continue
                merged.getOrPut(pkg) { mutableSetOf() }.add(syntheticSessionId(pkg))
            }
            if (fallback.isNotEmpty()) {
                Log.d(TAG, "dumpsys denied — fallback surfaced ${fallback.size} package(s) via MediaSessionManager")
            }
        }

        // Packages whose MediaController reports STATE_PLAYING now. Surfaces on Samsung even when
        // dumpsys is denied. Receiver uses it to decide per-row speaker pulse — paused rows go static.
        val playingNow = collectActivelyPlayingPackages(controllers)

        lastSnapshotEmpty = merged.isEmpty() && playingNow.isEmpty()

        Log.d(TAG, "snapshot detected=${merged.size} packages, playing=${playingNow.size}")

        // Pack into a Bundle of int[]s (Map<String, Set<Int>> isn't parcelable); receiver iterates keySet().
        val bundle = Bundle()
        for ((pkg, sids) in merged) {
            bundle.putIntArray(pkg, sids.toIntArray())
        }
        if (playingNow.isNotEmpty()) {
            bundle.putStringArray(
                EqService.EXTRA_PLAYING_PACKAGES_KEY,
                playingNow.toTypedArray(),
            )
        }
        val intent = Intent(this, EqService::class.java)
            .setAction(EqService.ACTION_PLAYBACK_DETECTED)
            .putExtra(EqService.EXTRA_DETECTED_BUNDLE, bundle)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (t: Throwable) {
            // Service may be shutting down — next snapshot will retry.
            Log.w(TAG, "could not dispatch ACTION_PLAYBACK_DETECTED", t)
        }
    }

    // ----- notification surface (intentionally empty) ------------------

    // Manifest declares android.service.notification.disabled_filter_types for every category, so
    // the system delivers no notifications here. These empty overrides make that contract explicit.

    @SuppressLint("MissingPermission")
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // Intentionally empty.
    }

    @SuppressLint("MissingPermission")
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Intentionally empty.
    }

    companion object {
        private const val TAG = "PlaybackListenerSvc"
        private const val DEBOUNCE_MS = 100L
        private const val HEARTBEAT_MS = 3000L
        // Full snapshot at least every ~60 s even while idle-skipping (20 × 3 s).
        private const val IDLE_BACKSTOP_TICKS = 20
    }
}
