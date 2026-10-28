package com.andrexthedev.autovybz.measurement

import com.andrexthedev.autovybz.measurement.dsp.SmoothingProfile
import kotlin.math.min

/** Where the speaker is played. Changes how the measurement is analysed and how the EQ is fitted. */
enum class ListeningEnvironment {
    /** Living room, office, … - room modes and a diffuse reverberant field. */
    INDOOR,

    /** Garden, terrace, beach, park - (almost) free field, ground reflection, wind and ambient noise. */
    OUTDOOR,
}

/**
 * All environment-dependent tuning in one place (pure Kotlin, unit-tested). See docs/OUTDOOR.md for
 * the full failure-mode analysis behind the outdoor values.
 *
 * Outdoor physics → decision:
 * * **No room gain.** Indoors the walls add roughly +4…+8 dB of bass; outdoors that is missing, so the
 *   measurement shows a big bass deficit. Filling it with EQ would cost the same amount of headroom
 *   (pre-gain) - i.e. *loudness*, the one thing a party needs - and drives the woofer into excursion at
 *   party level. → boost capped at [OUTDOOR_MAX_BOOST_DB], wider excursion ramp, limiter forced on.
 *   Free bass comes from placement instead (speaker on the ground / against a wall, see Help).
 * * **Ground reflection.** The floor reflection creates deep, position-dependent comb notches that no EQ
 *   can fill. → "cut narrow, boost broad" (boosts follow a 1-octave smoothed curve), stronger smoothing,
 *   3 measurement positions spread over the audience area by default.
 * * **Noise & distant echoes.** Wind, traffic, voices, birds; echoes from buildings arrive late. The
 *   windowed sweep rejects both, the steady-state pink noise does not. → sweep weighted 70 %.
 */
data class EnvironmentTuning(
    val smoothing: SmoothingProfile,
    /** Weight of the sweep vs. the pink-noise result (0..1). */
    val sweepWeight: Double,
    val maxBoostDb: Double,
    /** See [com.andrexthedev.autovybz.measurement.eq.AutoEqSettings.broadBoostOctaves]. */
    val broadBoostOctaves: Double?,
    val excursionGuardOctaves: Double,
    /** Arm the limiter whenever such a calibration is activated (party volume). */
    val forceLimiter: Boolean,
    /** Default number of measurement positions suggested for this environment. */
    val recommendedPositions: Int,
) {
    companion object {
        const val OUTDOOR_MAX_BOOST_DB = 3.0

        /**
         * How far above the detected bass limit a boost must be before it is fully allowed (octaves).
         *
         * The limit is already conservative (the -6 dB crossing x 1.25), so a full octave meant "no boost
         * below 2.5x the driver's own roll-off". Measured on the mini box: at 104-122 Hz - well above its
         * 58 Hz -6 dB point - the correction was held back by ~6 dB in a band that carries 3 % of the
         * programme energy, and the residual error there alone kept two probe cases from passing. Half an
         * octave still protects the roll-off itself and aligns with the 0.5-octave fade of
         * `AutoEqRenderer.rangeWeight`, so both mechanisms end at the same frequency.
         */
        const val INDOOR_EXCURSION_GUARD_OCTAVES = 0.5

        fun of(
            environment: ListeningEnvironment,
            userSmoothing: SmoothingProfile = SmoothingProfile.DYNAMIC,
            userMaxBoostDb: Double = 6.0,
        ): EnvironmentTuning = when (environment) {
            ListeningEnvironment.INDOOR -> EnvironmentTuning(
                smoothing = userSmoothing,
                sweepWeight = 0.5,
                maxBoostDb = userMaxBoostDb,
                broadBoostOctaves = null,
                excursionGuardOctaves = INDOOR_EXCURSION_GUARD_OCTAVES,
                forceLimiter = false,
                recommendedPositions = 1,
            )
            ListeningEnvironment.OUTDOOR -> EnvironmentTuning(
                // No room modes to resolve; 1/2-octave smoothing in the mids ignores ground-reflection ripple.
                // An explicit Pro choice (fine / strong) is respected.
                smoothing = if (userSmoothing == SmoothingProfile.DYNAMIC) SmoothingProfile.STRONG else userSmoothing,
                sweepWeight = 0.7,
                maxBoostDb = min(userMaxBoostDb, OUTDOOR_MAX_BOOST_DB),
                broadBoostOctaves = 1.0,
                excursionGuardOctaves = 1.5,
                forceLimiter = true,
                recommendedPositions = 3,
            )
        }
    }
}
