package com.andrexthedev.autovybz.preset

import com.andrexthedev.autovybz.export.PresetExporters
import com.andrexthedev.autovybz.export.PresetNaming
import com.andrexthedev.autovybz.preset.model.AutoVybzPreset
import java.util.zip.CRC32

/**
 * File names for stored calibrations. Characters outside `[A-Za-z0-9._+-]` are replaced by `_`; to keep
 * names such as "Küche" and "Kéche" (both "K_che") apart, a CRC32 of the exact name is appended whenever
 * something had to be replaced. Names that were already safe keep their old file name.
 */
object PresetFiles {
    private val UNSAFE = Regex("[^A-Za-z0-9._+-]")

    fun fileName(presetName: String): String {
        val safe = UNSAFE.replace(presetName, "_")
        return if (safe == presetName) safe + AutoVybzPreset.FILE_EXTENSION
        else safe + "_" + crc(presetName) + AutoVybzPreset.FILE_EXTENSION
    }

    /** File name used before the collision fix (read / clean-up only). */
    fun legacyFileName(presetName: String): String = UNSAFE.replace(presetName, "_") + AutoVybzPreset.FILE_EXTENSION

    private fun crc(s: String): String {
        val c = CRC32()
        c.update(s.toByteArray(Charsets.UTF_8))
        return String.format("%08x", c.value)
    }

    /**
     * Preset name from the *head* of an internal preset file, i.e. from its first few lines. The full file
     * carries five curve arrays of 1/48-octave data (hundreds of kilobytes); rebuilding that model just to
     * read the name is wasteful when the index of existing calibrations is (re)built once.
     *
     * The writer puts `format`, `formatVersion` and `name` first (see [AutoVybzPreset.toInternalJson]), so
     * the first lines always contain the name. Returns null when the head is not recognisable; callers then
     * fall back to a full parse.
     */
    fun nameFromHead(head: String): String? {
        val match = NAME_IN_JSON.find(head) ?: return null
        return unescapeJson(match.groupValues[1])
    }

    private val NAME_IN_JSON = Regex("\"name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

    /**
     * Undoes the escapes [PresetExporters.jsonString] writes for preset names. Preset names cannot contain
     * backslashes or control characters (see [PresetNaming.isValid]), so quotes are the only realistic case;
     * `\\uXXXX` is still handled because older files or hand-edited ones may contain it.
     */
    internal fun unescapeJson(raw: String): String {
        if ('\\' !in raw) return raw
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c != '\\' || i == raw.length - 1) {
                out.append(c); i++; continue
            }
            when (val esc = raw[i + 1]) {
                '"' -> out.append('"')
                '\\' -> out.append('\\')
                '/' -> out.append('/')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'b' -> out.append('\b')
                'f' -> out.append('\u000C')
                'u' -> {
                    val hex = raw.substring(i + 2, (i + 6).coerceAtMost(raw.length))
                    val code = hex.toIntOrNull(16)
                    if (code != null && hex.length == 4) {
                        out.append(code.toChar()); i += 6; continue
                    }
                    out.append('\\').append(esc); i += 2; continue
                }
                else -> out.append('\\').append(esc)
            }
            i += 2
        }
        return out.toString()
    }
}
