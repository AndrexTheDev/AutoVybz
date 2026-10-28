package com.andrexthedev.autovybz.preset

/**
 * How a saved calibration is *named* on screen.
 *
 * The stored name is a file-safe identifier (`AutoVybz_<device>_<yyyy-MM-dd>`, see
 * [com.andrexthedev.autovybz.export.PresetNaming]); the device part comes from the firmware and can be as
 * long as the manufacturer wants. The Sound screen's hero card has one narrow column next to the power ring,
 * where such a name wrapped to three lines on a 720 px phone (beta screenshots, es-small-font130) and pushed
 * the speaker picker out of view.
 *
 * [compact] therefore drops the bookkeeping around the device - the `AutoVybz_` prefix, the date and the
 * "-2"/"-3" de-duplication suffix - and leaves what a user recognises. It is a *display* helper only: the
 * stored preset name, the picker chips and the exports keep the full identifier.
 *
 * Pure Kotlin, unit-tested in [com.andrexthedev.autovybz.preset.PresetDisplayNameTest].
 */
object PresetDisplayName {

    private val PREFIX = Regex("^AutoVybz_")
    /** Trailing `_yyyy-MM-dd`, optionally followed by the de-duplication counter `_2`, `_3`, … */
    private val DATE_SUFFIX = Regex("""_\d{4}-\d{2}-\d{2}(_\d+)?$""")

    /**
     * A short, human-readable form of [name]: `AutoVybz_JBL-Flip-6_2026-10-28` → `JBL-Flip-6`.
     * Anything that does not look like a generated name (a calibration the user named themselves) is
     * returned unchanged - their wording is not ours to shorten.
     */
    fun compact(name: String): String {
        val withoutDate = DATE_SUFFIX.replace(name, "")
        if (withoutDate == name) return name // no generated date -> hand-named preset, keep it verbatim
        val withoutPrefix = PREFIX.replace(withoutDate, "")
        return withoutPrefix.ifBlank { name }
    }

    /** What the Sound screen shows for a calibration: the speaker name if it has one, else the compact name. */
    fun of(speakerName: String?, presetName: String): String =
        speakerName?.takeIf { it.isNotBlank() } ?: compact(presetName)

    /**
     * [labels] made distinguishable inside one list (the speaker picker).
     *
     * Compacting names makes collisions possible: two calibrations of the same speaker measured on different
     * days both read "JBL Flip 6". The old code appended the date of *every* colliding entry; if that date is
     * the same too (two runs the same day) the chips became indistinguishable. Here the date is only added
     * while it actually separates entries, and a numbered suffix is the last resort - so the list always maps
     * one-to-one to the calibrations, in the given order.
     */
    fun uniqueLabels(labels: List<String>, timestamps: List<Long>, formatDate: (Long) -> String): List<String> {
        require(labels.size == timestamps.size)
        val counts = labels.groupingBy { it }.eachCount()
        val used = HashSet<String>()
        return labels.mapIndexed { i, base ->
            val candidate = if (counts.getValue(base) > 1) "$base · ${formatDate(timestamps[i])}" else base
            if (used.add(candidate)) {
                candidate
            } else {
                var n = 2
                var numbered: String
                do { numbered = "$base ($n)"; n++ } while (!used.add(numbered))
                numbered
            }
        }
    }
}
