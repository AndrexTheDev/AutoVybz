package com.andrexthedev.autovybz.sound

import com.andrexthedev.autovybz.measurement.eq.PeqBand
import com.andrexthedev.autovybz.measurement.eq.PeqType

/** Groups for the style picker - systematic instead of one long list. */
enum class StyleGroup { BASE, BEATS, STAGE, ACOUSTIC, SITUATION }

/**
 * Ready-made sound styles ("Musikrichtungen"), layered *on top of* a speaker calibration (or used alone).
 *
 * Design rules (enforced by SoundStyleTest):
 *  - at most [MAX_BANDS] filters per style, every filter within ±[MAX_GAIN_DB] dB, curve within ±[MAX_GAIN_DB] dB;
 *  - broad strokes only (shelves and Q ≤ 1.4): a style flavours the calibrated sound, it never re-tunes a speaker;
 *  - [NEUTRAL] is the identity: calibration only.
 *
 * Curves are tuned from common mastering practice for each genre: where the energy of the music sits (kick/sub,
 * snare, vocals, air), and what a small or outdoor speaker loses (bass and presence at distance, low-volume loudness).
 * The enum name is the persistence key - never rename an entry.
 */
enum class SoundStyle(val group: StyleGroup, val bands: List<PeqBand>) {
    NEUTRAL(StyleGroup.BASE, emptyList()),

    /** Deep 808 sub, clean low mids, forward vocals, warm top. */
    HIPHOP_RNB(
        StyleGroup.BEATS,
        listOf(ls(60.0, 4.5), pk(300.0, -2.0, 1.0), pk(3_000.0, 1.5, 1.0), hs(10_000.0, 0.5)),
    ),

    /** Sound-system bass weight, relaxed mids (warm, not boxy), crisp but soft top. */
    REGGAE_DANCEHALL(
        StyleGroup.BEATS,
        listOf(ls(60.0, 5.0), pk(160.0, 1.0, 1.0), pk(800.0, -2.0, 0.8), hs(8_000.0, 1.0)),
    ),

    /** Punchy kick, cleared low mids, percussion and guitar sparkle. */
    AFROBEATS(
        StyleGroup.BEATS,
        listOf(ls(90.0, 3.0), pk(400.0, -1.0, 1.0), pk(2_500.0, 2.0, 1.2), hs(9_000.0, 1.5)),
    ),

    /** Reggaeton / salsa / cumbia: kick drive, brass and guitars, shining percussion. */
    LATIN(
        StyleGroup.BEATS,
        listOf(ls(80.0, 3.0), pk(1_500.0, 1.5, 1.0), pk(5_000.0, 2.5, 1.2), hs(12_000.0, 1.0)),
    ),

    /** Deep sub, tight low mids, synth bite and air. */
    ELECTRONIC(
        StyleGroup.BEATS,
        listOf(ls(50.0, 5.0), pk(250.0, -1.5, 1.0), pk(6_000.0, 1.5, 1.0), hs(12_000.0, 2.0)),
    ),

    /** Kick body, less boxiness, guitar/vocal presence. */
    ROCK(
        StyleGroup.STAGE,
        listOf(ls(100.0, 2.5), pk(450.0, -1.5, 0.9), pk(3_000.0, 2.5, 1.0), hs(10_000.0, 1.5)),
    ),

    /** Polished modern pop: tight bass, bright vocals. */
    POP(
        StyleGroup.STAGE,
        listOf(ls(100.0, 2.0), pk(350.0, -1.0, 1.0), pk(2_500.0, 1.5, 1.0), hs(10_000.0, 2.0)),
    ),

    /** Concert feel: open, less muddy, vocals and room "in front". */
    LIVE(
        StyleGroup.STAGE,
        listOf(ls(120.0, 1.0), pk(250.0, -1.5, 0.8), pk(2_500.0, 2.0, 0.8), hs(8_000.0, 1.0)),
    ),

    /** Warm, natural instruments: body in the low mids, gentle air. */
    JAZZ_ACOUSTIC(
        StyleGroup.ACOUSTIC,
        listOf(ls(80.0, 1.0), pk(200.0, 1.5, 0.8), pk(3_000.0, -0.5, 1.0), hs(10_000.0, 1.5)),
    ),

    /** Wide dynamics, smooth upper mids, a touch of hall air. */
    CLASSICAL(
        StyleGroup.ACOUSTIC,
        listOf(ls(60.0, 1.5), pk(2_000.0, -1.0, 0.7), hs(12_000.0, 2.0)),
    ),

    /** Speech intelligibility: no rumble, clear consonants, no hiss. */
    SPEECH(
        StyleGroup.SITUATION,
        listOf(ls(120.0, -4.0), pk(2_800.0, 3.0, 1.0), hs(9_000.0, -1.0)),
    ),

    /** Outdoors: no walls reinforce the bass, distance eats presence and air. */
    PARTY_OUTDOOR(
        StyleGroup.SITUATION,
        listOf(ls(80.0, 5.0), pk(2_000.0, 2.5, 0.9), hs(8_000.0, 2.5)),
    ),

    /** Low volume (equal-loudness compensation): ears lose bass and treble first. */
    NIGHT(
        StyleGroup.SITUATION,
        listOf(ls(80.0, 4.0), pk(1_000.0, -1.0, 0.8), hs(10_000.0, 3.0)),
    );

    /** Bands scaled to [intensity] percent (0 = none, 100 = as designed). */
    fun bandsAt(intensity: Int): List<PeqBand> {
        val f = intensity.coerceIn(0, 100) / 100.0
        if (f == 0.0) return emptyList()
        return bands.map { it.copy(gainDb = it.gainDb * f) }.filter { kotlin.math.abs(it.gainDb) >= 0.05 }
    }

    companion object {
        const val MAX_BANDS = 4
        const val MAX_GAIN_DB = 6.0
        const val DEFAULT_INTENSITY = 70

        fun fromName(name: String?): SoundStyle = values().firstOrNull { it.name == name } ?: NEUTRAL
    }
}

/** Shelf / bell Q used by every style: wide and musical. */
private const val SHELF_Q = 0.71

private fun ls(hz: Double, db: Double) = PeqBand(PeqType.LOW_SHELF, hz, db, SHELF_Q)
private fun hs(hz: Double, db: Double) = PeqBand(PeqType.HIGH_SHELF, hz, db, SHELF_Q)
private fun pk(hz: Double, db: Double, q: Double) = PeqBand(PeqType.PEAK, hz, db, q)
