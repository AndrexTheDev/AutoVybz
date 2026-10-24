package com.andrexthedev.autovybz

import android.content.Context
import com.andrexthedev.autovybz.measurement.audio.FallbackMeasurementEngine
import com.andrexthedev.autovybz.measurement.audio.MeasurementAudioEngine
import com.andrexthedev.autovybz.measurement.audio.RouteClassifier
import com.andrexthedev.autovybz.measurement.audio.RouteInspector

/**
 * Tiny service locator for the parts that touch real hardware. Production code always uses the
 * defaults; the instrumented end-to-end tests swap in a simulated speaker/room and a fixed route so
 * the complete wizard can be driven (and screenshotted) on an emulator without a real speaker.
 */
object AutoVybzServices {

    /** Creates the audio engine used for one calibration (Oboe with Java fallback). */
    @Volatile var engineFactory: () -> MeasurementAudioEngine = DEFAULT_ENGINE

    /** Decides whether the current output route may be measured. */
    @Volatile var routeInspector: (Context) -> RouteClassifier.Decision = DEFAULT_ROUTE

    /** "Please be quiet" countdown before each position, so the user can step back from the phone. */
    @Volatile var countdownSeconds: Int = DEFAULT_COUNTDOWN_SECONDS

    const val DEFAULT_COUNTDOWN_SECONDS = 3

    fun reset() {
        engineFactory = DEFAULT_ENGINE
        routeInspector = DEFAULT_ROUTE
        countdownSeconds = DEFAULT_COUNTDOWN_SECONDS
    }
}

private val DEFAULT_ENGINE: () -> MeasurementAudioEngine = { FallbackMeasurementEngine() }
private val DEFAULT_ROUTE: (Context) -> RouteClassifier.Decision = { RouteInspector.inspect(it) }
