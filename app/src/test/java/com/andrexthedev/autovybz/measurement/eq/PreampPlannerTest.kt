package com.andrexthedev.autovybz.measurement.eq

import com.andrexthedev.autovybz.measurement.dsp.FrequencyGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * Gain staging of a finished correction: clipping safety first, perceived loudness second.
 *
 * These are the invariants the user notices: a calibration must never sound quieter than the
 * original - a correction that mostly cuts gets its perceived level back, and with the limiter armed
 * (device / rescue / outdoor) even a correction that boosts comes out loudness-neutral instead of
 * `peak + headroom` dB quieter. Without a limiter the legacy clipping-safe value is kept exactly.
 */
class PreampPlannerTest {

    private val grid = FrequencyGrid.log(pointsPerOctave = 48)

    private fun flat(db: Double) = DoubleArray(grid.size) { db }

    /** Smooth Gaussian band around [fc]: negative = cut, positive = boost; strictly one-sided. */
    private fun band(fc: Double, gainDb: Double, octaves: Double = 0.7): DoubleArray =
        DoubleArray(grid.size) { i ->
            val o = ln(grid[i] / fc) / ln(2.0) / octaves
            gainDb * exp(-o * o)
        }

    @Test
    fun aWeightingMatchesTheStandard() {
        // IEC 61672 reference points.
        assertEquals(0.0, PreampPlanner.aWeightingDb(1000.0), 0.05)
        assertEquals(-3.2, PreampPlanner.aWeightingDb(500.0), 0.3)
        assertEquals(1.3, PreampPlanner.aWeightingDb(2500.0), 0.3)
        assertEquals(-0.1, PreampPlanner.aWeightingDb(6300.0), 0.5)
        assertEquals(-34.6, PreampPlanner.aWeightingDb(40.0), 0.5)
    }

    @Test
    fun loudnessOfAFlatCorrectionIsItsLevel() {
        // A constant offset is the definition of "the same tonal balance, just louder/quieter".
        for (d in listOf(-6.0, -3.0, 0.0, 2.0)) {
            val delta = PreampPlanner.loudnessDeltaDb(grid, flat(0.0), flat(d))
            assertEquals("flat $d dB", d, delta, 0.05)
        }
        // …and it is independent of the recording level (AutoEqPropertyTest: level independence).
        val a = PreampPlanner.loudnessDeltaDb(grid, flat(0.0), band(2000.0, -6.0))
        val b = PreampPlanner.loudnessDeltaDb(grid, flat(37.0), band(2000.0, -6.0))
        assertEquals(a, b, 1e-6)
    }

    @Test
    fun aPresenceCutCostsMoreLoudnessThanABassCut() {
        // Why the make-up matters: the ear is most sensitive around 1-4 kHz. The same cut costs far
        // more perceived level there than at 60 Hz (where it is nearly inaudible at normal levels).
        val presence = -PreampPlanner.loudnessDeltaDb(grid, flat(0.0), band(2000.0, -7.0))
        val bass = -PreampPlanner.loudnessDeltaDb(grid, flat(0.0), band(60.0, -7.0))
        assertTrue("presence $presence dB vs bass $bass dB", presence > bass + 0.7)
        assertTrue("presence cut must be audible: $presence dB", presence in 0.5..4.0)
    }

    @Test
    fun withoutLimiterTheLegacyClippingSafeValueIsKept() {
        // allowance 0 = the static pre-gain is the last line of defence (standard indoor path).
        val cut = band(2000.0, -8.0)
        val plan = PreampPlanner.plan(grid, flat(0.0), cut, headroomDb = 0.5, headroomAllowanceDb = 0.0)
        assertEquals(0.0, plan.preampDb, 0.0) // cut-only: nothing to compensate for clipping
        assertEquals(plan.clippingSafeDb, plan.preampDb, 0.0)
        assertEquals(0.0, plan.makeUpDb, 0.0)

        val boost = band(80.0, 6.0, octaves = 0.5)
        val boosted = PreampPlanner.plan(grid, flat(0.0), boost, headroomDb = 0.5, headroomAllowanceDb = 0.0)
        assertTrue("boost ${boosted.peakDb} preamp ${boosted.preampDb}", boosted.preampDb <= -6.0)
        assertTrue("peak after pre-gain", boosted.peakDb + boosted.preampDb <= 0.0)
        assertEquals(0.0, boosted.makeUpDb, 0.0)
    }

    @Test
    fun cutOnlyCorrectionGetsThePerceivedLevelBack() {
        // Typical device-speaker correction: broad presence cut, nothing boosted.
        val cut = band(2000.0, -7.0, octaves = 1.0)
        val plan = PreampPlanner.plan(
            grid, flat(0.0), cut, headroomDb = 0.5,
            headroomAllowanceDb = PreampPlanner.LIMITER_HEADROOM_ALLOWANCE_DB,
        )
        assertTrue("make-up ${plan.makeUpDb}", plan.makeUpDb >= 1.0)
        assertEquals(0.0, plan.peakDb, 0.01) // the correction itself boosts nothing
        // …so the whole static overshoot is the make-up, which the allowance has to cover.
        assertTrue("overshoot ${plan.makeUpDb}", plan.makeUpDb <= PreampPlanner.LIMITER_HEADROOM_ALLOWANCE_DB)
        // Perceived level after the correction: unchanged (within the 0.5 dB grid resolution).
        val residual = plan.loudnessDeltaDb + plan.preampDb
        assertTrue("residual level change $residual dB", abs(residual) < 0.5)
    }

