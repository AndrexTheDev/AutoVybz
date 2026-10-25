package com.andrexthedev.autovybz.preset

import com.andrexthedev.autovybz.preset.model.AutoVybzPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PresetFilesTest {

    @Test
    fun safeNamesKeepTheirFileName() {
        assertEquals("AutoVybz_JBL-Flip-6_2026-10-24" + AutoVybzPreset.FILE_EXTENSION, PresetFiles.fileName("AutoVybz_JBL-Flip-6_2026-10-24"))
        assertEquals(PresetFiles.legacyFileName("Boom+2.0"), PresetFiles.fileName("Boom+2.0"))
    }

    @Test
    fun namesThatOnlyDifferInUnsafeCharactersGetDifferentFiles() {
        val names = listOf("Küche", "Kéche", "K_che", "K?che", "K/che", "Küche ", "Kueche")
        val files = names.map { PresetFiles.fileName(it) }
        assertEquals(files.toString(), names.size, files.toSet().size)
    }

    @Test
    fun fileNamesAreAlwaysSafeAndDeterministic() {
        val r = Random(7)
        val alphabet = "aZ09._+- /\\:*?\"<>|äöüßéñ😀\u0000\t\n"
        repeat(2000) {
            val name = String(CharArray(r.nextInt(1, 40)) { alphabet[r.nextInt(alphabet.length)] })
            val f = PresetFiles.fileName(name)
            assertTrue(f, Regex("[A-Za-z0-9._+-]+").matches(f))
            assertTrue(f, f.endsWith(AutoVybzPreset.FILE_EXTENSION))
            assertTrue("path traversal: $f", "/" !in f && f != ".." + AutoVybzPreset.FILE_EXTENSION)
            assertEquals(f, PresetFiles.fileName(name))
        }
    }
}
