package com.andrexthedev.autovybz.audit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Static contract for the permanent footer slot: one shell-owned instance, stable height, localized
 * placeholder copy, and no advertising SDK hidden behind the placeholder.
 */
class StickyFooterBannerAuditTest {

    private val activity = ProjectFiles.module(
        "src/main/java/com/andrexthedev/autovybz/ui/AutoVybzActivity.kt",
    ).readText()

    private val banner = ProjectFiles.module(
        "src/main/java/com/andrexthedev/autovybz/ui/components/StickyFooterBanner.kt",
    ).readText()

    @Test
    fun shellComposesTheFooterOnceAndUnconditionallyAbovePortraitNavigation() {
        assertEquals(1, Regex("""\bStickyFooterBanner\(""").findAll(activity).count())

        val bottomBar = activity.substringAfter("bottomBar = {").substringBefore(") { padding ->")
        val footer = bottomBar.indexOf("StickyFooterBanner(onClick")
        val navigation = bottomBar.indexOf("if (!compact) NavigationBar")
        assertTrue("footer must be inside Scaffold.bottomBar", footer >= 0)
        assertTrue("footer must be above the portrait navigation and outside its compact condition", footer < navigation)
        assertTrue("footer click must open AutoVybz Pro", "showPremium = true" in bottomBar)
        assertTrue("shell must host the paywall", "PremiumPaywallDialog(" in activity)
    }

    @Test
    fun placeholderHasAStableHeightTestTagAndLocalizedCopy() {
        assertTrue("const val TestTag = \"avz:sticky-footer\"" in banner)
        assertTrue("val Height = 52.dp" in banner)
        assertTrue("R.string.avz_sticky_footer_placeholder" in banner)

        for (locale in listOf("values", "values-de", "values-es")) {
            val strings = ProjectFiles.strings(ProjectFiles.module("src/main/res/$locale/autovybz_strings.xml"))
            assertTrue("$locale is missing the sticky footer label", !strings["avz_sticky_footer_placeholder"].isNullOrBlank())
        }
    }

    @Test
    fun placeholderIsAnAccessiblePremiumEntryPointAndDoesNotPullInAnAdsSdk() {
        assertTrue("placeholder must expose its optional click action", "clickable(role = Role.Button)" in banner)
        assertTrue("placeholder must not reference Google Mobile Ads", "com.google.android.gms.ads" !in banner)

        val gradle = ProjectFiles.module("build.gradle.kts").readText().lowercase()
        for (sdk in listOf("play-services-ads", "admob", "gms:play-services")) {
            assertTrue("unexpected ads SDK dependency: $sdk", sdk !in gradle)
        }
    }
}
