package com.williamsbot

import org.junit.Assert.*
import org.junit.Test

class FuturesExposureTransportPolicyTest {
    @Test fun connectionOrWorkerLossRevokesEntryEvenWithRecentSignedSnapshot() {
        val now = 1_800_000_100_000L
        assertNull(futuresExposureTransportBlocker(true, false, true, false, now - 1L, now))
        assertNotNull(futuresExposureTransportBlocker(false, false, true, false, now - 1L, now))
        assertNotNull(futuresExposureTransportBlocker(true, true, true, false, now - 1L, now))
        assertNotNull(futuresExposureTransportBlocker(true, false, false, false, now - 1L, now))
        assertNotNull(futuresExposureTransportBlocker(true, false, true, true, now - 1L, now))
    }

    @Test fun proactiveCandidateSignedRefreshHasBudgetButNeverExtendsNinetySecondGate() {
        val now=1_800_000_000_000L
        assertTrue(entrySignedProofNeedsRefresh(0L,now))
        assertTrue(entrySignedProofNeedsRefresh(now+1L,now))
        assertFalse(entrySignedProofNeedsRefresh(now-44_999L,now))
        assertTrue(entrySignedProofNeedsRefresh(now-45_000L,now))
        assertTrue(entrySignedProofNeedsRefresh(now-89_999L,now))
        assertTrue(entrySignedProofNeedsRefresh(now-90_001L,now))
        // A refresh REQUEST is not signed proof. No local timestamp update
        // can bypass the original 90s financial transport fence.
        assertEquals("SIGNED_RECONCILIATION_STALE_BEFORE_ENTRY",
            futuresExposureTransportBlocker(true,false,true,false,now-90_001L,now))
        assertNull(futuresExposureTransportBlocker(
            true,false,true,false,now-89_999L,now))
        assertTrue(deferrableFuturesReconciliation("WILLIAMS_PRE_ENTRY_SIGNED_REFRESH"))
        assertEquals(0L,futuresReconciliationLockBudgetMs(
            "WILLIAMS_PRE_ENTRY_SIGNED_REFRESH",50_000L))
        assertFalse(deferrableFuturesReconciliation("STARTUP_RECONCILIATION"))
    }

    @Test fun completingASlowAuditCannotRenewTheAgeOfItsOldAccountSnapshot() {
        val started = 1_800_000_000_000L
        val completed = started + 95_000L
        assertNotNull(futuresExposureTransportBlocker(true, false, true, false, started, completed))
        assertNotNull(futuresExposureTransportBlocker(true, false, true, false, 0L, completed))
        assertNotNull(futuresExposureTransportBlocker(true, false, true, false, completed + 1L, completed))
        assertNull(futuresExposureTransportBlocker(true, false, true, false, started, started + 90_000L))
    }
}
