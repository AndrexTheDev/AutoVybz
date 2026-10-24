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
    )

    @Test
    fun translationsAreNotForgotten() {
        val problems = listOf("values-de", "values-es").flatMap { loc ->
            files.getValue(loc).filter { (k, t) -> t == base[k] && k !in sameAsEnglishAllowed }.map { "$loc/${it.key}: ${it.value.take(40)}" }
        }
        report("untranslated strings", problems)
    }

    @Test
    fun germanAndSpanishUseTheirOwnQuotationMarks() {
        val de = files.getValue("values-de").filter { (_, t) -> "“" in t && "„" !in t }.keys
        val es = files.getValue("values-es").filter { (_, t) -> "„" in t }.keys
        report("wrong quotation marks", de.map { "de/$it" } + es.map { "es/$it" })
    }

    @Test
    fun everyReferencedStringExistsAndEveryStringIsUsed() {
        val code = ProjectFiles.autoVybzSources().joinToString("\n") { it.readText() }
        val res = ProjectFiles.module("src/main").walkTopDown()
            .filter { it.isFile && it.extension == "xml" && it.name != "autovybz_strings.xml" }.joinToString("\n") { it.readText() }
        val used = Regex("""R\.string\.(avz_\w+)""").findAll(code).map { it.groupValues[1] }.toSet() +
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

    private fun unescape(raw: String) = raw.replace("\\'", "'").replace("\\\"", "\"").replace("\\n", "\n").replace("\\@", "@").replace("\\?", "?")
}
