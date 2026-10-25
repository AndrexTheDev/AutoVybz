package com.bearinmind.equalizer314.autoeq

import android.content.Context

/**
 * Bundled AutoEq database, loaded from the packed `assets/autoeq/autoeq.bin`
 * (see [AutoEqPack] — built from tools/autoeq/data by pack_autoeq.py; identical
 * entries and profile values to the former index.json + 8850 .txt assets).
 */
class AutoEqDatabase(private val context: Context) {

    private class State(val pack: AutoEqPack.Data?, val lowerNames: List<String>)

    @Volatile
    private var state: State? = null

    private fun ensureLoaded(): State {
        state?.let { return it }
        return synchronized(this) {
            state ?: run {
                val pack = try {
                    context.assets.open(ASSET_PATH).use { AutoEqPack.read(it.readBytes()) }
                } catch (e: Exception) {
                    android.util.Log.e("AutoEqDB", "Failed to load index", e)
                    null
                }
                // Lowercase names once at load: search() used to lowercase all 8850
                // names for the filter and again inside every sort comparison.
                State(pack, pack?.entries?.map { it.name.lowercase() } ?: emptyList())
                    .also { state = it }
            }
        }
    }

    fun search(query: String): List<AutoEqEntry> {
        val st = ensureLoaded()
        val all = st.pack?.entries ?: emptyList()
        if (query.isBlank()) return all

        val q = query.trim().lowercase()
        val words = q.split(WHITESPACE)
        val lower = st.lowerNames

        val matched = ArrayList<Int>()
        for (i in all.indices) {
            val name = lower[i]
            if (words.all { word -> name.contains(word) }) matched.add(i)
        }
        // Same ranking as before (stable sort), keyed on the precomputed names.
        matched.sortWith(compareBy(
            { !lower[it].startsWith(q) },
            { lower[it].indexOf(q).let { idx -> if (idx < 0) Int.MAX_VALUE else idx } },
            { lower[it] }
        ))
        return matched.map { all[it] }
    }

    fun loadProfile(entry: AutoEqEntry): AutoEqProfile? {
        val pack = ensureLoaded().pack ?: return null
        // Entries handed out by search() are the cached instances — identity first.
        var index = pack.entries.indexOfFirst { it === entry }
        if (index < 0) index = pack.entries.indexOf(entry)
        if (index < 0) {
            android.util.Log.e("AutoEqDB", "Failed to load profile: ${entry.path}")
            return null
        }
        return pack.profile(index)
    }

    fun totalCount(): Int = ensureLoaded().pack?.entries?.size ?: 0

    private companion object {
        const val ASSET_PATH = "autoeq/autoeq.bin"
        val WHITESPACE = "\\s+".toRegex()
    }
}
