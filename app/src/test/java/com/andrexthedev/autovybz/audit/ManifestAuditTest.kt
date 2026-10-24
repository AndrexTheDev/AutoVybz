package com.andrexthedev.autovybz.audit

import com.andrexthedev.autovybz.audit.ProjectFiles.android
import com.andrexthedev.autovybz.audit.ProjectFiles.children
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Security / privacy audit of the merged-source manifest, FileProvider paths and the per-app language list. */
class ManifestAuditTest {

    private val manifest = ProjectFiles.xml(ProjectFiles.module("src/main/AndroidManifest.xml")).documentElement
    private val application = manifest.children("application").single()
    private val components = listOf("activity", "service", "receiver", "provider").flatMap { application.children(it) }

    @Test
    fun autoVybzIsTheLauncher() {
        val launchers = application.children("activity").filter { a ->
            a.children("category").any { it.android("name") == "android.intent.category.LAUNCHER" }
        }.map { it.android("name") }
        assertEquals(listOf("com.andrexthedev.autovybz.ui.AutoVybzActivity"), launchers)
        val main = application.children("activity").first { it.android("name")!!.endsWith("AutoVybzActivity") }
        assertEquals("@style/Theme.AutoVybz", main.android("theme"))
        assertEquals("adjustResize", main.android("windowSoftInputMode"))
    }

    /** Every exported component is either the launcher, guarded by a system permission or explicitly reviewed. */
    @Test
    fun exportedComponentsAreReviewed() {
        val reviewed = setOf(
            "com.andrexthedev.autovybz.ui.AutoVybzActivity", // launcher
            "com.bearinmind.equalizer314.MainActivity", // upstream classic UI (Pro area deep link, audio-effect panel)
            "com.bearinmind.equalizer314.audio.AudioSessionReceiver", // public OPEN/CLOSE_AUDIO_EFFECT_CONTROL_SESSION broadcasts
            "com.bearinmind.equalizer314.audio.BootCompletedReceiver", // BOOT_COMPLETED
        )
        val problems = components.filter { it.android("exported") == "true" }
            .filter { it.android("permission") == null && it.android("name") !in reviewed }
            .map { "${it.tagName} ${it.android("name")}" }
        assertTrue("unreviewed exported components: $problems", problems.isEmpty())
        // Components with intent filters must declare android:exported explicitly (Android 12+ install failure).
        val implicit = components.filter { it.children("intent-filter").isNotEmpty() && it.android("exported") == null }
        assertTrue("missing android:exported: ${implicit.map { it.android("name") }}", implicit.isEmpty())
    }

    @Test
    fun fileProviderIsPrivateAndOnlySharesTheShareCardFolder() {
        val provider = application.children("provider").single { it.android("name") == "androidx.core.content.FileProvider" }
        assertEquals("false", provider.android("exported"))
        assertEquals("true", provider.android("grantUriPermissions"))
        assertEquals("\${applicationId}.avzfiles", provider.android("authorities"))
        val paths = ProjectFiles.xml(ProjectFiles.module("src/main/res/xml/autovybz_file_paths.xml")).documentElement
        val entries = (0 until paths.childNodes.length).map { paths.childNodes.item(it) }.filterIsInstance<org.w3c.dom.Element>()
        assertEquals(1, entries.size)
        assertEquals("cache-path", entries[0].tagName)
        assertEquals("share/", entries[0].getAttribute("path"))
    }

    @Test
    fun noCleartextTrafficOrDebuggableFlag() {
        assertFalse(application.android("usesCleartextTraffic") == "true")
        assertFalse(application.android("debuggable") == "true")
        val nsc = application.android("networkSecurityConfig")
        if (nsc != null) {
            val f = ProjectFiles.module("src/main/res/xml/${nsc.removePrefix("@xml/")}.xml")
            assertFalse("cleartext permitted in $nsc", "cleartextTrafficPermitted=\"true\"" in f.readText())
        }
    }

    @Test
    fun measurementPermissionsArePresent() {
        val perms = manifest.children("uses-permission").map { it.android("name") }.toSet()
        for (p in listOf("android.permission.RECORD_AUDIO", "android.permission.MODIFY_AUDIO_SETTINGS"))
            assertTrue("missing $p", p in perms)
        // AutoVybz itself never needs location, contacts, storage or camera.
        val forbidden = perms.filter { p -> listOf("LOCATION", "CONTACTS", "READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE", "CAMERA", "READ_PHONE_STATE", "SMS").any { it in p!! } }
        assertTrue("unexpected sensitive permissions: $forbidden", forbidden.isEmpty())
    }

    @Test
    fun localeConfigListsExactlyTheFullyTranslatedLanguages() {
        assertEquals("@xml/locales_config", application.android("localeConfig"))
        val cfg = ProjectFiles.xml(ProjectFiles.module("src/main/res/xml/locales_config.xml")).documentElement
        val listed = cfg.children("locale").map { it.android("name")!! }.toSet()
        val translated = ProjectFiles.module("src/main/res").listFiles()!!
            .filter { File(it, "autovybz_strings.xml").exists() }
            .map { if (it.name == "values") "en" else it.name.removePrefix("values-") }.toSet()
        assertEquals(translated, listed)
    }

    private fun File(dir: java.io.File, name: String) = java.io.File(dir, name)
}
