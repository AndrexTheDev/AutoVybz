package com.andrexthedev.autovybz.audit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * i18n audit of the AutoVybz strings (en = values, de, es): completeness, placeholder parity, escaping,
 * format-string safety, dead/undefined keys and forgotten translations. Every finding is listed at once.
 */
class StringResourceAuditTest {

    private val locales = listOf("values", "values-de", "values-es")
    private val files = locales.associateWith { ProjectFiles.strings(ProjectFiles.module("src/main/res/$it/autovybz_strings.xml")) }
    private val base = files.getValue("values")
    private val placeholder = Regex("""%(\d+\$)?[sdf]""")

    private fun report(title: String, problems: List<String>) =
        assertTrue("$title (${problems.size}):\n  " + problems.joinToString("\n  "), problems.isEmpty())

    @Test
    fun everyLocaleHasExactlyTheSameKeys() {
        val problems = files.flatMap { (loc, map) ->
            (base.keys - map.keys).map { "$loc missing $it" } + (map.keys - base.keys).map { "$loc extra $it" }
        }
        report("key mismatch", problems)
        assertTrue("suspiciously few strings: ${base.size}", base.size > 150)
    }

    @Test
    fun keysUseTheAvzPrefixAndAreUnique() {
        locales.forEach { loc ->
            val names = ProjectFiles.xml(ProjectFiles.module("src/main/res/$loc/autovybz_strings.xml"))
                .getElementsByTagName("string").let { l -> (0 until l.length).map { (l.item(it) as org.w3c.dom.Element).getAttribute("name") } }
            assertEquals("duplicate keys in $loc", names.size, names.toSet().size)
            report("keys without avz_ prefix in $loc", names.filterNot { it.startsWith("avz_") })
        }
    }

    @Test
    fun placeholdersAreIdenticalInEveryTranslation() {
        val problems = mutableListOf<String>()
        for ((loc, map) in files) for ((key, text) in map) {
            val expected = placeholder.findAll(base.getValue(key)).map { it.value }.sorted().toList()
            val actual = placeholder.findAll(text).map { it.value }.sorted().toList()
            if (expected != actual) problems += "$loc/$key: $actual instead of $expected"
        }
        report("placeholder mismatch", problems)
    }

    @Test
    fun multiplePlaceholdersArePositional() {
        val problems = files.flatMap { (loc, map) ->
            map.filter { (_, t) -> placeholder.findAll(t).count() > 1 && placeholder.findAll(t).any { it.groupValues[1].isEmpty() } }
                .map { "$loc/${it.key}" }
        }
        report("non-positional multi-argument strings", problems)
    }

    /** A literal '%' in a string that is used with format arguments crashes at runtime. */
    @Test
    fun formatStringsAreValid() {
        val problems = mutableListOf<String>()
        for ((loc, map) in files) for ((key, raw) in map) {
            val text = unescape(raw)
            val n = placeholder.findAll(text).count()
            if (n == 0) continue
            val stray = text.replace(placeholder, "").replace("%%", "")
            if ('%' in stray) problems += "$loc/$key: stray %"
            val args = placeholder.findAll(text).map { m -> if (m.value.endsWith("s")) "x" as Any else if (m.value.endsWith("d")) 1 else 1.0 }.toList()
            runCatching { String.format(Locale.ROOT, text, *args.toTypedArray()) }.onFailure { problems += "$loc/$key: ${it.message}" }
        }
        report("broken format strings", problems)
    }

    /**
     * A literal "70 %" (normal space) is parsed by Lint / java.util.Formatter as the flag ' ' plus a
     * conversion ("% g", "% o"): Lint error StringFormatInvalid. Numbers and % must be joined by a
     * non-breaking space, which is also correct typography (no line break between 70 and %).
     */
    @Test
    fun percentSignsCannotBeMistakenForFormatFlags() {
        val problems = mutableListOf<String>()
        for (loc in locales) with(ProjectFiles) {
            for (e in xml(module("src/main/res/$loc/autovybz_strings.xml")).documentElement.children("string")) {
                val key = e.getAttribute("name"); val t = e.textContent
                // "100 % open": '% o' reads as flag + conversion -> needs formatted="false" (Lint StringFormatInvalid)
                if (e.getAttribute("formatted") != "false" && Regex("""% [A-Za-zÀ-ÿ]""").containsMatchIn(t.replace(placeholder, "")))
                    problems += "$loc/$key: literal '% <letter>' without formatted=\"false\""
                if (e.getAttribute("formatted") == "false" && placeholder.containsMatchIn(t))
                    problems += "$loc/$key: formatted=\"false\" but has placeholders"
                // typography: number and % are joined by a non-breaking space (no line break in "70 %")
                if (Regex("""\d %""").containsMatchIn(t)) problems += "$loc/$key: normal space between number and %"
            }
        }
        report("percent-sign problems", problems)
    }

