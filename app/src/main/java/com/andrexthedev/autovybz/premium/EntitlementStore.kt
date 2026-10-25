package com.andrexthedev.autovybz.premium

import android.content.Context

/** SharedPreferences persistence for [Entitlement] (separate file so E2E resets can clear it deterministically). */
class EntitlementStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(nowEpochMs: Long = System.currentTimeMillis()): Entitlement {
        val stored = Entitlement(
            tier = enumValue(prefs.getString(KEY_TIER, null), EntitlementTier.FREE),
            plan = enumValueOrNull(prefs.getString(KEY_PLAN, null)),
            source = enumValue(prefs.getString(KEY_SOURCE, null), EntitlementSource.NONE),
            startedAtEpochMs = prefs.getLong(KEY_STARTED_AT, 0L),
            expiresAtEpochMs = prefs.getLong(KEY_EXPIRES_AT, NO_EXPIRY).takeIf { it != NO_EXPIRY },
            lastSeenEpochMs = prefs.getLong(KEY_LAST_SEEN, 0L),
            trialUsed = prefs.getBoolean(KEY_TRIAL_USED, false),
            coin = enumValueOrNull(prefs.getString(KEY_COIN, null)),
            txidHash = prefs.getString(KEY_TXID_HASH, null),
        )
        val touched = EntitlementPolicy.touch(stored, nowEpochMs)
        if (touched != stored) save(touched)
        return touched
    }

    fun save(entitlement: Entitlement): Entitlement {
        prefs.edit()
            .putString(KEY_TIER, entitlement.tier.name)
            .putString(KEY_PLAN, entitlement.plan?.name)
            .putString(KEY_SOURCE, entitlement.source.name)
            .putLong(KEY_STARTED_AT, entitlement.startedAtEpochMs)
            .putLong(KEY_EXPIRES_AT, entitlement.expiresAtEpochMs ?: NO_EXPIRY)
            .putLong(KEY_LAST_SEEN, entitlement.lastSeenEpochMs)
            .putBoolean(KEY_TRIAL_USED, entitlement.trialUsed)
            .putString(KEY_COIN, entitlement.coin?.name)
            .putString(KEY_TXID_HASH, entitlement.txidHash)
            .apply()
        return entitlement
    }

    fun startTrial(nowEpochMs: Long = System.currentTimeMillis()): Entitlement =
        save(EntitlementPolicy.startTrial(load(nowEpochMs), nowEpochMs))

    fun activateCryptoAlpha(
        plan: PremiumPlan,
        coin: CryptoCoin,
        txid: String,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): Entitlement = save(EntitlementPolicy.activateCryptoAlpha(load(nowEpochMs), plan, coin, txid, nowEpochMs))

    /** Alpha test hook: lets the developer re-test Free → Trial → Pro on the same phone. */
    fun resetAlpha(): Entitlement {
        prefs.edit().clear().apply()
        return Entitlement.free()
    }

    private inline fun <reified T : Enum<T>> enumValue(name: String?, fallback: T): T =
        enumValueOrNull<T>(name) ?: fallback

    private inline fun <reified T : Enum<T>> enumValueOrNull(name: String?): T? =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() }

    companion object {
        const val PREFS_NAME = "autovybz_premium"
        private const val NO_EXPIRY = -1L
        private const val KEY_TIER = "tier"
        private const val KEY_PLAN = "plan"
        private const val KEY_SOURCE = "source"
        private const val KEY_STARTED_AT = "startedAtEpochMs"
        private const val KEY_EXPIRES_AT = "expiresAtEpochMs"
        private const val KEY_LAST_SEEN = "lastSeenEpochMs"
        private const val KEY_TRIAL_USED = "trialUsed"
        private const val KEY_COIN = "coin"
        private const val KEY_TXID_HASH = "txidHash"
    }
}
