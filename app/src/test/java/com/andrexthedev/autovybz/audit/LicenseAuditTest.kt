package com.andrexthedev.autovybz.audit

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GPL-3.0 compliance: full licence text, upstream attribution, and only GPL-3.0-compatible dependencies
 * in the shipped app (test-only dependencies are listed separately).
 */
class LicenseAuditTest {

    @Test
    fun licenseIsTheFullGpl3Text() {
        val text = ProjectFiles.root("LICENSE").readText()
        assertTrue("GNU GENERAL PUBLIC LICENSE" in text && "Version 3, 29 June 2007" in text)
        assertTrue("truncated licence text", text.length > 30_000 && "END OF TERMS AND CONDITIONS" in text)
    }

    @Test
    fun upstreamIsCredited() {
        for (f in listOf("README.md", "NOTICE.md")) {
            val t = ProjectFiles.root(f).readText()
            assertTrue("$f must credit Equalizer314", "Equalizer314" in t && "bearinmindcat" in t)
            assertTrue("$f must name the licence", "GPL" in t)
        }
        val about = ProjectFiles.strings(ProjectFiles.module("src/main/res/values/autovybz_strings.xml"))
        assertTrue("About screen must mention Equalizer314 + GPL", about.values.any { "Equalizer314" in it && "GPL" in it })
    }

    /** Licence of every shipped dependency group (all Apache-2.0 → compatible with GPL-3.0). */
    private val shipped = mapOf(
        "androidx." to "Apache-2.0",
        "com.google.android.material:" to "Apache-2.0",
        "com.google.oboe:" to "Apache-2.0",
        "org.jetbrains.kotlinx:" to "Apache-2.0",
        "org.jetbrains.kotlin:" to "Apache-2.0",
    )
    private val testOnly = mapOf("junit:" to "EPL-1.0", "org.json:" to "Public Domain (JSON-java 2023+)")

    @Test
    fun everyDependencyHasAKnownCompatibleLicence() {
        val gradle = ProjectFiles.module("build.gradle.kts").readText()
        val deps = Regex("""^\s*(\w+)\(\s*"([^":]+:[^":]+)(:[^"]*)?"\s*\)""", RegexOption.MULTILINE).findAll(gradle)
            .map { it.groupValues[1] to it.groupValues[2] }.toList()
        assertTrue("dependency parsing broken", deps.size > 10)
        val problems = deps.mapNotNull { (conf, coord) ->
            val isTest = conf.startsWith("test") || conf.startsWith("androidTest") || coord.contains("ui-test") || coord.contains("ui-tooling")
            val ok = shipped.keys.any { coord.startsWith(it) } || (isTest && testOnly.keys.any { coord.startsWith(it) })
            if (ok) null else "$conf $coord"
        }
        assertTrue("dependencies without reviewed licence: $problems", problems.isEmpty())
    }

    @Test
    fun noProprietaryTrackingOrAdsSdks() {
        val gradle = ProjectFiles.module("build.gradle.kts").readText().lowercase()
        for (sdk in listOf("firebase", "crashlytics", "play-services-ads", "gms:play-services", "facebook", "appsflyer", "admob", "sentry", "mixpanel"))
            assertTrue("found $sdk", sdk !in gradle)
    }
}
