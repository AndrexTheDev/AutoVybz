package com.andrexthedev.autovybz.ui.share

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.andrexthedev.autovybz.measurement.MeasurementOutcome
import com.andrexthedev.autovybz.measurement.Verification
import com.bearinmind.equalizer314.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Shares the rendered result card (PNG) plus a short text through the Android share sheet. */
object ResultSharer {

    const val SITE_URL = "https://andrexthedev.github.io/AutoVybz/"
    const val SITE_LABEL = "andrexthedev.github.io/AutoVybz"
    private const val SHARE_DIR = "share"

    /**
     * Renders the card off the main thread first: a 1080 x 1350 PNG at quality 100 plus the cache clean-up took
     * long enough to matter on a slow device - monkey ANRs on API 30 (campaigns of 139bea7 / d251336) showed
     * "Input dispatching timed out" while the main thread was busy, and the share button was the only place that
     * encoded a bitmap inside an onClick. Call from a main-dispatched scope; the chooser is started on main.
     */
    suspend fun share(
        context: Context,
        outcome: MeasurementOutcome,
        presetName: String,
        verification: Verification.Report? = null,
    ) {
        try {
            val file = withContext(Dispatchers.IO) { writeCard(context, outcome, presetName, verification) }
            val uri = FileProvider.getUriForFile(context, context.packageName + ".avzfiles", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, context.getString(R.string.avz_share_text, SITE_URL))
                clipData = ClipData.newRawUri(null, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(send, context.getString(R.string.avz_share_chooser)).apply {
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            Log.e("AutoVybz", "share failed", e)
            Toast.makeText(context, R.string.avz_share_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /** Renders the card into cache/share/ (old cards are removed; the cache is never backed up). */
    private fun writeCard(
        context: Context,
        outcome: MeasurementOutcome,
        presetName: String,
        verification: Verification.Report?,
    ): File {
        val dir = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "AutoVybz_result_${outcome.createdAtMillis}.png")
        val bmp = ResultCardRenderer.render(context, outcome, presetName, verification)
        try {
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bmp.recycle()
        }
        return file
    }
}
