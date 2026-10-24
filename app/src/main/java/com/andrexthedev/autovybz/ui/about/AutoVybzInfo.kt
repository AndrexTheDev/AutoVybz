package com.andrexthedev.autovybz.ui.about

/**
 * Project facts shown in the app (About, donate dialog, share text). Pure Kotlin so that
 * `ProjectInfoAuditTest` can verify them (incl. the checksums of the donation addresses) on the JVM.
 */
object AutoVybzInfo {
    const val DEVELOPER = "AndrexTheDev"
    const val CONTACT_EMAIL = "hippie.highho@gmail.com"
    const val SOURCE_URL = "https://github.com/AndrexTheDev/AutoVybz"
    const val UPSTREAM_URL = "https://github.com/bearinmindcat/Equalizer314"

    const val DONATE_SOL = "79KsqtJJdhKFJ9woxnYgtf3nq7HxQveafWBCtC3mxWi8"
    const val DONATE_BTC = "bc1qeqzrlfg3edrydk4s0hecakc82gp26n5p7hkc7f"
    const val DONATE_ETH = "0xBC3fab34f69bc9f6661608C3FB36dDdC313C42F7"
}
