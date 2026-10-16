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
    fun userStreamHandshakeWithoutEventIsNotFresh() {
        assertFalse(userStreamIsFresh(
            connected = true,
            syncRequired = false,
            lastEventMs = 0L,
            nowMs = 1_000_000L
        ))
    }

    @Test
    fun userStreamRejectsStaleFutureAndUnsynchronizedEvents() {
        assertFalse(userStreamIsFresh(true, false, 100_000L, 220_001L))
        assertFalse(userStreamIsFresh(true, false, 300_000L, 220_000L))
        assertFalse(userStreamIsFresh(true, true, 219_000L, 220_000L))
        assertTrue(userStreamIsFresh(true, false, 219_000L, 220_000L))
    }
}
