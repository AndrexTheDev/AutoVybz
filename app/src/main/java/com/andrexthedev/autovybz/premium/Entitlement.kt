package com.andrexthedev.autovybz.premium

import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.min

/** Paid supporter plans. Crypto payments never auto-renew: a plan simply expires unless paid again. */
enum class PremiumPlan(val priceUsdCents: Int, val durationDays: Long?) {
    MONTHLY(199, 30),
    YEARLY(1_999, 365),
    LIFETIME(9_900, null),
}

/** The only accepted payment rails (the three project addresses stay in `AutoVybzInfo`). */
enum class CryptoCoin { SOL, BTC, ETH }

enum class EntitlementTier { FREE, TRIAL, PRO }

enum class EntitlementSource { NONE, TRIAL, CRYPTO_ALPHA }

/**
 * Local supporter entitlement for the GPL "Path A" model. This is deliberately a convenience/supporter
 * unlock, not DRM: the source code remains GPL-licensed. The Alpha flow activates after a TXID is entered
 * and is reconciled manually by the developer; a signed-key issuer can replace that activation step later
 * without changing the feature policy below.
 */
data class Entitlement(
    val tier: EntitlementTier = EntitlementTier.FREE,
    val plan: PremiumPlan? = null,
    val source: EntitlementSource = EntitlementSource.NONE,
    val startedAtEpochMs: Long = 0L,
    val expiresAtEpochMs: Long? = null,
    val lastSeenEpochMs: Long = 0L,
    val trialUsed: Boolean = false,
    val coin: CryptoCoin? = null,
    val txidHash: String? = null,
) {
    companion object {
        fun free() = Entitlement()
    }
}

/** The single source of truth for "very limited, but usable" Free behaviour. */
data class FeatureLimits(
    val maxSavedProfiles: Int,
    val maxPositions: Int,
    val advancedMeasurementSettings: Boolean,
    val externalExports: Boolean,
) {
    val unlimitedProfiles: Boolean get() = maxSavedProfiles == Int.MAX_VALUE
}

object EntitlementPolicy {
    const val TRIAL_DAYS = 7L
    const val DAY_MS = 86_400_000L
    const val FREE_MAX_SAVED_PROFILES = 1
    const val FREE_MAX_POSITIONS = 1
    const val PRO_MAX_POSITIONS = 3

    /**
     * A single reading at most this far ahead of the last recorded one counts as real time passing. Beyond
     * it the reading is treated as a broken or manipulated clock: it may still let a *paid* interval expire
     * (anti-cheat), but nothing is granted or stored on its basis.
     *
     * One year, because a legitimate pause between two launches before the user starts a trial or activates
     * a plan is plausible; a decade-long jump never is.
     */
    const val MAX_TRUSTED_CLOCK_JUMP_MS = 365 * DAY_MS

    /** Clock-rollback guard: once the app has seen a later time, expiry checks never move backwards. */
    fun effectiveNow(nowEpochMs: Long, entitlement: Entitlement): Long =
        max(nowEpochMs, entitlement.lastSeenEpochMs)

    /**
     * False when [nowEpochMs] lies absurdly far in the future compared with what this install already saw -
     * i.e. the system clock was changed, or the RTC came back garbage. The first observation has nothing to
     * compare against and counts as plausible (trusting it cannot cost the user anything the device could not
     * measure anyway).
     */
    fun isClockPlausible(entitlement: Entitlement, nowEpochMs: Long): Boolean {
        val seen = entitlement.lastSeenEpochMs
        return seen <= 0L || nowEpochMs <= seen + MAX_TRUSTED_CLOCK_JUMP_MS
    }

    /** Reference time a *grant* may use: an implausible jump is ignored, so it cannot buy extra paid time. */
    private fun trustedNow(entitlement: Entitlement, nowEpochMs: Long): Long =
        if (isClockPlausible(entitlement, nowEpochMs)) effectiveNow(nowEpochMs, entitlement) else entitlement.lastSeenEpochMs

    /** Value to persist as `lastSeen`: an implausible jump is *not* stored, so one bogus reading cannot make
     *  every later expiry check believe in a future far beyond the plan and lock the user out for good. */
    private fun recordedLastSeen(entitlement: Entitlement, nowEpochMs: Long): Long =
        if (isClockPlausible(entitlement, nowEpochMs)) effectiveNow(nowEpochMs, entitlement) else entitlement.lastSeenEpochMs

    fun effectiveTier(entitlement: Entitlement, nowEpochMs: Long = System.currentTimeMillis()): EntitlementTier {
        val expiry = entitlement.expiresAtEpochMs ?: return entitlement.tier
        return if (effectiveNow(nowEpochMs, entitlement) >= expiry) EntitlementTier.FREE else entitlement.tier
    }

    fun isPro(entitlement: Entitlement, nowEpochMs: Long = System.currentTimeMillis()): Boolean =
        effectiveTier(entitlement, nowEpochMs) != EntitlementTier.FREE

