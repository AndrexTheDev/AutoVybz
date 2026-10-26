package com.andrexthedev.autovybz.measurement

import com.andrexthedev.autovybz.measurement.audio.VolumeMath
import com.andrexthedev.autovybz.measurement.dsp.SmoothingProfile
import com.andrexthedev.autovybz.measurement.eq.AutoEqSettings
import com.andrexthedev.autovybz.measurement.eq.HIGH_BOOST_CAP_START_HZ
import com.andrexthedev.autovybz.measurement.eq.PreampPlanner
import com.andrexthedev.autovybz.measurement.eq.TargetType
import kotlin.math.max
import kotlin.math.min

/**
 * What kind of loudspeaker is being calibrated. The third tuning axis next to the target curve
 * (taste) and the [ListeningEnvironment] (room physics): the optimal correction *strategy*
 * depends on the speaker itself. See docs/SPEAKER_MODES.md for the full reasoning.
 */
enum class SpeakerClass {
    /** Normal external speaker (Bluetooth, USB, HDMI, …) - the classic AutoVybz behaviour. */
    STANDARD,

    /**
     * Weak / cheap speaker ("rescue"): don't chase perfection - remove what makes it sound cheap
     * (boom, honk, harshness, rattling sub-bass) and accept its physical limits gracefully.
     */
    RESCUE,

    /**
     * This phone's / tablet's own speakers, measured with its own microphone. The mic sits
     * centimetres from the driver, so correction is restricted to the trustworthy band and
     * the protective high pass does most of the quality work.
     */
    DEVICE,
}

/**
 * Strategy overrides of a [SpeakerClass]; `null` / `false` always means "inherit the standard
 * behaviour", so [SpeakerClass.STANDARD] is guaranteed to change nothing. Merged with the
 * [EnvironmentTuning] by [CorrectionPlanner] (pure Kotlin, unit-tested).
 */
data class SpeakerClassTuning(
    /** Analysis smoothing (only replaces the automatic default; an explicit Pro choice wins). */
    val smoothing: SmoothingProfile? = null,
    /** Additional cap on the allowed total boost. */
    val maxBoostCapDb: Double? = null,
    val maxCutDb: Double? = null,
    val highLimitHz: Double? = null,
    /** "Cut narrow, boost broad" width (see [AutoEqSettings.broadBoostOctaves]). */
    val broadBoostOctaves: Double? = null,
    /** Excursion-guard ramp width; the wider (safer) of class and environment wins. */
    val excursionGuardOctaves: Double? = null,
    val lowLimitScanHz: Double? = null,
    val lowLimitMaxHz: Double? = null,
    /** Cut sub-bass the driver cannot reproduce instead of letting it rattle (see AutoEqRenderer). */
    val protectiveHighPass: Boolean = false,
    /**
     * Fixed corner of the protective high pass in Hz; `null` derives it from the measured bass
     * roll-off (the classic behaviour). A tiny driver's -6 dB point sits in the *midrange*
     * (400-900 Hz) - cutting there would eat musical bass, so a device self-measurement protects
     * the sub-bass only.
     */
    val protectiveHighPassHz: Double? = null,
    /** Absolute boost cap (dB) above [highBoostCapStartHz]; `null` = [AutoEqSettings.maxBoostDb]. */
    val highBoostCapDb: Double? = null,
    /** Start of the [highBoostCapDb] region (Hz). */
    val highBoostCapStartHz: Double? = null,
    /**
     * Fraction of a measured excess that is corrected (0.1..1.0); see [AutoEqSettings.cutTrust].
     * `null` = full correction. Every dB cut in the ear's most sensitive band costs perceived loudness.
     */
    val cutTrust: Double? = null,
    /** Arm the limiter whenever such a calibration is activated. */
    val forceLimiter: Boolean = false,
    /** Target the wizard pre-selects for this class (the user can still change it). */
    val suggestedTarget: TargetType? = null,
    /** Media volume the wizard sets before measuring (device speakers measure next to the mic). */
    val measurementVolumeFraction: Float? = null,
    /** Fixed geometry - extra positions would measure the same thing again. */
    val singlePosition: Boolean = false,
    /** Always record with the phone's own microphone (its compensation is part of the concept). */
    val forcePhoneMic: Boolean = false,
) {
    companion object {
        private val STANDARD = SpeakerClassTuning()

        private val RESCUE = SpeakerClassTuning(
            // Cheap drivers measure ragged; correct the tonal shape, not every wiggle.
            smoothing = SmoothingProfile.STRONG,
            // Boom / honk / enclosure resonances of cheap boxes are 6-12 dB tall - allow deep cuts.
            // Boosts keep the user's limit: they cost distortion and loudness on a weak amp.
            maxCutDb = 16.0,
            broadBoostOctaves = 1.0,
            excursionGuardOctaves = 1.5,
            // Budget speakers often roll off at 150-300 Hz; the standard scan/clamp would miss it.
            lowLimitScanHz = 400.0,
            lowLimitMaxHz = 350.0,
            protectiveHighPass = true,
            // Weak speakers get played loud - keep the safety net armed.
            forceLimiter = true,
            // Bass shelf + downward tilt forgives the typical midrange shout / harsh treble.
            suggestedTarget = TargetType.HARMAN,
        )

        private val DEVICE = SpeakerClassTuning(
            // Near field: bass smoothed hard (pressure zone + chassis vibration), midrange/treble fine
            // (no room, no reflections) - see SmoothingProfile.DEVICE.
            smoothing = SmoothingProfile.DEVICE,
            // Tiny drivers already run at their limit (OEM DSP); boosts mostly buy distortion.
            maxBoostCapDb = 4.0,
            // Above ~12 kHz the self-measurement says more about chassis diffraction than sound.
            highLimitHz = 12000.0,
            broadBoostOctaves = 1.0,
            // Two-octave boost ramp above the roll-off: bass a phone speaker cannot play
            // must not be "EQ'd in" - it only takes excursion from the audible band.
            excursionGuardOctaves = 2.0,
            // Phones roll off around 400-900 Hz, tablets around 150-350 Hz: scan from 1.2 kHz.
            lowLimitScanHz = 1200.0,
            lowLimitMaxHz = 900.0,
            protectiveHighPass = true,
            // The -6 dB point of a tiny driver is midrange (400-900 Hz): a protective pass there would
            // eat the bass the driver *does* play. Protect the sub-bass only - the driver cannot
            // reproduce anything below ~60 Hz cleanly anyway (rattle, excursion).
            protectiveHighPassHz = 60.0,
            // Above ~6 kHz the self-measurement is dominated by the (estimated) mic profile and the
            // driver's own hash: a +4 dB "correction" there is mostly phantom boost. Cap it.
            highBoostCapDb = 2.0,
            highBoostCapStartHz = 6000.0,
            forceLimiter = true,
            // A self-measurement at 1-15 cm overstates the presence region, and every dB cut around
            // 1-4 kHz costs several dB of perceived loudness: correct 75 % of a measured excess. The
            // remaining 25 % is what the manufacturer's own tuning usually leaves in, too.
            cutTrust = 0.75,
            suggestedTarget = TargetType.HARMAN,
            // The microphone sits centimetres from the speaker: 50 % is plenty of level
            // (70 % would risk clipping the mic and exciting chassis rattles).
            measurementVolumeFraction = VolumeMath.DEVICE_MEASUREMENT_FRACTION,
            singlePosition = true,
            forcePhoneMic = true,
        )

        fun of(speakerClass: SpeakerClass): SpeakerClassTuning = when (speakerClass) {
            SpeakerClass.STANDARD -> STANDARD
            SpeakerClass.RESCUE -> RESCUE
            SpeakerClass.DEVICE -> DEVICE
        }
    }
}

