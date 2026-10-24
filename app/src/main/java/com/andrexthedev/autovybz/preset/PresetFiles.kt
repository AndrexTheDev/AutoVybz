package com.andrexthedev.autovybz.preset

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
}
