package com.williamsbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StandaloneRuntimeReconciliationTest {
    @Test
    fun queuedUserStreamReconciliationCountsAsLiveWork() {
        val findings = diagnosticConsistencyFindings(
            coordinatorState = "RECONCILE_REQUIRED",
            coordinatorReady = false,
            coordinatorBlocker = "exchange_reconciliation_in_progress",
            runtimeState = "RECONCILE_REQUIRED",
            runtimeReconcileRequired = true,
            executionEnabled = false,
            dailyRiskBaselineSource = "EXCHANGE_RECONCILED",
            reconciliationRunning = true
        )
        assertFalse(findings.any { it.startsWith("RECONCILIATION_STALE_BLOCKER:") })
    }

    @Test
    fun inProgressCoordinatorWithNoRunningTaskIsFlagged() {
        val findings = diagnosticConsistencyFindings(
            coordinatorState = "RECONCILE_REQUIRED",
            coordinatorReady = false,
            coordinatorBlocker = "exchange_reconciliation_in_progress",
            runtimeState = "RECONCILE_REQUIRED",
            runtimeReconcileRequired = true,
            executionEnabled = false,
            dailyRiskBaselineSource = "EXCHANGE_RECONCILED",
            reconciliationRunning = false
        )
        assertTrue(findings.any { it.startsWith("RECONCILIATION_STALE_BLOCKER:") })
    }

    @Test
    fun onlyCurrentConnectedSessionCanClearSyncLatch() {
        assertTrue(mayCommitUserStreamSync(true, false, 7L, 7L))
        assertFalse(mayCommitUserStreamSync(false, false, 7L, 7L))
        assertFalse(mayCommitUserStreamSync(true, true, 7L, 7L))
        assertFalse(mayCommitUserStreamSync(true, false, 6L, 7L))
    }

    @Test
    fun reconciliationLifecycleHasAnExplicitTimeout() {
        assertTrue(reconciliationTaskLifecycleHealthy(false, 0L, 500_000L))
        assertTrue(reconciliationTaskLifecycleHealthy(true, 100_000L, 400_000L))
        assertFalse(reconciliationTaskLifecycleHealthy(true, 100_000L, 400_001L))
        assertFalse(reconciliationTaskLifecycleHealthy(true, 0L, 400_000L))
        assertFalse(reconciliationTaskLifecycleHealthy(true, 500_000L, 400_000L))
    }
}
