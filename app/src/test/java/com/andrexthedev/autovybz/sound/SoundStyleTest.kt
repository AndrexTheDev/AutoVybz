package com.andrexthedev.autovybz.sound

import com.andrexthedev.autovybz.measurement.dsp.FrequencyGrid
import com.andrexthedev.autovybz.measurement.eq.AntiClipping
import com.andrexthedev.autovybz.measurement.eq.BiquadMath
import com.andrexthedev.autovybz.measurement.eq.PeqBand
import com.andrexthedev.autovybz.measurement.eq.PeqType
import com.andrexthedev.autovybz.measurement.eq.TargetType
import com.andrexthedev.autovybz.preset.model.AutoVybzPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Sound styles ("Musikrichtungen") and their composition with a calibration. */
class SoundStyleTest {

    private val grid = FrequencyGrid.log(pointsPerOctave = 12)
    private fun curve(bands: List<PeqBand>) = BiquadMath.Grid(grid).response(bands)

    private fun calibration(bands: List<PeqBand>, preamp: Double) = AutoVybzPreset(
        name = "AutoVybz_JBL_2026-10-25", createdAtMillis = 0, deviceManufacturer = "x", deviceModel = "y",
        speakerName = "JBL", target = TargetType.SMART, bandCount = bands.size, bands = bands, preampDb = preamp,
        lowLimitHz = 60.0, highLimitHz = 16_000.0,
    )

    private val cal10 = calibration(
        listOf(
            PeqBand(PeqType.LOW_SHELF, 90.0, 3.5, 0.7), PeqBand(PeqType.PEAK, 180.0, -4.0, 2.0),
            PeqBand(PeqType.PEAK, 320.0, 2.0, 1.5), PeqBand(PeqType.PEAK, 650.0, -3.0, 2.5),
            PeqBand(PeqType.PEAK, 1_200.0, 1.5, 1.2), PeqBand(PeqType.PEAK, 2_400.0, -2.5, 3.0),
            PeqBand(PeqType.PEAK, 4_000.0, 2.5, 2.0), PeqBand(PeqType.PEAK, 6_500.0, -1.5, 2.0),
            PeqBand(PeqType.PEAK, 9_000.0, 1.0, 1.5), PeqBand(PeqType.HIGH_SHELF, 12_000.0, -2.0, 0.7),
        ),
        preamp = -4.2,
    )

    @Test
    fun everyStyleIsGentleAndSmall() {
        for (s in SoundStyle.values()) {
            assertTrue("$s has ${s.bands.size} bands", s.bands.size <= SoundStyle.MAX_BANDS)
            for (b in s.bands) {
                assertTrue("$s band gain ${b.gainDb}", abs(b.gainDb) <= SoundStyle.MAX_GAIN_DB)
                assertTrue("$s band Q ${b.q} not broad", b.q <= 1.4)
                assertTrue("$s band ${b.frequency} Hz outside the audio band", b.frequency in 20.0..20_000.0)
            }
            val c = curve(s.bands)
            assertTrue("$s curve exceeds ±${SoundStyle.MAX_GAIN_DB} dB: ${c.maxOrNull()} / ${c.minOrNull()}",
                c.all { abs(it) <= SoundStyle.MAX_GAIN_DB + 0.05 })
        }
    }

    @Test
    fun neutralIsEmptyAndEveryOtherStyleDiffersAudibly() {
        assertTrue(SoundStyle.NEUTRAL.bands.isEmpty())
        val curves = SoundStyle.values().filter { it != SoundStyle.NEUTRAL }.associateWith { curve(it.bands) }
        for ((s, c) in curves) assertTrue("$s is inaudible", c.maxOf { abs(it) } >= 1.5)
        // no two styles are (almost) the same curve - each choice must mean something
        val list = curves.entries.toList()
        for (i in list.indices) for (j in i + 1 until list.size) {
            val d = list[i].value.indices.maxOf { abs(list[i].value[it] - list[j].value[it]) }
            assertTrue("${list[i].key} ≈ ${list[j].key} (max diff $d dB)", d >= 0.9)
        }
    }

    @Test
    fun intensityScalesLinearlyAndZeroIsIdentity() {
        val s = SoundStyle.REGGAE_DANCEHALL
        assertTrue(s.bandsAt(0).isEmpty())
        assertEquals(s.bands, s.bandsAt(100))
        val half = s.bandsAt(50)
        for ((a, b) in s.bands.zip(half)) assertEquals(a.gainDb / 2, b.gainDb, 1e-9)
        assertEquals(s.bands, s.bandsAt(250)) // clamped
    }

