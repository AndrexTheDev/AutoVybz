package com.andrexthedev.autovybz.e2e

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.anyIntent
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent
import androidx.test.espresso.intent.matcher.IntentMatchers.hasData
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andrexthedev.autovybz.ui.about.AutoVybzInfo
import com.bearinmind.equalizer314.LimiterActivity
import com.bearinmind.equalizer314.MainActivity
import com.bearinmind.equalizer314.MbcActivity
import com.bearinmind.equalizer314.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Tabs, help, about, donations (clipboard), feedback mail and the links into the Equalizer314 engine. */
@RunWith(AndroidJUnit4::class)
class NavigationE2ETest : E2eTest() {

    private fun clipboardText(): String? {
        var text: String? = null
        E2e.instrumentation.runOnMainSync {
            val cm = E2e.app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            text = cm.primaryClip?.getItemAt(0)?.text?.toString()
        }
        return text
    }


    @Test
    fun allTabsAreReachable() {
        compose.await("avz:start")
        shot("wizard_idle")
        compose.tap("avz:tab:PRO"); compose.await("avz:screen:pro"); shot("pro")
        compose.tap("avz:tab:HELP"); compose.await("avz:screen:help"); shot("help")
        compose.tap("avz:tab:ABOUT"); compose.await("avz:screen:about"); shot("about")
        compose.tap("avz:tab:WIZARD"); compose.await("avz:start")
    }

    @Test
    fun stickyFooterBanner_staysVisibleAcrossTabs() {
        val footer = "avz:sticky-footer"
        fun assertFooter() {
            compose.await(footer)
            compose.onNodeWithTag(footer).assertIsDisplayed()
            assertTrue("placeholder label visible", compose.hasText(str(R.string.avz_sticky_footer_placeholder)))
        }

        compose.await("avz:start"); assertFooter()
        compose.tap("avz:tab:HOME"); compose.await("avz:screen:home"); assertFooter()
        compose.tap("avz:tab:PRO"); compose.await("avz:screen:pro"); assertFooter()
        compose.tap("avz:tab:HELP"); compose.await("avz:screen:help"); assertFooter()
        compose.tap("avz:tab:ABOUT"); compose.await("avz:screen:about"); assertFooter()
        compose.tap("avz:tab:WIZARD"); compose.await("avz:start"); assertFooter()

        val footerBounds = compose.onNodeWithTag(footer).getUnclippedBoundsInRoot()
        val homeTabBounds = compose.onNodeWithTag("avz:tab:HOME").getUnclippedBoundsInRoot()
        assertTrue(
            "sticky footer must sit above, not over, the navigation: footer=$footerBounds homeTab=$homeTabBounds",
            footerBounds.bottom <= homeTabBounds.top,
        )
        shot("sticky_footer_banner")
    }

    @Test
    fun helpSections_expandAndCollapse() {
        compose.tap("avz:tab:HELP")
        compose.await("avz:screen:help")
        val title = str(R.string.avz_help_s5_title)
        val body = str(R.string.avz_help_s5_body).take(30)
        assertTrue("collapsed initially", !compose.hasText(body))
        compose.onNodeWithText(title).performScrollTo().performClick()
        compose.awaitText(body)
        shot("help_expanded")
        compose.onNodeWithText(title).performScrollTo().performClick()
        compose.waitUntil(5_000) { !compose.hasText(body) }
    }

    @Test
    fun wizardHelpLink_opensHelp() {
        compose.await("avz:start")
        compose.onNodeWithText(str(R.string.avz_wizard_help_link), substring = true).performScrollTo().performClick()
        compose.await("avz:screen:help")
    }

    @Test
    fun donateDialog_copiesAllThreeAddressesExactly() {
        compose.tap("avz:tab:ABOUT")
        compose.tap("avz:about:donate")
        compose.await("avz:donate:SOL")
        shot("donate")
        for ((tag, address) in listOf("SOL" to AutoVybzInfo.DONATE_SOL, "BTC" to AutoVybzInfo.DONATE_BTC, "ETH" to AutoVybzInfo.DONATE_ETH)) {
            compose.tap("avz:donate:copy:$tag")
            assertEquals("clipboard after copying $tag", address, clipboardText())
            assertTrue("$tag address visible", compose.hasText(address))
        }
        compose.tap("avz:donate:close")
        compose.awaitGone("avz:donate:SOL")
    }

    @Test
    fun feedbackMail_isAddressedToTheDeveloper() = withIntents {
        compose.tap("avz:tab:ABOUT")
        compose.tap("avz:about:mail")
        intended(hasAction(Intent.ACTION_SENDTO))
        intended(hasData("mailto:${AutoVybzInfo.CONTACT_EMAIL}"))
        val mail = Intents.getIntents().last { it.action == Intent.ACTION_SENDTO }
        assertTrue(mail.getStringExtra(Intent.EXTRA_SUBJECT)!!.startsWith("AutoVybz"))
        assertTrue("device info for bug reports", mail.getStringExtra(Intent.EXTRA_TEXT)!!.contains("API "))
    }

    @Test
    fun proLinks_openTheEqualizer314Engine() = withIntents {
        compose.tap("avz:tab:PRO")
        compose.await("avz:screen:pro")
        for ((label, activity) in listOf(R.string.avz_pro_peq to MainActivity::class.java, R.string.avz_pro_mbc to MbcActivity::class.java, R.string.avz_pro_limiter to LimiterActivity::class.java)) {
            compose.onAllNodesWithText(str(label)).onFirst().performScrollTo().performClick()
            compose.waitForIdle()
            intended(hasComponent(activity.name))
        }
    }
}
