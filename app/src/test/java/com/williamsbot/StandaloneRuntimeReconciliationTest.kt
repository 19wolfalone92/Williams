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
    fun reconciliationLifecycleHasAnExplicitTimeout() {
        assertTrue(reconciliationTaskLifecycleHealthy(false, 0L, 200_000L))
        assertTrue(reconciliationTaskLifecycleHealthy(true, 100_000L, 220_000L))
        assertFalse(reconciliationTaskLifecycleHealthy(true, 100_000L, 220_001L))
        assertFalse(reconciliationTaskLifecycleHealthy(true, 0L, 220_000L))
        assertFalse(reconciliationTaskLifecycleHealthy(true, 300_000L, 220_000L))
    }
}
