package com.bearinmind.equalizer314.autoeq

import com.andrexthedev.autovybz.audit.ProjectFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The packed AutoEq database (assets/autoeq/autoeq.bin, built by
 * tools/autoeq/pack_autoeq.py) must be a bit-exact stand-in for the former
 * index.json + 8850 .txt assets: every reconstructed entry path must name the
 * canonical text file, and every decoded float must have the identical bit
 * pattern [AutoEqParser] (Float.parseFloat) produces — including "-0.0" gains.
 */
class AutoEqPackTest {

    private val bin: ByteArray =
        ProjectFiles.module("src/main/assets/autoeq/autoeq.bin").readBytes()
    private val pack: AutoEqPack.Data =
        requireNotNull(AutoEqPack.read(bin)) { "autoeq.bin failed to parse" }
    private val profilesDir: File = ProjectFiles.root("tools/autoeq/data/profiles")

    private fun assertBits(msg: String, expected: Float, actual: Float) {
        assertEquals(msg, expected.toRawBits(), actual.toRawBits())
    }

    private fun assertProfileMatches(index: Int) {
        val entry = pack.entries[index]
        val txt = File(profilesDir, entry.path)
        assertTrue("reconstructed path must exist: ${entry.path}", txt.isFile)
        val ref = AutoEqParser.parse(txt.readText())
        val got = pack.profile(index)
        assertNotNull("reference parse: ${entry.path}", ref)
        assertNotNull("packed profile: ${entry.path}", got)
        ref!!
        got!!
        assertEquals("perChannel ${entry.path}", ref.perChannel, got.perChannel)
        assertBits("preamp ${entry.path}", ref.preampDb, got.preampDb)
        assertEquals("filter count ${entry.path}", ref.filters.size, got.filters.size)
        for (i in ref.filters.indices) {
            val r = ref.filters[i]
            val g = got.filters[i]
            assertEquals("type ${entry.path} #$i", r.filterType, g.filterType)
            assertBits("freq ${entry.path} #$i", r.frequency, g.frequency)
            assertBits("gain ${entry.path} #$i", r.gain, g.gain)
            assertBits("q ${entry.path} #$i", r.q, g.q)
        }
    }

    @Test
    fun entryCountMatchesDataFiles() {
        val txtCount = profilesDir.walkTopDown().count { it.isFile && it.name.endsWith(".txt") }
        assertEquals(txtCount, pack.entries.size)
        assertTrue(pack.entries.size > 8_000)
    }

    @Test
    fun entriesAreWellFormed() {
        val paths = HashSet<String>()
        for (e in pack.entries) {
            assertTrue(e.name.isNotEmpty())
            assertTrue(e.source.isNotEmpty())
            assertTrue(e.path.endsWith(".txt") && e.path.startsWith(e.source + "/"))
            assertTrue("paths must be unique: ${e.path}", paths.add(e.path))
        }
    }

    @Test
    fun sampledProfilesAreBitIdenticalToTextParse() {
        val last = pack.entries.size - 1
        // Edges + a deterministic spread across the whole database.
        val indices = (listOf(0, 1, 2, last - 1, last) + (0..last step 211)).distinct()
        for (i in indices) assertProfileMatches(i)
    }

    @Test
    fun negativeZeroGainIsPreserved() {
        // A handful of bundled profiles contain "-0.0" gains; find one and
        // verify it round-trips through the sentinel with its sign bit intact.
        val idx = pack.entries.indices.firstOrNull { i ->
            pack.profile(i)!!.filters.any { it.gain.toRawBits() == Int.MIN_VALUE }
        }
        assertNotNull("expected at least one -0.0 gain in the database", idx)
        assertProfileMatches(idx!!)
    }

    @Test
    fun profileOutOfRangeIsNull() {
        assertNull(pack.profile(-1))
        assertNull(pack.profile(pack.entries.size))
    }

    @Test
    fun malformedInputIsRejected() {
        assertNull(AutoEqPack.read(ByteArray(0)))
        assertNull(AutoEqPack.read(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)))
        assertNull(AutoEqPack.read(bin.copyOf(100))) // truncated
        val badMagic = bin.copyOf()
        badMagic[0] = 'X'.code.toByte()
        assertNull(AutoEqPack.read(badMagic))
    }
}
