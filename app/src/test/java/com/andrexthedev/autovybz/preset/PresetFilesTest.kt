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
    fun nameCanBeReadFromTheFileHeadWithoutAPresetParser() {
        // The calibration index is rebuilt once from the stored files. Their measurement arrays are
        // hundreds of kilobytes, so only the first lines (format, version, name) are read.
        val name = "Küche \"Wand\" 2"
        val json = com.andrexthedev.autovybz.preset.model.AutoVybzPreset(
            name = name, createdAtMillis = 1L, deviceManufacturer = "Google", deviceModel = "Pixel",
            speakerName = null, target = com.andrexthedev.autovybz.measurement.eq.TargetType.SMART,
            bandCount = 0, bands = emptyList(), preampDb = 0.0, lowLimitHz = 20.0, highLimitHz = 20000.0,
        ).toInternalJson()
        val head = json.lineSequence().take(4).joinToString("\n")
        assertEquals(name, PresetFiles.nameFromHead(head))
        // Full document works as well (same regex), and a foreign file yields null instead of a wrong name.
        assertEquals(name, PresetFiles.nameFromHead(json))
        assertEquals(null, PresetFiles.nameFromHead("{\"preamp\":0,\"bands\":[]}"))
        assertEquals(null, PresetFiles.nameFromHead(""))
    }

    @Test
    fun jsonEscapesAreUndone() {
        assertEquals("a\"b", PresetFiles.unescapeJson("a\\\"b"))
        assertEquals("back\\slash", PresetFiles.unescapeJson("back\\\\slash"))
        assertEquals("Küche", PresetFiles.unescapeJson("K\\u00fcche"))
        assertEquals("tab\there", PresetFiles.unescapeJson("tab\\there"))
        // Broken escapes stay literal instead of throwing.
        assertEquals("x\\u12", PresetFiles.unescapeJson("x\\u12"))
        assertEquals("plain", PresetFiles.unescapeJson("plain"))
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
