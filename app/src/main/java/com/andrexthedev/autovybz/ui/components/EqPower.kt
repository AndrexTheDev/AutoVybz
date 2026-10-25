package com.andrexthedev.autovybz.ui.components

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.andrexthedev.autovybz.measurement.audio.EqBypass
import com.andrexthedev.autovybz.sound.VybzSound
import com.bearinmind.equalizer314.audio.EqService

/** Live on/off state of the system-wide sound engine plus the calibration that is audible. */
@Stable
private const val MIN_TOGGLE_INTERVAL_MS = 600L

class EqPowerState internal constructor(private val context: Context) {
    var on by mutableStateOf(EqService.isDpRunning)
        internal set
    var calibration by mutableStateOf<String?>(null)
        internal set
    internal var requests by mutableIntStateOf(0)

    private var lastToggleAt = 0L

    fun set(want: Boolean) {
        // Every toggle starts or stops the sound service (DSP rebuild on the main thread): ignore rapid-fire taps.
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastToggleAt < MIN_TOGGLE_INTERVAL_MS) return
        lastToggleAt = now
        on = want // optimistic; the engine's broadcast (or the re-check) confirms it
        requests++
        if (want) EqBypass.resume(context)
        else if (EqService.isDpRunning) EqService.stop(context, silentToast = true)
    }
}

/**
 * Follows the engine's own broadcasts, so every switch stays in sync with the quick-settings tile, the notification
 * and the engine screens (no polling - that would also keep Compose tests busy).
 */
@Composable
fun rememberEqPower(): EqPowerState {
    val context = LocalContext.current
    val state = remember { EqPowerState(context.applicationContext) }
    LaunchedEffect(Unit) {
        state.calibration = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { VybzSound.activeCalibrationName(context) }
    }
    // If the engine could not start (or stop), fall back to its real state instead of showing a lie.
    LaunchedEffect(state.requests) {
        if (state.requests > 0) {
            kotlinx.coroutines.delay(4_000)
            state.on = EqService.isDpRunning
        }
    }
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                state.on = intent.action == EqService.ACTION_EQ_STARTED
                state.calibration = VybzSound.activeCalibrationName(context)
            }
        }
        val filter = IntentFilter().apply {
            addAction(EqService.ACTION_EQ_STARTED)
            addAction(EqService.ACTION_EQ_STOPPED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return state
}