    /** Every plural has the quantities its language needs (CLDR: es also has "many"), with equal placeholders. */
    @Test
    fun pluralsAreCompleteInEveryLanguage() {
        val required = mapOf("values" to setOf("one", "other"), "values-de" to setOf("one", "other"), "values-es" to setOf("one", "many", "other"))
        val problems = mutableListOf<String>()
        val basePlurals = ProjectFiles.plurals(ProjectFiles.module("src/main/res/values/autovybz_strings.xml"))
        for (loc in locales) {
            val plurals = ProjectFiles.plurals(ProjectFiles.module("src/main/res/$loc/autovybz_strings.xml"))
            if (plurals.keys != basePlurals.keys) problems += "$loc plural keys ${plurals.keys} != ${basePlurals.keys}"
            for ((name, items) in plurals) {
                val missing = required.getValue(loc) - items.keys
                if (missing.isNotEmpty()) problems += "$loc/$name missing quantities $missing"
                val expected = placeholder.findAll(items["other"].orEmpty()).map { it.value }.sorted().toList()
                for ((q, t) in items) {
                    val actual = placeholder.findAll(t).map { it.value }.sorted().toList()
                    if (actual != expected) problems += "$loc/$name[$q]: $actual instead of $expected"
                }
            }
        }
        assertTrue("no plurals found - the filter count must use <plurals>", basePlurals.isNotEmpty())
        report("plural problems", problems)
    }

    /** aapt strips unescaped double quotes and rejects unescaped apostrophes. */
    @Test
    fun quotesAndApostrophesAreEscaped() {
        val problems = files.flatMap { (loc, map) ->
            map.filter { (_, t) -> Regex("""(?<!\\)['"]""").containsMatchIn(t) }.map { "$loc/${it.key}: ${it.value.take(50)}" }
        }
        report("unescaped quotes", problems)
    }

    @Test
    fun noBlankTextStrayWhitespaceOrTodoMarkers() {
        val problems = mutableListOf<String>()
        for ((loc, map) in files) for ((key, t) in map) {
            if (t.isBlank()) problems += "$loc/$key blank"
            if (t != t.trim()) problems += "$loc/$key leading/trailing whitespace"
            if ("  " in t.replace("\\n", "\n")) problems += "$loc/$key double space"
            // Case-sensitive: Spanish "todo" (= all) is not a marker.
            if (Regex("TODO|FIXME|XXX|[Ll]orem ipsum").containsMatchIn(t)) problems += "$loc/$key marker"
            if ("..." in t) problems += "$loc/$key: use … instead of ..."
        }
        report("whitespace / marker issues", problems)
    }

    /** Brand / technical names that are intentionally identical to English. */
    private val sameAsEnglishAllowed = setOf(
        "avz_nav_pro", "avz_target_flat", "avz_target_bass", "avz_target_harman", "avz_export_apo",
        "avz_result_details", "avz_detail_engine", "avz_pro_limiter", "avz_pro_custom_bass", "avz_about_version",
        "avz_donate_sol", "avz_donate_btc", "avz_donate_eth",
        // navigation / brand words used as-is in German ("Sound", "Studio") and genre names that are loanwords
        "avz_nav_home", "avz_pro_title", "avz_studio_bass", "avz_studio_db", "avz_home_intensity_value",
        "avz_style_group_base", "avz_style_afrobeats", "avz_style_latin", "avz_style_electronic", "avz_style_rock", "avz_style_pop",
        // W8: "Subwoofer" is the word German and Spanish actually use (a loanword, not a missing translation),
        // and the chip label "%1$d Hz" is a number with a unit - there is nothing to translate in either.
        "avz_sub_label", "avz_sub_hz",
    )

    @Test
    fun translationsAreNotForgotten() {
        val problems = listOf("values-de", "values-es").flatMap { loc ->
            files.getValue(loc).filter { (k, t) -> t == base[k] && k !in sameAsEnglishAllowed }.map { "$loc/${it.key}: ${it.value.take(40)}" }
        }
        report("untranslated strings", problems)
    }

    /** en “…”, de „…“, es «…» - the end-user test found German „…“ in the English strings (W6). */
    @Test
    fun everyLanguageUsesItsOwnQuotationMarks() {
        val all = { loc: String -> files.getValue(loc) + ProjectFiles.plurals(ProjectFiles.module("src/main/res/$loc/autovybz_strings.xml"))
            .flatMap { (n, items) -> items.map { (q, t) -> "$n[$q]" to t } } }
        val en = all("values").filter { (_, t) -> "„" in t || "«" in t || "»" in t }.keys
        val de = all("values-de").filter { (_, t) -> ("“" in t && "„" !in t) || "«" in t }.keys
        val es = all("values-es").filter { (_, t) -> "„" in t || "“" in t || "”" in t }.keys
        report("wrong quotation marks", en.map { "en/$it" } + de.map { "de/$it" } + es.map { "es/$it" })
    }

