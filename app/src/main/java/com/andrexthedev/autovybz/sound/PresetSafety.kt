package com.andrexthedev.autovybz.sound

import com.andrexthedev.autovybz.measurement.ListeningEnvironment
import com.andrexthedev.autovybz.measurement.SpeakerClass
import com.andrexthedev.autovybz.measurement.SpeakerClassTuning
import com.andrexthedev.autovybz.preset.model.MeasurementSnapshot

/**
 * Which calibrations must always run with the Equalizer314 limiter armed.
 *
 * The wizard already applies this rule when the calibration is saved (`MeasurementRequest.forceLimiter` =
 * environment *or* speaker class), but re-applying the same calibration later from the Sound or Pro screen
 * (e.g. after switching a sound style) used to check the environment only. A party, weak-speaker or
 * device-speaker calibration therefore lost its limiter the first time the user touched a style - exactly
 * the calibrations whose whole concept assumes a safety net at high level.
 *
 * Pure Kotlin so the rule is unit-tested once and used everywhere the engine is written.
 */
object PresetSafety {

    /** Should the limiter be armed for [snapshot] when re-applying a stored calibration? */
    fun requiresLimiter(userEnabledInSettings: Boolean, snapshot: MeasurementSnapshot?): Boolean =
        userEnabledInSettings || isAlwaysProtected(snapshot)

    /**
     * True for calibrations that are dangerous without a limiter regardless of the user's preference:
     * outdoors / party (the speaker runs at party level), weak-speaker rescue and device speakers (the two
     * classes that deliberately boost or cut hard). Older presets without a speaker class default to
     * STANDARD in the model, which keeps them on the plain rule.
     */
    fun isAlwaysProtected(snapshot: MeasurementSnapshot?): Boolean {
        if (snapshot == null) return false
        if (snapshot.environment == ListeningEnvironment.OUTDOOR.name) return true
        val speakerClass = runCatching { SpeakerClass.valueOf(snapshot.speakerClass) }.getOrNull() ?: return false
        return SpeakerClassTuning.of(speakerClass).forceLimiter
    }
}
