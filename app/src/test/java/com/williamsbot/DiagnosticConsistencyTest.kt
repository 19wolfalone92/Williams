package com.williamsbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticConsistencyTest {
    @Test
    fun detectsCoordinatorRuntimeStateMismatchAndStaleReconcileBlocker() {
        val findings = diagnosticConsistencyFindings(
            coordinatorState = "RECONCILE_REQUIRED",
            coordinatorReady = false,
            coordinatorBlocker = "exchange_reconciliation_in_progress",
            runtimeState = "READY_FLAT",
            runtimeReconcileRequired = false,
            executionEnabled = false,
            dailyRiskBaselineSource = "UNKNOWN",
            reconciliationRunning = false
        )

        assertEquals(4, findings.size)
        assertTrue(findings.any { it.startsWith("STATE_MISMATCH:") })
        assertTrue(findings.any { it.startsWith("RECONCILE_LATCH_MISMATCH:") })
        assertTrue(findings.any { it.startsWith("RECONCILIATION_STALE_BLOCKER:") })
        assertTrue(findings.any { it.startsWith("RISK_BASELINE_UNKNOWN:") })
    }

    @Test
    fun flagsUnsafeExecutionClaimsWhenCoordinatorOrRiskBaselineIsUnknown() {
        val findings = diagnosticConsistencyFindings(
            coordinatorState = "RECONCILE_REQUIRED",
            coordinatorReady = false,
            coordinatorBlocker = "position_snapshot_unknown",
            runtimeState = "READY_FLAT",
            runtimeReconcileRequired = true,
            executionEnabled = true,
            dailyRiskBaselineSource = "UNKNOWN",
            reconciliationRunning = false
        )

        assertTrue(findings.any { it.startsWith("STATE_MISMATCH:") })
        assertTrue(findings.any { it.startsWith("GATE_CONTRADICTION:") })
        assertTrue(findings.any { it.startsWith("SAFETY_CONTRADICTION:") })
        assertTrue(findings.any { it.startsWith("RISK_BASELINE_UNKNOWN:") })
    }

    @Test
    fun reportsUnknownRiskBaselineEvenWhenRuntimeIsConsistentAndBlocked() {
        val findings = diagnosticConsistencyFindings(
            coordinatorState = "READY_FLAT",
            coordinatorReady = true,
            coordinatorBlocker = "",
            runtimeState = "READY_FLAT",
            runtimeReconcileRequired = false,
            executionEnabled = false,
            dailyRiskBaselineSource = "UNKNOWN",
            reconciliationRunning = false
        )

        assertEquals(1, findings.size)
        assertTrue(findings.single().startsWith("RISK_BASELINE_UNKNOWN:"))
    }
    @Test
    fun detectsTopLevelAndExecutionContractStateConflict() {
        val findings = diagnosticConsistencyFindings(
            coordinatorState = "RECONCILE_REQUIRED",
            coordinatorReady = false,
            coordinatorBlocker = "exchange_reconciliation_in_progress",
            runtimeState = "READY_FLAT",
            runtimeReconcileRequired = false,
            executionEnabled = false,
            dailyRiskBaselineSource = "KNOWN",
            reconciliationRunning = false,
            topLevelRuntimeState = "RECONCILE_REQUIRED"
        )

        assertTrue(findings.any { it.startsWith("RUNTIME_STATE_MISMATCH:") })
        assertTrue(findings.any { it.startsWith("STATE_MISMATCH:") })
    }


    @Test
    fun idleUserStreamIsFreshAfterSuccessfulRestReconciliation() {
        assertTrue(userStreamSyncIsFresh(
            connected = true,
            syncRequired = false,
            lastSuccessfulSyncMs = 900_000L,
            nowMs = 1_000_000L
        ))
    }

    @Test
    fun userStreamSyncRequiresCurrentSessionReconciliationButAllowsIdleStreams() {
        assertFalse(userStreamSyncIsFresh(true, false, 0L, 1_000_000L))
        assertTrue(userStreamSyncIsFresh(true, false, 800_000L, 1_000_001L))
        assertFalse(userStreamSyncIsFresh(true, false, 1_000_001L, 1_000_000L))
        assertFalse(userStreamSyncIsFresh(true, true, 999_000L, 1_000_000L))
        assertFalse(userStreamSyncIsFresh(false, false, 999_000L, 1_000_000L))
    }


    @Test
    fun activeAccountReconciliationDoesNotInventAStaleRuntimeMismatch() {
        val findings = diagnosticConsistencyFindings(
            coordinatorState = "RECONCILE_REQUIRED",
            coordinatorReady = false,
            coordinatorBlocker = "exchange_reconciliation_in_progress",
            runtimeState = "ENTRY_PENDING",
            runtimeReconcileRequired = false,
            executionEnabled = false,
            dailyRiskBaselineSource = "INITIAL_FLAT_SNAPSHOT",
            reconciliationRunning = true,
            topLevelRuntimeState = "RECONCILE_REQUIRED"
        )
        assertTrue(findings.isEmpty())
    }

    @Test
    fun transientReconciliationStillFailsClosedIfExecutionIsEnabled() {
        val findings = diagnosticConsistencyFindings(
            coordinatorState = "RECONCILE_REQUIRED",
            coordinatorReady = false,
            coordinatorBlocker = "exchange_reconciliation_in_progress",
            runtimeState = "ENTRY_PENDING",
            runtimeReconcileRequired = false,
            executionEnabled = true,
            dailyRiskBaselineSource = "INITIAL_FLAT_SNAPSHOT",
            reconciliationRunning = true,
            topLevelRuntimeState = "RECONCILE_REQUIRED"
        )
        assertTrue(findings.any { it.startsWith("GATE_CONTRADICTION:") })
    }

    @Test
    fun completedOrphanedReconciliationStillFailsDiagnostics() {
        val findings = diagnosticConsistencyFindings(
            coordinatorState = "RECONCILE_REQUIRED",
            coordinatorReady = false,
            coordinatorBlocker = "exchange_reconciliation_in_progress",
            runtimeState = "ENTRY_PENDING",
            runtimeReconcileRequired = false,
            executionEnabled = false,
            dailyRiskBaselineSource = "INITIAL_FLAT_SNAPSHOT",
            reconciliationRunning = false,
            topLevelRuntimeState = "RECONCILE_REQUIRED"
        )
        assertEquals(4, findings.size)
        assertTrue(findings.any { it.startsWith("RUNTIME_STATE_MISMATCH:") })
        assertTrue(findings.any { it.startsWith("STATE_MISMATCH:") })
        assertTrue(findings.any { it.startsWith("RECONCILE_LATCH_MISMATCH:") })
        assertTrue(findings.any { it.startsWith("RECONCILIATION_STALE_BLOCKER:") })
    }

    @Test
    fun nontransientCoordinatorFailureIsNotMaskedByAnotherReconciliation() {
        val findings = diagnosticConsistencyFindings(
            coordinatorState = "RECONCILE_REQUIRED",
            coordinatorReady = false,
            coordinatorBlocker = "position_snapshot_unknown",
            runtimeState = "ENTRY_PENDING",
            runtimeReconcileRequired = false,
            executionEnabled = false,
            dailyRiskBaselineSource = "INITIAL_FLAT_SNAPSHOT",
            reconciliationRunning = true,
            topLevelRuntimeState = "RECONCILE_REQUIRED"
        )
        assertTrue(findings.any { it.startsWith("STATE_MISMATCH:") })
        assertTrue(findings.any { it.startsWith("RECONCILE_LATCH_MISMATCH:") })
    }
}