    @Test
    fun everyReferencedStringExistsAndEveryStringIsUsed() {
        val code = ProjectFiles.autoVybzSources().joinToString("\n") { it.readText() }
        val res = ProjectFiles.module("src/main").walkTopDown()
            .filter { it.isFile && it.extension == "xml" && it.name != "autovybz_strings.xml" }.joinToString("\n") { it.readText() }
        val used = Regex("""R\.(?:string|plurals)\.(avz_\w+)""").findAll(code).map { it.groupValues[1] }.toSet() +
            Regex("""@string/(avz_\w+)""").findAll(res).map { it.groupValues[1] }.toSet()
        report("referenced but undefined", (used - base.keys).sorted())
        report("defined but never used", (base.keys - used).sorted())
    }

    @Test
    fun noHardcodedUserFacingTextInComposables() {
        // Text("…") / label = { Text("…") } with letters is a forgotten translation. Numbers, symbols and
        // test tags are fine.
        // Brand word mark (logo: "Auto" + "Vybz" in two colours) and link labels are not translated.
        val allowed = setOf("Auto", "Vybz", "GitHub")
        val problems = ProjectFiles.autoVybzSources().filter { "/ui/" in it.path }.flatMap { f ->
            Regex("""\bText\(\s*"([^"$]*[A-Za-zÄÖÜäöüß]{3,}[^"]*)"""").findAll(f.readText()).map { it.groupValues[1] }
                .filterNot { it in allowed || it.startsWith("github.com/") || it.startsWith("https://") }
                .map { "${f.name}: $it" }.toList()
        }
        report("hardcoded UI text", problems)
    }

    /**
     * Error sentences must be complete translations. The engine reports failures with English technical
     * messages ("Cannot open microphone at 48000 Hz"), and the error screen used to splice that into the
     * translated sentence - a German UI with an English fragment in the middle. The raw message belongs in
     * the dedicated technical-detail line, so the user-facing error strings must not take arguments.
     */
    @Test
    fun userFacingErrorSentencesNeverEmbedEngineText() {
        val withArgument = base.keys.filter { it.startsWith("avz_err_") && placeholder.containsMatchIn(base.getValue(it)) }
        // Allowed: the technical-detail line (raw message) and the two counters of the resume flow
        // ("positions measured so far", "repeat position n") - both insert our own, localized numbers.
        val allowed = setOf("avz_err_details", "avz_err_resume_hint", "avz_err_resume_retry")
        report("error strings with a format argument", withArgument.filterNot { it in allowed })
        assertTrue("missing avz_err_details", "avz_err_details" in base)
    }

    /**
     * The inherited Equalizer314 screens (Pro area: 10-band EQ, compressor, limiter, presets) live in strings.xml.
     * Upstream shipped Spanish but no German, so German users saw English there (lint MissingTranslation x440).
     * Every translatable key must exist in de and es with the same placeholders, and German uses its own quotes.
     */
    @Test
    fun inheritedScreensAreFullyTranslated() {
        fun translatable(loc: String): Map<String, String> {
            val f = ProjectFiles.module("src/main/res/$loc/strings.xml")
            val doc = ProjectFiles.xml(f).documentElement
            val skip = (0 until doc.childNodes.length).map { doc.childNodes.item(it) }.filterIsInstance<org.w3c.dom.Element>()
                .filter { it.getAttribute("translatable") == "false" }.map { it.getAttribute("name") }.toSet()
            return ProjectFiles.strings(f).filterKeys { it !in skip }
        }
        val en = translatable("values")
        val anyPlaceholder = Regex("""%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[sdfx]""")
        val problems = mutableListOf<String>()
        for (loc in listOf("values-de", "values-es")) {
            val map = translatable(loc)
            (en.keys - map.keys).forEach { problems += "$loc missing $it" }
            for ((key, text) in map) {
                val ref = en[key] ?: continue
                val a = anyPlaceholder.findAll(ref).map { it.value }.sorted().toList()
                val b = anyPlaceholder.findAll(text).map { it.value }.sorted().toList()
                if (a != b) problems += "$loc/$key: $b instead of $a"
                if (loc == "values-de" && "\"" in text) problems += "$loc/$key uses English quotes: $text"
            }
        }
        report("inherited strings not translated", problems)
        assertTrue("suspiciously few inherited strings: ${en.size}", en.size > 300)
    }

    private fun unescape(raw: String) = raw.replace("\\'", "'").replace("\\\"", "\"").replace("\\n", "\n").replace("\\@", "@").replace("\\?", "?")
}