    @Test
    fun persistenceKeysAreStable() {
        assertEquals(SoundStyle.NEUTRAL, SoundStyle.fromName(null))
        assertEquals(SoundStyle.NEUTRAL, SoundStyle.fromName("REMOVED_STYLE"))
        for (s in SoundStyle.values()) assertEquals(s, SoundStyle.fromName(s.name))
        // the user-requested genre split must stay complete
        val required = listOf(
            "HIPHOP_RNB", "REGGAE_DANCEHALL", "AFROBEATS", "ROCK", "ELECTRONIC", "LIVE", "POP",
            "JAZZ_ACOUSTIC", "CLASSICAL", "LATIN", "SPEECH", "PARTY_OUTDOOR",
        )
        for (r in required) assertTrue("missing style $r", SoundStyle.values().any { it.name == r })
    }

    @Test
    fun neutralSelectionReturnsTheCalibrationUnchanged() {
        assertSame(cal10, SoundComposer.compose(cal10, SoundSelection()))
        assertSame(cal10, SoundComposer.compose(cal10, SoundSelection(style = SoundStyle.ROCK, intensity = 0)))
    }

    @Test
    fun composedSoundNeverClipsAndFitsTheEngine() {
        for (s in SoundStyle.values()) for (i in listOf(30, 70, 100)) for (bass in listOf(-6.0, 0.0, 6.0)) {
            val sel = SoundSelection(style = s, intensity = i, bassDb = bass, trebleDb = -bass / 2)
            val out = SoundComposer.compose(cal10, sel)
            assertTrue("$sel: ${out.bands.size} bands", out.bands.size <= SoundComposer.ENGINE_MAX_BANDS)
            assertTrue("calibration bands must come first and unchanged", out.bands.take(10) == cal10.bands)
            val peak = AntiClipping.maxBoostDb(out.bands) + out.preampDb
            assertTrue("$sel clips: peak ${"%.2f".format(peak)} dB", peak <= 0.0)
            assertTrue("pre-gain must be ≤ 0", out.preampDb <= 0.0)
            assertEquals(cal10.name, out.name)
        }
    }

    @Test
    fun calibrationHeadroomMarginIsKept() {
        val boost = AntiClipping.maxBoostDb(cal10.bands)
        val margin = -cal10.preampDb - boost
        val out = SoundComposer.compose(cal10, SoundSelection(style = SoundStyle.ELECTRONIC, intensity = 100))
        val newMargin = -out.preampDb - AntiClipping.maxBoostDb(out.bands)
        assertTrue("margin $newMargin < calibrated $margin", newMargin >= margin - 1e-9)
        assertTrue("margin grossly oversized: $newMargin", newMargin <= margin + 0.11)
    }

    @Test
    fun styleOnlyWorksWithoutCalibration() {
        val base = SoundComposer.styleOnlyBase(0)
        assertTrue(base.bands.isEmpty() && base.preampDb == 0.0)
        val out = SoundComposer.compose(base, SoundSelection(style = SoundStyle.HIPHOP_RNB, intensity = 100))
        assertEquals(SoundStyle.HIPHOP_RNB.bands, out.bands)
        assertTrue(out.preampDb < 0.0 && AntiClipping.maxBoostDb(out.bands) + out.preampDb <= 0.0)
        val speech = SoundComposer.compose(base, SoundSelection(style = SoundStyle.SPEECH, intensity = 100))
        assertTrue(AntiClipping.maxBoostDb(speech.bands) + speech.preampDb <= 0.0)
    }

    @Test
    fun toneShelvesAreClampedAndOmittedAtZero() {
        assertTrue(SoundComposer.toneBands(SoundSelection()).isEmpty())
        val t = SoundComposer.toneBands(SoundSelection(bassDb = 20.0, trebleDb = -20.0))
        assertEquals(listOf(PeqType.LOW_SHELF, PeqType.HIGH_SHELF), t.map { it.type })
        assertEquals(SoundComposer.MAX_TONE_DB, t[0].gainDb, 0.0)
        assertEquals(-SoundComposer.MAX_TONE_DB, t[1].gainDb, 0.0)
    }

    /** "Without calibration": the style alone must shape the sound (regression: the empty base was written as is). */
    @Test
    fun styleOnly_carriesTheStyleAndIsClipSafe() {
        val base = SoundComposer.styleOnlyBase(now = 0L)
        assertSame(base, SoundComposer.compose(base, SoundSelection()))
        val sel = SoundSelection(style = SoundStyle.HIPHOP_RNB, bassDb = 3.0)
        val out = SoundComposer.compose(base, sel)
        assertEquals(SoundComposer.STYLE_ONLY_NAME, out.name)
        assertEquals(SoundStyle.HIPHOP_RNB.bandsAt(sel.intensity).size + 1, out.bands.size)
        assertTrue("pre-gain must cover the boost", out.preampDb <= -AntiClipping.maxBoostDb(out.bands))
    }
}
