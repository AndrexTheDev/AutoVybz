package com.andrexthedev.autovybz.measurement

import android.content.Context
import android.util.Log
import com.andrexthedev.autovybz.settings.AutoVybzSettings
import java.io.File

/**
 * Storage for [FieldLog] samples: one JSON object per line in `files/autovybz/fieldlog.jsonl`.
 *
 * Three properties matter more than the format:
 *
 * * **Local only.** Nothing here opens a socket. The file leaves the device only when the user exports it
 *   (Studio → field data), which is the whole reason that screen shows the count before offering the file.
 * * **Bounded.** [MAX_SAMPLES] lines, oldest dropped first. A log that grows without a limit is a bug
 *   report waiting to happen on a phone with 200 MB free.
 * * **Never in the way.** [record] swallows its own errors: a calibration that just took thirty seconds
 *   must not fail because a log file could not be appended. The failure goes to logcat instead.
 */
object FieldLogStore {

    private const val TAG = "AutoVybzFieldLog"
    const val FILE_NAME = "fieldlog.jsonl"

    /** Cap: ~2000 calibrations of a dozen numbers each is well under a megabyte. */
    const val MAX_SAMPLES = 2000

    fun file(context: Context): File = File(context.filesDir, "autovybz/$FILE_NAME")

    fun isEnabled(context: Context): Boolean = AutoVybzSettings(context).fieldLog

    /**
     * Appends [sample] unless the user turned collection off. Returns true when something was written (the
     * caller does not have to care; tests and the settings screen do).
     */
    @Synchronized
    fun record(context: Context, sample: FieldLog.Sample): Boolean {
        if (!isEnabled(context)) return false
        return try {
            val f = file(context)
            f.parentFile?.mkdirs()
            f.appendText(FieldLog.toJson(sample) + "\n")
            trim(f)
            true
        } catch (e: Exception) {
            Log.w(TAG, "could not append a field sample", e)
            false
        }
    }

    /** All samples, oldest first. Unparsable lines are skipped, not repaired. */
    fun samples(context: Context): List<FieldLog.Sample> = try {
        val f = file(context)
        if (!f.exists()) emptyList()
        else f.readLines().mapNotNull { FieldLog.fromJson(it) }
    } catch (e: Exception) {
        Log.w(TAG, "could not read the field log", e)
        emptyList()
    }

    /** Number of usable samples - what the settings card shows. */
    fun count(context: Context): Int = samples(context).size

    /** The file's own bytes, for the export (no re-encoding, so a future schema still exports as written). */
    fun text(context: Context): String = try {
        val f = file(context)
        if (f.exists()) f.readText() else ""
    } catch (e: Exception) {
        Log.w(TAG, "could not read the field log", e)
        ""
    }

    fun clear(context: Context) {
        try {
            file(context).delete()
        } catch (e: Exception) {
            Log.w(TAG, "could not delete the field log", e)
        }
    }

    /** Keeps the newest [MAX_SAMPLES] lines. Only rewrites when the cap is actually exceeded. */
    private fun trim(f: File) {
        val lines = f.readLines().filter { it.isNotBlank() }
        if (lines.size <= MAX_SAMPLES) return
        f.writeText(lines.takeLast(MAX_SAMPLES).joinToString("\n", postfix = "\n"))
    }
}
