package com.williamsbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

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
    fun activeReconciliationDoesNotMisclassifyThePriorFsmAndLatchAsAStuckTask() {
        val findings = diagnosticConsistencyFindings(
            "RECONCILE_REQUIRED", false, "exchange_reconciliation_in_progress", "ENTRY_PENDING",
            false, false, "EXCHANGE_RECONCILED", true
        )
        assertTrue(findings.isEmpty())
        val finishedButStillBlocked = diagnosticConsistencyFindings(
            "RECONCILE_REQUIRED", false, "exchange_reconciliation_in_progress", "ENTRY_PENDING",
            false, false, "EXCHANGE_RECONCILED", false
        )
        assertTrue(finishedButStillBlocked.any { it.startsWith("RECONCILIATION_STALE_BLOCKER:") })
    }

    @Test
    fun healthAndExecutionContractUseOneAuthoritativeStateMapping() {
        assertEquals("RECONCILE_REQUIRED", authoritativeRuntimeDisplayState(true, true, false, false, "RECONCILE_REQUIRED", "ENTRY_PENDING"))
        assertEquals("ENTRY_PENDING", authoritativeRuntimeDisplayState(true, true, false, false, "ENTRY_PENDING", "READY_FLAT"))
        assertEquals("OPEN", authoritativeRuntimeDisplayState(true, true, false, false, "OPEN_PROTECTED", "ENTRY_PENDING"))
        assertEquals("STOPPED", authoritativeRuntimeDisplayState(true, false, false, false, "READY", "STOPPED"))
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
    fun completeSignedDataCanSynchronizeWhileNewEntriesStayBlocked() {
        val blocked=JSONObject().put("ok",false).put("state","POSITION_UNPROTECTED")
            .put("exchange_snapshot_complete",true).put("positions_checked",3)
            .put("exchange_protection_observations",JSONArray())
        assertTrue(futuresSignedSnapshotComplete(blocked))
        assertFalse(blocked.getBoolean("ok"))
        assertEquals("POSITION_UNPROTECTED",blocked.getString("state"))
        blocked.remove("exchange_protection_observations")
        assertFalse(futuresSignedSnapshotComplete(blocked))
        assertFalse(futuresSignedSnapshotComplete(JSONObject().put("ok",true)))
        assertFalse(futuresSignedSnapshotComplete(JSONObject()
            .put("ok",false).put("blocker","signed REST timeout")))
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