/** Merges environment + speaker-class tuning into the concrete analysis / render parameters. */
object CorrectionPlanner {

    /** An explicit Pro smoothing choice always wins; classes only replace the automatic default. */
    fun smoothing(
        userSmoothing: SmoothingProfile,
        environment: EnvironmentTuning,
        speakerClass: SpeakerClassTuning,
    ): SmoothingProfile =
        if (userSmoothing == SmoothingProfile.DYNAMIC) speakerClass.smoothing ?: environment.smoothing
        else environment.smoothing

    fun forceLimiter(environment: EnvironmentTuning, speakerClass: SpeakerClassTuning): Boolean =
        environment.forceLimiter || speakerClass.forceLimiter

    fun autoEqSettings(
        bandCount: Int,
        environment: EnvironmentTuning,
        speakerClass: SpeakerClassTuning,
    ): AutoEqSettings = AutoEqSettings(
        bandCount = bandCount,
        maxBoostDb = speakerClass.maxBoostCapDb?.let { min(environment.maxBoostDb, it) } ?: environment.maxBoostDb,
        maxCutDb = speakerClass.maxCutDb ?: 12.0, // = AutoEqSettings default
        highLimitHz = speakerClass.highLimitHz ?: 16000.0, // = AutoEqSettings default
        broadBoostOctaves = speakerClass.broadBoostOctaves ?: environment.broadBoostOctaves,
        excursionGuardOctaves = speakerClass.excursionGuardOctaves
            ?.let { max(environment.excursionGuardOctaves, it) } ?: environment.excursionGuardOctaves,
        lowLimitScanHz = speakerClass.lowLimitScanHz ?: 200.0, // = AutoEqSettings default
        lowLimitMaxHz = speakerClass.lowLimitMaxHz ?: 160.0, // = AutoEqSettings default
        protectiveHighPass = speakerClass.protectiveHighPass,
        protectiveHighPassHz = speakerClass.protectiveHighPassHz,
        highBoostCapDb = speakerClass.highBoostCapDb,
        highBoostCapStartHz = speakerClass.highBoostCapStartHz ?: HIGH_BOOST_CAP_START_HZ,
        cutTrust = (speakerClass.cutTrust ?: 1.0).coerceIn(0.1, 1.0), // = AutoEqSettings default
        // Gain staging (PreampPlanner): a calibration must make the speaker better, not quieter. Where
        // the preset arms the limiter anyway, the pre-gain is loudness-neutral (bounded by the limiter's
        // headroom); everywhere else the legacy clipping-safe value is kept.
        loudnessMakeUp = true,
        maxMakeUpDb = PreampPlanner.MAX_MAKE_UP_DB,
        preampHeadroomAllowanceDb = if (forceLimiter(environment, speakerClass)) {
            PreampPlanner.LIMITER_HEADROOM_ALLOWANCE_DB
        } else {
            0.0 // no limiter armed -> the clipping-safe pre-gain is the last line of defence
        },
    )
}