    fun limits(entitlement: Entitlement, nowEpochMs: Long = System.currentTimeMillis()): FeatureLimits =
        if (isPro(entitlement, nowEpochMs)) {
            FeatureLimits(
                maxSavedProfiles = Int.MAX_VALUE,
                maxPositions = PRO_MAX_POSITIONS,
                advancedMeasurementSettings = true,
                externalExports = true,
            )
        } else {
            FeatureLimits(
                maxSavedProfiles = FREE_MAX_SAVED_PROFILES,
                maxPositions = FREE_MAX_POSITIONS,
                advancedMeasurementSettings = false,
                externalExports = false,
            )
        }

    /** Records time progression without granting anything new (implausible jumps are ignored, see above). */
    fun touch(entitlement: Entitlement, nowEpochMs: Long = System.currentTimeMillis()): Entitlement {
        val seen = recordedLastSeen(entitlement, nowEpochMs)
        return if (seen == entitlement.lastSeenEpochMs) entitlement else entitlement.copy(lastSeenEpochMs = seen)
    }

    /** One full-feature trial. Called after the first successful measurement (and manually from the paywall). */
    fun startTrial(entitlement: Entitlement, nowEpochMs: Long = System.currentTimeMillis()): Entitlement {
        val now = trustedNow(entitlement, nowEpochMs)
        if (entitlement.trialUsed || effectiveTier(entitlement, now) != EntitlementTier.FREE) {
            return entitlement.copy(lastSeenEpochMs = now)
        }
        return entitlement.copy(
            tier = EntitlementTier.TRIAL,
            plan = null,
            source = EntitlementSource.TRIAL,
            startedAtEpochMs = now,
            expiresAtEpochMs = now + TRIAL_DAYS * DAY_MS,
            lastSeenEpochMs = now,
            trialUsed = true,
            coin = null,
            txidHash = null,
        )
    }

    /**
     * Alpha crypto activation: validates the TXID format, prevents the same TXID extending twice on this
     * install, and stacks a new paid period onto an unexpired one. Payment amount/confirmations are checked
     * manually against the TXID during Alpha; do not present this as payment verification.
     */
    fun activateCryptoAlpha(
        entitlement: Entitlement,
        plan: PremiumPlan,
        coin: CryptoCoin,
        txid: String,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): Entitlement {
        require(isValidTxid(coin, txid)) { "Invalid TXID format for $coin" }
        // A clock jump must not stretch a paid period: the period is counted from the last plausible time.
        val now = trustedNow(entitlement, nowEpochMs)
        val hash = txidHash(txid)
        if (hash == entitlement.txidHash && effectiveTier(entitlement, now) == EntitlementTier.PRO) {
            return entitlement.copy(lastSeenEpochMs = now)
        }
        val currentExpiry = entitlement.expiresAtEpochMs
        val base = if (currentExpiry != null && currentExpiry > now && entitlement.tier == EntitlementTier.PRO) currentExpiry else now
        return entitlement.copy(
            tier = EntitlementTier.PRO,
            plan = plan,
            source = EntitlementSource.CRYPTO_ALPHA,
            startedAtEpochMs = if (entitlement.startedAtEpochMs > 0 && entitlement.tier == EntitlementTier.PRO) entitlement.startedAtEpochMs else now,
            expiresAtEpochMs = plan.durationDays?.let { base + it * DAY_MS },
            lastSeenEpochMs = now,
            trialUsed = true,
            coin = coin,
            txidHash = hash,
        )
    }

    fun remainingMs(entitlement: Entitlement, nowEpochMs: Long = System.currentTimeMillis()): Long? {
        if (!isPro(entitlement, nowEpochMs)) return 0L
        val expiry = entitlement.expiresAtEpochMs ?: return null
        return (expiry - effectiveNow(nowEpochMs, entitlement)).coerceAtLeast(0L)
    }

    fun allowedPositions(requested: Int, limits: FeatureLimits): Int =
        min(requested.coerceAtLeast(1), limits.maxPositions)

    /** Overwriting/re-saving an existing profile is always allowed; only new saved profiles count. */
    fun canSaveProfile(existingNames: Collection<String>, candidateName: String, limits: FeatureLimits): Boolean {
        val candidate = candidateName.trim()
        if (candidate.isEmpty()) return false
        return candidate in existingNames || existingNames.size < limits.maxSavedProfiles
    }

    fun isProfileLimitReached(existingNames: Collection<String>, candidateName: String, limits: FeatureLimits): Boolean {
        val candidate = candidateName.trim()
        return candidate.isNotEmpty() && candidate !in existingNames && existingNames.size >= limits.maxSavedProfiles
    }

    fun isValidTxid(coin: CryptoCoin, rawTxid: String): Boolean {
        val txid = rawTxid.trim()
        return when (coin) {
            CryptoCoin.BTC -> txid.matches(Regex("[0-9a-fA-F]{64}"))
            CryptoCoin.ETH -> txid.removePrefix("0x").removePrefix("0X").matches(Regex("[0-9a-fA-F]{64}"))
            CryptoCoin.SOL -> txid.length in 64..88 && txid.all { it in BASE58_ALPHABET }
        }
    }

    fun txidHash(rawTxid: String): String {
        val normalized = rawTxid.trim().lowercase().removePrefix("0x")
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private const val BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
}
