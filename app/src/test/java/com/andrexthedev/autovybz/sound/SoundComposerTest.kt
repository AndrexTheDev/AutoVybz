package com.andrexthedev.autovybz.sound

import com.andrexthedev.autovybz.measurement.eq.AntiClipping
import com.andrexthedev.autovybz.measurement.eq.PeqBand
import com.andrexthedev.autovybz.measurement.eq.PeqType
import com.andrexthedev.autovybz.measurement.eq.TargetType
import com.andrexthedev.autovybz.preset.model.AutoVybzPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A style or Studio tone is layered *on top of* a calibration. The one thing it must never do is undo the
 * calibration's gain staging - a device-speaker profile gets a positive pre-gain (loudness make-up, see
 * PreampPlanner), and the old composer silently reset that to the clipping-safe value, i.e. the sound
 * dropped several dB as soon as a style was switched on.
 */
class SoundComposerTest {

    /** A cut-only device-speaker calibration: protective pass + presence cut, loudness-compensated pre-gain. */
    private val deviceCalibration = AutoVybzPreset(
        name = "Phone", createdAtMillis = 0L, deviceManufacturer = "Xiaomi", deviceModel = "Redmi Note 13",
        speakerName = null, target = TargetType.HARMAN, bandCount = 2,
        bands = listOf(
            PeqBand(PeqType.HIGH_PASS, 600.0, 0.0, 0.707),
            PeqBand(PeqType.PEAK, 2000.0, -4.0, 1.0),
        ),
        preampDb = 3.0, lowLimitHz = 750.0, highLimitHz = 12_000.0,
    )

    private fun clippingSafe(bands: List<PeqBand>): Double {
        val peak = AntiClipping.maxBoostDb(bands)
        return if (peak > 0.0) -(peak + SoundComposer.HEADROOM_DB) else 0.0
    }

    @Test
    fun makeUpIsThePartOfThePreGainThatIsNotClippingProtection() {
        // The protective pass only attenuates, so the whole +3 dB pre-gain is loudness compensation
        // (the clipping-safe value may sit up to 0.5 dB below zero if the pass' 0 dB asymptote is counted).
        assertTrue("make-up ${SoundComposer.makeUpDb(deviceCalibration)}", SoundComposer.makeUpDb(deviceCalibration) >= 2.4)
        assertEquals(
            deviceCalibration.preampDb - clippingSafe(deviceCalibration.bands),
            SoundComposer.makeUpDb(deviceCalibration),
            1e-9,
        )
        // A classic preset with the clipping-safe pre-gain has no make-up at all.
        val classical = deviceCalibration.copy(
            bands = listOf(PeqBand(PeqType.LOW_SHELF, 80.0, 6.0, 0.7)),
            preampDb = clippingSafe(listOf(PeqBand(PeqType.LOW_SHELF, 80.0, 6.0, 0.7))),
        )
        assertEquals(0.0, SoundComposer.makeUpDb(classical), 0.05)
    }

    @Test
    fun layeringAStyleKeepsTheLoudnessMakeUp() {
        val composed = SoundComposer.compose(deviceCalibration, SoundSelection(SoundStyle.HIPHOP_RNB, 70))
        assertEquals(SoundComposer.makeUpDb(deviceCalibration), composed.preampDb - clippingSafe(composed.bands), 0.15)
        assertTrue("pre-gain lost the make-up: ${composed.preampDb} dB", composed.preampDb > 0.0)
        // Bass the phone cannot play sits under the protective pass: it must not cost any pre-gain either.
        assertTrue("peak ${AntiClipping.maxBoostDb(composed.bands)} dB", AntiClipping.maxBoostDb(composed.bands) <= 2.0)
    }

    @Test
    fun identitySelectionReturnsTheCalibrationUntouched() {
        val composed = SoundComposer.compose(deviceCalibration, SoundSelection())
        assertEquals(deviceCalibration, composed)
    }

    @Test
    fun compositionStaysWithinTheEngineBandLimit() {
        val manyBands = deviceCalibration.copy(
            bands = List(SoundComposer.ENGINE_MAX_BANDS - 2) { PeqBand(PeqType.PEAK, 200.0 * (it + 1), -1.0, 1.0) },
            bandCount = SoundComposer.ENGINE_MAX_BANDS - 2,
        )
        val composed = SoundComposer.compose(manyBands, SoundSelection(SoundStyle.ROCK, 100, bassDb = 3.0, trebleDb = 2.0))
        assertTrue("bands ${composed.bands.size}", composed.bands.size <= SoundComposer.ENGINE_MAX_BANDS)
        assertEquals(composed.bands.size, composed.bandCount)
    }

    /**
     * The band budget used to be filled as "calibration + style + tone" and then cut: on a calibration with
     * more than 12 filters the user's Bass/Höhen shelves were dropped *first* (they are appended last), so the
     * Studio sliders showed +3 dB that never reached the engine. The tone shelves now win over the style.
     */
    @Test
    fun toneShelvesSurviveAFullBandBudgetAndTheStyleIsCutInstead() {
        val big = deviceCalibration.copy(
            bands = List(13) { PeqBand(PeqType.PEAK, 100.0 * (it + 1), -1.0, 1.0) },
            bandCount = 13,
        )
        val selection = SoundSelection(SoundStyle.HIPHOP_RNB, 100, bassDb = 4.0, trebleDb = 3.0)
        val composed = SoundComposer.compose(big, selection)
        assertEquals(SoundComposer.ENGINE_MAX_BANDS, composed.bands.size)
        // 13 calibration filters leave room for the two tone shelves and exactly one style band.
        val tone = SoundComposer.toneBands(selection)
        assertEquals(2, tone.size)
        assertTrue("tone shelves missing: ${composed.bands}", composed.bands.containsAll(tone))
        val styleBands = SoundStyle.HIPHOP_RNB.bandsAt(100)
        assertEquals(1, composed.bands.count { it in styleBands })
        // The calibration itself is never cut in favour of a layer.
        assertTrue(composed.bands.containsAll(big.bands))
        assertEquals(composed.bands.size, composed.bandCount)
    }

    @Test
    fun aFullBudgetStillKeepsEveryToneShelfWhenNoBaseBandsArePresent() {
        // Style only: everything fits, order stays style -> tone.
        val composed = SoundComposer.compose(
            SoundComposer.styleOnlyBase(0L),
            SoundSelection(SoundStyle.ROCK, 100, bassDb = 2.0, trebleDb = 1.0),
        )
        assertEquals(SoundStyle.ROCK.bandsAt(100).size + 2, composed.bands.size)
        assertTrue(composed.bands.takeLast(2) == SoundComposer.toneBands(SoundSelection(bassDb = 2.0, trebleDb = 1.0)))
    }

    @Test
    fun composedCurveNeverClipsWithoutAnAllowance() {
        // Style only, no calibration: the pre-gain is the plain clipping-safe value.
        val composed = SoundComposer.compose(
            SoundComposer.styleOnlyBase(0L),
            SoundSelection(SoundStyle.ELECTRONIC, 100, bassDb = 6.0, trebleDb = 3.0),
        )
        val peak = AntiClipping.maxBoostDb(composed.bands)
        assertTrue("peak $peak dB, preamp ${composed.preampDb} dB", peak + composed.preampDb <= 0.05)
        assertEquals(0.0, SoundComposer.makeUpDb(composed), 0.0)
    }
}
