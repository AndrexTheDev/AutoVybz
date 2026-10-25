package com.bearinmind.equalizer314.autoeq

/**
 * Reader for `assets/autoeq/autoeq.bin` ("AEQ1") — the packed form of the bundled
 * AutoEq database, built by tools/autoeq/pack_autoeq.py from the canonical text
 * sources in tools/autoeq/data (index.json + 8850 profile .txt files).
 *
 * Every bundled profile is perfectly regular (Preamp + 10 filters in the fixed
 * order LSC, PK x4, HSC, PK x4; preamp/gain on a 0.1 dB grid, Q on 0.01, Fc
 * integer Hz), so it stores losslessly as 61 bytes: decoding reconstructs the
 * identical Floats the APO text parse produced — `int / scale` in Float is
 * IEEE-correctly-rounded, exactly like Float.parseFloat on the decimal text
 * (proven for all values by the packer and by AutoEqPackTest). Packing saves
 * ~2.5 MB of APK (ZIP headers for 8851 tiny assets dominated) and makes loading
 * a single sequential pass instead of 8850 `org.json` objects + per-file opens.
 *
 * [AutoEqEntry.path] is reconstructed as `source/[rig ]type/name.txt`, which the
 * packer proves identical to the original index for every entry (the path is
 * used as a cache key by the UI).
 */
internal object AutoEqPack {

    // Fixed filter order of every bundled profile (AutoEq export shape).
    private val FILTER_TYPES = arrayOf("LSC", "PK", "PK", "PK", "PK", "HSC", "PK", "PK", "PK", "PK")
    private const val PROFILE_BYTES = 61 // i8 preamp*10 + 10 * (u16 fc, i16 gain*10, u16 q*100)
    private const val GAIN_NEG_ZERO = -32768 // sentinel gain*10 for the text "-0.0" (keep the sign bit)
    private const val MAX_ENTRIES = 1_000_000

    class Data internal constructor(
        val entries: List<AutoEqEntry>,
        private val bytes: ByteArray,
        private val profilesOffset: Int,
    ) {
        /** Profile at [index] (same order as [entries]); null when out of range. */
        fun profile(index: Int): AutoEqProfile? {
            if (index !in entries.indices) return null
            var o = profilesOffset + index * PROFILE_BYTES
            val preampDb = bytes[o].toInt() / 10f
            o++
            val filters = ArrayList<AutoEqFilter>(FILTER_TYPES.size)
            for (j in FILTER_TYPES.indices) {
                val fc = u16(o)
                val gain10 = i16(o + 2)
                val q100 = u16(o + 4)
                o += 6
                filters += AutoEqFilter(
                    filterType = FILTER_TYPES[j],
                    frequency = fc.toFloat(),
                    gain = if (gain10 == GAIN_NEG_ZERO) Float.fromBits(Int.MIN_VALUE) else gain10 / 10f,
                    q = q100 / 100f,
                )
            }
            // Same shape AutoEqParser returns for these single-channel files:
            // left/right default to the flat list, no per-channel split.
            return AutoEqProfile(preampDb = preampDb, filters = filters)
        }

        private fun u16(o: Int): Int =
            (bytes[o].toInt() and 0xFF) or ((bytes[o + 1].toInt() and 0xFF) shl 8)

        private fun i16(o: Int): Int = u16(o).toShort().toInt()
    }

    /** Parse [bytes]; null on any inconsistency (bad magic, truncation, size mismatch). */
    fun read(bytes: ByteArray): Data? {
        return try {
            if (bytes.size < 8) return null
            if (bytes[0] != 'A'.code.toByte() || bytes[1] != 'E'.code.toByte() ||
                bytes[2] != 'Q'.code.toByte() || bytes[3] != '1'.code.toByte()
            ) return null

            var pos = 4
            fun u8(): Int = bytes[pos++].toInt() and 0xFF
            fun str(): String {
                val n = u8()
                val s = String(bytes, pos, n, Charsets.UTF_8)
                pos += n
                return s
            }
            fun dict(): List<String> {
                val count = u8()
                return List(count) { str() }
            }

            val sources = dict()
            val rigs = dict()
            val types = dict()

            val count = (bytes[pos].toInt() and 0xFF) or
                ((bytes[pos + 1].toInt() and 0xFF) shl 8) or
                ((bytes[pos + 2].toInt() and 0xFF) shl 16) or
                ((bytes[pos + 3].toInt() and 0xFF) shl 24)
            pos += 4
            if (count < 0 || count > MAX_ENTRIES) return null

            val entries = ArrayList<AutoEqEntry>(count)
            for (i in 0 until count) {
                val name = str()
                val source = sources.getOrNull(u8()) ?: return null
                val type = types.getOrNull(u8()) ?: return null
                val rig = rigs.getOrNull(u8()) ?: return null
                val path = "$source/${if (rig.isEmpty()) "" else "$rig "}$type/$name.txt"
                entries += AutoEqEntry(name, source, type, rig, path)
            }
            // The profile section must be exactly count fixed-size records.
            if (bytes.size - pos != count * PROFILE_BYTES) return null
            Data(entries, bytes, pos)
        } catch (e: Exception) {
            null
        }
    }
}
