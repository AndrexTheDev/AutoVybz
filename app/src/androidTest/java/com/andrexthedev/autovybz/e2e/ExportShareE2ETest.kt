package com.andrexthedev.autovybz.e2e

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra
import org.hamcrest.CoreMatchers.allOf
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Export formats through the system file picker (stubbed) and the share card intent. */
@RunWith(AndroidJUnit4::class)
class ExportShareE2ETest : E2eTest() {

    @Before fun initIntents() = Intents.init()
    @After fun releaseIntents() = Intents.release()

    private fun exportTo(format: String): String {
        val file = File(E2e.app.cacheDir, "export_$format.out").apply { delete() }
        intending(hasAction(Intent.ACTION_CREATE_DOCUMENT))
            .respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.fromFile(file))))
        compose.tap("avz:export:open")
        if (format == "APO") shot("export_menu")
        compose.tap("avz:export:$format")
        compose.waitUntil(10_000) { file.exists() && file.length() > 0 }
        return file.readText()
    }

    /** Pro list: each saved calibration has its own export button (unique tag) and exports exactly that preset. */
    @Test
    fun exportFromPro_exportsTheChosenCalibration() {
        val name = compose.quickCalibration()
        compose.tap("avz:result:saveOnly")
        compose.await("avz:saved")
        compose.savedToProTab()
        val file = File(E2e.app.cacheDir, "export_pro.out").apply { delete() }
        intending(hasAction(Intent.ACTION_CREATE_DOCUMENT))
            .respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.fromFile(file))))
        compose.tap("avz:pro:export:$name")
        compose.tap("avz:export:APO")
        compose.waitUntil(10_000) { file.exists() && file.length() > 0 }
        intended(allOf(hasAction(Intent.ACTION_CREATE_DOCUMENT), hasExtra(Intent.EXTRA_TITLE, "${name}_ParametricEQ.txt")))
        val apo = file.readText()
        assertTrue(apo, apo.lines().any { it.startsWith("Preamp: -") } && apo.contains("Filter 1: ON"))
    }

    @Test
    fun allExportFormats_produceValidFiles() {
        compose.quickCalibration()

        val apo = exportTo("APO")
        assertTrue(apo, apo.lines().any { it.startsWith("Preamp: -") && it.endsWith(" dB") })
        assertTrue(apo, Regex("""Filter 1: ON (PK|LSC|HSC) Fc \d+(\.\d)? Hz Gain -?\d+\.\d dB Q \d+\.\d\d""").containsMatchIn(apo))

        val peq = exportTo("PEQ_JSON")
        val peqValue = JSONTokener(peq).nextValue()
        assertTrue("PEQ JSON parses", peqValue is JSONObject || peqValue is JSONArray)

        val csv = exportTo("REW_CSV")
        val rows = csv.lines().filter { it.isNotBlank() && it.first().isDigit() }
        assertTrue("REW CSV rows ${rows.size}", rows.size > 100)
        rows.forEach { r -> val cols = r.split(Regex("[,\t ]+")); assertTrue(r, cols.size >= 2 && cols[0].toDouble() > 0 && cols[1].toDouble().isFinite()) }

        val internal = exportTo("INTERNAL")
        val json = JSONObject(internal)
        assertTrue(internal.take(200), json.toString().contains("AutoVybz"))
        assertEquals(4, Intents.getIntents().count { it.action == Intent.ACTION_CREATE_DOCUMENT })
    }

    @Test
    fun shareCard_isAPngGrantedThroughTheFileProvider() {
        intending(hasAction(Intent.ACTION_CHOOSER)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
        compose.quickCalibration()
        compose.tap("avz:share")
        compose.waitUntil(15_000) { Intents.getIntents().any { it.action == Intent.ACTION_CHOOSER } }
        intended(hasAction(Intent.ACTION_CHOOSER))
        val chooser = Intents.getIntents().last { it.action == Intent.ACTION_CHOOSER }
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("image/png", send.type)
        @Suppress("DEPRECATION")
        val uri = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals("content", uri.scheme)
        assertEquals("${E2e.PKG}.avzfiles", uri.authority)
        assertTrue("read grant", send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0 || chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val header = E2e.app.contentResolver.openInputStream(uri)!!.use { it.readNBytesCompat(8) }
        assertEquals("PNG signature", listOf(0x89, 0x50, 0x4E, 0x47), header.take(4).map { it.toInt() and 0xFF })
        assertTrue("share text", (send.getStringExtra(Intent.EXTRA_TEXT) ?: "").contains("AutoVybz"))
    }

    private fun java.io.InputStream.readNBytesCompat(n: Int): ByteArray {
        val buf = ByteArray(n); var off = 0
        while (off < n) { val r = read(buf, off, n - off); if (r < 0) break; off += r }
        return buf.copyOf(off)
    }
}
