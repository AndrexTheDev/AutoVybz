package com.andrexthedev.autovybz.measurement.eq

import com.andrexthedev.autovybz.measurement.dsp.FrequencyGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log2
import kotlin.random.Random

/**
 * Property-based test of the auto-EQ: 200 random "speakers" (resonances, dips, bass/treble roll-offs,
 * absolute level, ripple) x all targets x 5/10 bands. Safety invariants must hold for every single one.
 */
class AutoEqPropertyTest {

    private val grid = FrequencyGrid.log(pointsPerOctave = 24)

    private fun randomSpeaker(r: Random): DoubleArray {
        val bumps = List(r.nextInt(1, 7)) { Triple(40.0 * Math.pow(300.0, r.nextDouble()), r.nextDouble(-9.0, 9.0), r.nextDouble(0.1, 1.5)) }
        val hp = r.nextDouble(40.0, 180.0); val hpOrder = if (r.nextBoolean()) 2 else 4
        val lp = if (r.nextBoolean()) r.nextDouble(8000.0, 18000.0) else null
        val level = r.nextDouble(-25.0, 25.0)
        return DoubleArray(grid.size) { i ->
            val f = grid[i]
            var db = level
            for ((fc, g, w) in bumps) { val o = log2(f / fc) / w; db += g * exp(-o * o) }
            if (f < hp) db -= 6.0 * hpOrder * log2(hp / f)
            if (lp != null && f > lp) db -= 12.0 * log2(f / lp)
            db + r.nextDouble(-0.4, 0.4)
        }
    }

    private fun rms(a: DoubleArray, b: DoubleArray, lo: Double, hi: Double): Double {
        var s = 0.0; var n = 0
        for (i in grid.indices) if (grid[i] in lo..hi) { val d = a[i] - b[i]; s += d * d; n++ }
        return Math.sqrt(s / n)
    }

    @Test
    fun safetyInvariantsHoldForRandomSpeakers() {
        val targets = TargetType.values().toList()
        val problems = mutableListOf<String>()
        for (seed in 0 until 200) {
            val r = Random(seed)
            val measured = randomSpeaker(r)
            val target = TargetCurve(targets[seed % targets.size], CustomTarget())
            val settings = AutoEqSettings(
                bandCount = if (seed % 2 == 0) 10 else 5,
                maxBoostDb = listOf(3.0, 6.0, 9.0)[seed % 3],
                broadBoostOctaves = if (seed % 4 == 0) 0.5 else null,
            )
            val e = AutoEqRenderer.render(grid, measured, target, settings)
            val tag = "seed $seed (${target.type}, ${settings.bandCount} bands, max +${settings.maxBoostDb})"

            if (e.bands.size > settings.bandCount) problems += "$tag: ${e.bands.size} bands"
            e.bands.forEach { b ->
                if (!(b.frequency.isFinite() && b.gainDb.isFinite() && b.q.isFinite())) problems += "$tag: non-finite $b"
                if (b.frequency !in 15.0..24000.0) problems += "$tag: frequency ${b.frequency}"
                if (b.q !in 0.1..20.0) problems += "$tag: Q ${b.q}"
            }
            val maxResp = e.filterResponseDb.max()
            val minResp = e.filterResponseDb.min()
            if (maxResp > settings.maxBoostDb + 0.75) problems += "$tag: boost %.2f dB".format(maxResp)
            if (minResp < -settings.maxCutDb - 1.5) problems += "$tag: cut %.2f dB".format(minResp)
            // Anti-clipping: the pre-gain must cover the largest boost.
            if (e.preampDb > 0.0 || maxResp + e.preampDb > 0.05) problems += "$tag: clipping (boost %.2f, preamp %.2f)".format(maxResp, e.preampDb)
            assertEquals(tag, e.maxFilterBoostDb, maxResp, 0.75)
            // The correction never makes things worse.
            if (e.rmsErrorAfterDb > e.rmsErrorBeforeDb + 0.1) problems += "$tag: worse %.2f -> %.2f".format(e.rmsErrorBeforeDb, e.rmsErrorAfterDb)
            if (e.lowLimitHz !in 20.0..200.0 || e.highLimitHz > settings.highLimitHz + 1e-6) problems += "$tag: limits ${e.lowLimitHz}..${e.highLimitHz}"
            // Never pump bass into the speaker's roll-off (excursion protection): well below the detected
            // low limit the filters must not boost noticeably.
            for (i in grid.indices) if (grid[i] < e.lowLimitHz / 2 && e.filterResponseDb[i] > 1.5) {
                problems += "$tag: +%.1f dB at %.0f Hz below roll-off %.0f Hz".format(e.filterResponseDb[i], grid[i], e.lowLimitHz); break
            }
            // Level independence: shifting the whole measurement must not change the EQ.
            if (seed % 10 == 0) {
                val shifted = AutoEqRenderer.render(grid, DoubleArray(grid.size) { measured[it] + 17.0 }, target, settings)
                val d = shifted.filterResponseDb.indices.maxOf { abs(shifted.filterResponseDb[it] - e.filterResponseDb[it]) }
                if (d > 0.05) problems += "$tag: level dependent (%.2f dB)".format(d)
            }
        }
        assertTrue("${problems.size} violations:\n" + problems.take(40).joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun correctionImprovesTypicalSpeakersOnAverage() {
        var before = 0.0; var after = 0.0
        for (seed in 1000 until 1100) {
            val m = randomSpeaker(Random(seed))
            val e = AutoEqRenderer.render(grid, m, TargetCurve(TargetType.FLAT, CustomTarget()), AutoEqSettings())
            before += e.rmsErrorBeforeDb; after += e.rmsErrorAfterDb
        }
        assertTrue("mean RMS error %.2f -> %.2f dB".format(before / 100, after / 100), after < before * 0.6)
    }

    @Test
    fun renderIsDeterministic() {
        val m = randomSpeaker(Random(99))
        val a = AutoEqRenderer.render(grid, m, TargetCurve(TargetType.SMART, CustomTarget()))
        val b = AutoEqRenderer.render(grid, m, TargetCurve(TargetType.SMART, CustomTarget()))
        assertEquals(a.bands, b.bands)
        assertEquals(a.preampDb, b.preampDb, 0.0)
    }

    @Test
    fun flatSpeakerOnFlatTargetNeedsAlmostNothing() {
        val m = DoubleArray(grid.size) { i -> if (grid[i] < 50) -12.0 * log2(50 / grid[i]) else 0.0 }
        val e = AutoEqRenderer.render(grid, m, TargetCurve(TargetType.FLAT, CustomTarget()))
        val inBand = grid.indices.filter { grid[it] in 100.0..10000.0 }
        assertTrue(inBand.maxOf { abs(e.filterResponseDb[it]) } < 1.0)
        assertTrue(e.preampDb >= -1.5)
    }
}
