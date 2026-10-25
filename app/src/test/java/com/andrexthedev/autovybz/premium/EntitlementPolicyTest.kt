package com.andrexthedev.autovybz.premium

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EntitlementPolicyTest {
    private val now = 10_000_000_000L
    private val day = EntitlementPolicy.DAY_MS

    @Test
    fun freeKeepsEveryFeatureFamilyButLimitsPrecisionAndExpertTools() {
        val limits = EntitlementPolicy.limits(Entitlement.free(), now)
        assertEquals(EntitlementTier.FREE, EntitlementPolicy.effectiveTier(Entitlement.free(), now))
        assertEquals(1, limits.maxPositions)
        assertEquals(1, limits.maxSavedProfiles)
        assertFalse(limits.unlimitedProfiles)
        assertFalse(limits.advancedMeasurementSettings)
        assertFalse(limits.externalExports)
    }

    @Test
    fun trialUnlocksForExactlySevenDaysAndCanOnlyStartOnce() {
        val started = EntitlementPolicy.startTrial(Entitlement(lastSeenEpochMs = now), now)
        assertEquals(now, started.startedAtEpochMs)
        assertEquals(now + 7 * day, started.expiresAtEpochMs)
        assertTrue(started.trialUsed)
        assertEquals(EntitlementTier.TRIAL, EntitlementPolicy.effectiveTier(started, now + 7 * day - 1))
        assertEquals(EntitlementTier.FREE, EntitlementPolicy.effectiveTier(started, now + 7 * day))
        val locked = EntitlementPolicy.limits(started, now + 8 * day)
        assertEquals(1, locked.maxPositions)

        val secondAttempt = EntitlementPolicy.startTrial(started, now + 99 * day)
        assertEquals(started.startedAtEpochMs, secondAttempt.startedAtEpochMs)
        assertEquals(started.expiresAtEpochMs, secondAttempt.expiresAtEpochMs)
        assertEquals(EntitlementTier.FREE, EntitlementPolicy.effectiveTier(secondAttempt, now + 99 * day))
    }

    @Test
    fun trialDoesNotOverwritePaidAccess() {
        val paid = Entitlement(
            tier = EntitlementTier.PRO,
            expiresAtEpochMs = now + 30 * day,
            lastSeenEpochMs = now,
        )
        val result = EntitlementPolicy.startTrial(paid, now)
        assertEquals(EntitlementTier.PRO, result.tier)
        assertEquals(paid.expiresAtEpochMs, result.expiresAtEpochMs)
    }

    @Test
    fun alphaCryptoPlansStackAndLifetimeIsPermanent() {
        val monthly = EntitlementPolicy.activateCryptoAlpha(
            entitlement = Entitlement(lastSeenEpochMs = now),
            plan = PremiumPlan.MONTHLY,
            coin = CryptoCoin.BTC,
            txid = "a".repeat(64),
            nowEpochMs = now,
        )
        assertEquals(now + 30 * day, monthly.expiresAtEpochMs)
        assertEquals(EntitlementTier.PRO, EntitlementPolicy.effectiveTier(monthly, now + 29 * day))
        assertTrue(EntitlementPolicy.limits(monthly, now).externalExports)

        val yearly = EntitlementPolicy.activateCryptoAlpha(
            entitlement = monthly,
            plan = PremiumPlan.YEARLY,
            coin = CryptoCoin.ETH,
            txid = "0x" + "b".repeat(64),
            nowEpochMs = now + day,
        )
        assertEquals(now + (30 + 365) * day, yearly.expiresAtEpochMs)

        val lifetime = EntitlementPolicy.activateCryptoAlpha(
            entitlement = yearly,
            plan = PremiumPlan.LIFETIME,
            coin = CryptoCoin.SOL,
            txid = "5".repeat(88),
            nowEpochMs = now + 2 * day,
        )
        assertEquals(PremiumPlan.LIFETIME, lifetime.plan)
        assertNull(lifetime.expiresAtEpochMs)
        assertEquals(EntitlementTier.PRO, EntitlementPolicy.effectiveTier(lifetime, now + 10_000 * day))
    }

    @Test
    fun sameAlphaTransactionDoesNotExtendTwiceWhileProIsUnexpired() {
        val txid = "c".repeat(64)
        val first = EntitlementPolicy.activateCryptoAlpha(
            entitlement = Entitlement(lastSeenEpochMs = now),
            plan = PremiumPlan.MONTHLY,
            coin = CryptoCoin.BTC,
            txid = txid,
            nowEpochMs = now,
        )
        val repeated = EntitlementPolicy.activateCryptoAlpha(
            entitlement = first,
            plan = PremiumPlan.YEARLY,
            coin = CryptoCoin.BTC,
            txid = txid,
            nowEpochMs = now + day,
        )
        assertEquals(first.expiresAtEpochMs, repeated.expiresAtEpochMs)
        assertEquals(first.plan, repeated.plan)

        // After that paid interval has expired the Alpha entry can be inspected/entered again. Production
        // still needs a signed one-time licence; this guard prevents one active entry stacking itself.
        val afterExpiry = EntitlementPolicy.activateCryptoAlpha(
            entitlement = first,
            plan = PremiumPlan.MONTHLY,
            coin = CryptoCoin.BTC,
            txid = txid,
            nowEpochMs = now + 31 * day,
        )
        assertEquals(now + 61 * day, afterExpiry.expiresAtEpochMs)
    }

    @Test
    fun transactionValidationRejectsWrongNetworksAndCharacters() {
        assertTrue(EntitlementPolicy.isValidTxid(CryptoCoin.BTC, " " + "A".repeat(64) + " "))
        assertTrue(EntitlementPolicy.isValidTxid(CryptoCoin.ETH, "0x" + "A".repeat(64)))
        assertTrue(EntitlementPolicy.isValidTxid(CryptoCoin.SOL, "5".repeat(64)))
        assertFalse(EntitlementPolicy.isValidTxid(CryptoCoin.BTC, "z".repeat(64)))
        assertFalse(EntitlementPolicy.isValidTxid(CryptoCoin.SOL, "0".repeat(88)))
        assertFalse(EntitlementPolicy.isValidTxid(CryptoCoin.SOL, "5".repeat(10)))
    }

    @Test
    fun clockRollbackCannotReviveAnExpiredTrial() {
        val seenAfterExpiry = Entitlement(
            tier = EntitlementTier.TRIAL,
            expiresAtEpochMs = now - day,
            lastSeenEpochMs = now,
        )
        assertEquals(EntitlementTier.FREE, EntitlementPolicy.effectiveTier(seenAfterExpiry, now - 7 * day))
        val touched = EntitlementPolicy.touch(seenAfterExpiry, now - 9 * day)
        assertEquals(now, touched.lastSeenEpochMs)
    }

    @Test
    fun oneProfileCanBeOverwrittenButSecondNameIsLockedInFree() {
        val free = EntitlementPolicy.limits(Entitlement.free(), now)
        assertFalse(EntitlementPolicy.isProfileLimitReached(setOf("Living room"), "Living room", free))
        assertTrue(EntitlementPolicy.isProfileLimitReached(setOf("Living room"), "Party box", free))
        assertTrue(EntitlementPolicy.canSaveProfile(setOf("Living room"), "Living room", free))
        assertFalse(EntitlementPolicy.canSaveProfile(setOf("Living room"), "Party box", free))
        assertEquals(1, EntitlementPolicy.allowedPositions(3, free))
    }

    @Test
    fun pricesMatchTheApprovedCryptoOnlyOffer() {
        assertEquals(199, PremiumPlan.MONTHLY.priceUsdCents)
        assertEquals(1_999, PremiumPlan.YEARLY.priceUsdCents)
        assertEquals(9_900, PremiumPlan.LIFETIME.priceUsdCents)
    }
}
