package com.williamsbot

import org.junit.Assert.assertEquals
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

        assertEquals(3, findings.size)
        assertTrue(findings.any { it.startsWith("STATE_MISMATCH:") })
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
    fun acceptsConsistentBlockedRuntimeWithoutFalsePositive() {
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

}