    @Test
    fun makeUpIsBoundedByTheLimiterAllowance() {
        val cut = band(1500.0, -12.0, octaves = 0.5)
        val bounded = PreampPlanner.plan(
            grid, flat(0.0), cut, headroomAllowanceDb = 100.0, maxMakeUpDb = 0.5,
        )
        // A cut-only correction has no boost, so the static bound is the plain headroom (-0.5 dB) and
        // the make-up can use at most `maxMakeUpDb` above it. (Before the ceiling existed the bound was
        // 0 dB + 0.5 dB = 0.5 dB, i.e. the headroom was spent on loudness instead of safety.)
        assertEquals(0.0, bounded.preampDb, 0.1)
        assertEquals(-0.5, bounded.clippingSafeDb, 0.1)

        // A genuine +9 dB boost with the limiter armed: the pre-gain may use the bounded share of the
        // limiter's headroom (the allowance) so the correction does not come out 9.5 dB quieter - but
        // the static overshoot never exceeds the allowance, and the pre-gain never drops below the
        // clipping-safe value.
        val boost = band(100.0, 9.0, octaves = 0.5)
        val plan = PreampPlanner.plan(
            grid, flat(0.0), boost, headroomDb = 0.5,
            headroomAllowanceDb = PreampPlanner.LIMITER_HEADROOM_ALLOWANCE_DB,
        )
        assertEquals(-9.5, plan.clippingSafeDb, 0.1)
        assertTrue("preamp ${plan.preampDb} above clipping-safe", plan.preampDb > plan.clippingSafeDb)
        assertTrue("make-up ${plan.makeUpDb} within allowance", plan.makeUpDb <= PreampPlanner.LIMITER_HEADROOM_ALLOWANCE_DB)
        assertTrue(
            "static overshoot bounded",
            plan.peakDb + plan.preampDb <= PreampPlanner.LIMITER_HEADROOM_ALLOWANCE_DB + 1e-9,
        )
        // The bass boost adds almost nothing to the A-weighted loudness, so the loudness-neutral target
        // sits at the allowance boundary.
        assertEquals(
            plan.clippingSafeDb + PreampPlanner.LIMITER_HEADROOM_ALLOWANCE_DB,
            plan.preampDb,
            0.1,
        )
    }

    @Test
    fun boostingCorrectionComesOutLoudnessNeutralWithTheLimiter() {
        // The device-speaker case: a presence cut (loudness cost) plus a treble boost (clipping-safe
        // attenuation). With the limiter armed the corrected chain must come out close to as loud as
        // the original - the legacy rule made it peak + headroom dB quieter. What remains is the part
        // the measured limiter allowance does not cover (the probe measures that on real programme).
        val presenceCut = band(2000.0, -6.0)
        val trebleBoost = band(9000.0, 4.0, octaves = 0.5)
        val mixed = DoubleArray(grid.size) { presenceCut[it] + trebleBoost[it] }
        val plan = PreampPlanner.plan(
            grid, flat(0.0), mixed, headroomDb = 0.5,
            headroomAllowanceDb = PreampPlanner.LIMITER_HEADROOM_ALLOWANCE_DB,
        )
        assertTrue("peak ${plan.peakDb}", plan.peakDb > 0.0)
        // Residual perceived level change of the output vs. the original: never louder, and never
        // quieter than the allowance allows.
        val residual = plan.loudnessDeltaDb + plan.preampDb
        assertTrue("residual $residual dB", residual <= 1e-9)
        assertTrue("residual $residual dB within allowance", residual >= -PreampPlanner.LIMITER_HEADROOM_ALLOWANCE_DB - 0.6)
        assertTrue("static overshoot bounded", plan.peakDb + plan.preampDb <= PreampPlanner.LIMITER_HEADROOM_ALLOWANCE_DB + 1e-9)
    }

    @Test
    fun mixedCorrectionRecoversTheLevelOnlyUpToTheAllowance() {
        // A cut plus a small boost: the clipping-safe value is negative, the loudness-neutral target is
        // capped by the allowance (the limiter's headroom).
        val presenceCut = band(2000.0, -8.0)
        val trebleBoost = band(9000.0, 4.0, octaves = 0.5)
        val mixed = DoubleArray(grid.size) { presenceCut[it] + trebleBoost[it] }
        val plan = PreampPlanner.plan(
            grid, flat(0.0), mixed, headroomDb = 0.5,
            headroomAllowanceDb = PreampPlanner.LIMITER_HEADROOM_ALLOWANCE_DB,
        )
        assertTrue("peak ${plan.peakDb}", plan.peakDb > 0.0)
        assertTrue("preamp ${plan.preampDb} must beat the clipping-safe offset", plan.preampDb > plan.clippingSafeDb)
        assertTrue("make-up ${plan.makeUpDb} within allowance", plan.makeUpDb in 0.0..PreampPlanner.LIMITER_HEADROOM_ALLOWANCE_DB)
        assertTrue(
            "static overshoot bounded",
            plan.peakDb + plan.preampDb <= PreampPlanner.LIMITER_HEADROOM_ALLOWANCE_DB + 1e-9,
        )
    }

    @Test
    fun makeUpCanBeSwitchedOffForTheOldBehaviour() {
        val cut = band(2000.0, -8.0)
        val plan = PreampPlanner.plan(
            grid, flat(0.0), cut, headroomAllowanceDb = 6.0, loudnessMakeUp = false,
        )
        assertEquals(0.0, plan.preampDb, 0.0)
        assertEquals(0.0, plan.loudnessDeltaDb, 0.0)
        assertEquals(0.0, plan.makeUpDb, 0.0)
    }
}
