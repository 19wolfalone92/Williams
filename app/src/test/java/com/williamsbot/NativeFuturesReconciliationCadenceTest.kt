package com.williamsbot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeFuturesReconciliationCadenceTest {
    @Test fun slowProtectedSweepDefersFromSuccessfulCompletion() {
        val start = 100_000L
        val completed = 112_000L // 12s REST sweep, longer than the 3s poll interval
        assertTrue(shouldDeferNativeFuturesPeriodicReconciliation(
            NativeFuturesCampaignCoordinator.State.OPEN_PROTECTED, start, completed, 113_500L, 3_000L))
        assertTrue(shouldDeferNativeFuturesPeriodicReconciliation(
            NativeFuturesCampaignCoordinator.State.READY, start, completed, 113_500L, 3_000L))
        assertFalse(shouldDeferNativeFuturesPeriodicReconciliation(
            NativeFuturesCampaignCoordinator.State.OPEN_PROTECTED, start, completed, 115_000L, 3_000L))
    }

    @Test fun pendingEntryAndExitKeepMoreUrgentSnapshotStartCadence() {
        for (state in listOf(
            NativeFuturesCampaignCoordinator.State.ENTRY_PENDING,
            NativeFuturesCampaignCoordinator.State.EXIT_PENDING
        )) {
            assertFalse(shouldDeferNativeFuturesPeriodicReconciliation(
                state, 100_000L, 112_000L, 113_000L, 3_000L))
            assertTrue(shouldDeferNativeFuturesPeriodicReconciliation(
                state, 100_000L, 112_000L, 101_000L, 3_000L))
        }
    }

    @Test fun oldFutureMissingSignedSnapshotAndUnsafeStateCannotDefer() {
        val protected = NativeFuturesCampaignCoordinator.State.OPEN_PROTECTED
        assertFalse(shouldDeferNativeFuturesPeriodicReconciliation(
            protected, 100_000L, 191_000L, 192_000L, 3_000L))
        assertFalse(shouldDeferNativeFuturesPeriodicReconciliation(
            protected, 0L, 112_000L, 113_000L, 3_000L))
        assertFalse(shouldDeferNativeFuturesPeriodicReconciliation(
            protected, 115_000L, 112_000L, 113_000L, 3_000L))
        assertFalse(shouldDeferNativeFuturesPeriodicReconciliation(
            protected, 100_000L, 115_000L, 113_000L, 3_000L))
        assertFalse(shouldDeferNativeFuturesPeriodicReconciliation(
            protected, 100_000L, 112_000L, 113_000L, 0L))
        assertFalse(shouldDeferNativeFuturesPeriodicReconciliation(
            NativeFuturesCampaignCoordinator.State.POSITION_UNPROTECTED,
            100_000L, 112_000L, 113_000L, 0L))
    }
}
