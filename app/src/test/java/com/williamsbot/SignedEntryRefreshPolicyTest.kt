package com.williamsbot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignedEntryRefreshPolicyTest {
    private val now=1_794_327_002_800L

    @Test fun availableSignedProofGetsFreshnessHeadroomBeforeExpensiveH1FinalVerifier() {
        assertFalse(signedFuturesEntryRefreshNeeded(now, now))
        assertFalse(signedFuturesEntryRefreshNeeded(now-44_999L,now))
        assertTrue(signedFuturesEntryRefreshNeeded(now-45_000L,now))
        assertTrue(signedFuturesEntryRefreshNeeded(now-89_999L,now))
        assertTrue(signedFuturesEntryRefreshNeeded(now-90_001L,now))
    }

    @Test fun missingOrFutureClockProofRequestsFreshSignedExchangeEvidence() {
        assertTrue(signedFuturesEntryRefreshNeeded(0L,now))
        assertTrue(signedFuturesEntryRefreshNeeded(-1L,now))
        assertTrue(signedFuturesEntryRefreshNeeded(now+1L,now))
        assertTrue(signedFuturesEntryRefreshNeeded(now,0L))
    }

    @Test fun preflightRefreshDoesNotIncreaseTheNinetySecondTradingTimeToLive() {
        assertTrue(signedFuturesReconciliationFresh(now-90_000L,now))
        assertFalse(signedFuturesReconciliationFresh(now-90_001L,now))
        assertFalse(signedFuturesReconciliationFresh(now+1L,now))
        // A preflight must have *at least* 45 seconds remaining after a
        // success, or the candidate stays blocked and reverts to discovery.
        assertTrue(signedFuturesReconciliationFresh(now-45_000L,now,45_000L))
        assertFalse(signedFuturesReconciliationFresh(now-45_001L,now,45_000L))
        assertTrue(signedFuturesEntryRefreshNeeded(now-45_000L,now))
    }

    @Test fun zeroHeadroomIsNeverAnExecutionAuthorization() {
        assertFalse(signedFuturesEntryRefreshNeeded(now-89_999L,now,0L))
        assertTrue(signedFuturesEntryRefreshNeeded(now-90_000L,now,0L))
        assertTrue(signedFuturesEntryRefreshNeeded(now-90_000L,now,100_000L))
    }
}
