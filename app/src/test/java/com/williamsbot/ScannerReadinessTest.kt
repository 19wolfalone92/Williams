package com.williamsbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class ScannerReadinessTest {
    private fun state(
        running: Boolean = true,
        paused: Boolean = false,
        reconcileRequired: Boolean = false,
        killLatched: Boolean = false,
        historyReady: Boolean = true,
        accountReady: Boolean = true,
        marketDataFresh: Boolean = true
    ) = scannerReadinessState(
        running = running,
        paused = paused,
        reconcileRequired = reconcileRequired,
        killLatched = killLatched,
        historyReady = historyReady,
        accountReady = accountReady,
        marketDataFresh = marketDataFresh
    )

    @Test fun unknown_daily_risk_baseline_retries_while_entries_are_paused_but_never_bypasses_guards() {
        fun eligible(
            running: Boolean = true,
            paused: Boolean = true,
            killLatched: Boolean = false,
            reconcileRequired: Boolean = false,
            accountReady: Boolean = true,
            userStreamConnected: Boolean = true,
            userStreamSyncRequired: Boolean = false,
            baselineReady: Boolean = false
        ) = shouldRetryDailyRiskBaseline(
            running, paused, killLatched, reconcileRequired,
            accountReady, userStreamConnected, userStreamSyncRequired, baselineReady
        )
        // The prior successful API resume could immediately be undone by
        // FUTURES_DAILY_BASELINE_UNKNOWN, with no subsequent background repair.
        assertEquals(true, eligible(paused = true))
        assertEquals(true, eligible(paused = false))
        assertEquals(false, eligible(running = false))
        assertEquals(false, eligible(killLatched = true))
        assertEquals(false, eligible(reconcileRequired = true))
        assertEquals(false, eligible(accountReady = false))
        assertEquals(false, eligible(userStreamConnected = false))
        assertEquals(false, eligible(userStreamSyncRequired = true))
        assertEquals(false, eligible(baselineReady = true))
    }

    @Test fun activeFailedTenPairScannerMustBeReportedAsDiagnosticFailure() {
        assertEquals("FAIL", scannerFailureSeverity(true, true, false, "FAILED"))
        assertEquals("WARN", scannerFailureSeverity(false, true, false, "FAILED"))
        assertEquals("WARN", scannerFailureSeverity(true, false, false, "FAILED"))
        assertEquals("WARN", scannerFailureSeverity(true, true, true, "RUNNING"))
        assertEquals("WARN", scannerFailureSeverity(true, true, false, "READY_BLOCKED"))
    }

    @Test fun jsonFailureReportsAnalysisStageAndKotlinSourceLocation() {
        val origin = org.json.JSONException("End of input at character 0 of")
        val desc = h1ScannerExceptionDetail("ANALYSE_TC2_NTD_COURSE_H1", origin)
        assertEquals(true, desc.contains("ANALYSE_TC2_NTD_COURSE_H1"))
        assertEquals(true, desc.contains("JSONException"))
        assertEquals(true, desc.contains("End of input"))
    }

    @Test fun stopped_runtime_is_reported_before_other_gates() {
        assertEquals("STOPPED", state(running = false, historyReady = false))
    }

    @Test fun pause_reconciliation_and_kill_are_explicit() {
        assertEquals("PAUSED", state(paused = true))
        assertEquals("BLOCKED_RECONCILE", state(reconcileRequired = true))
        assertEquals("BLOCKED_KILL", state(killLatched = true))
    }

    @Test fun missing_history_balance_or_fresh_market_has_distinct_state() {
        assertEquals("WAITING_FOR_HISTORY", state(historyReady = false))
        assertEquals("BLOCKED_ACCOUNT", state(accountReady = false))
        assertEquals("WAITING_FOR_MARKET_DATA", state(marketDataFresh = false))
    }

    @Test fun scanner_is_runnable_only_when_all_required_gates_pass() {
        assertEquals("RUNNABLE", state())
    }

    @Test fun read_only_scanner_continues_when_execution_is_paused_or_reconciling() {
        assertEquals("RUNNABLE", scannerDataReadinessState(true, true, true))
        assertEquals("WAITING_FOR_HISTORY", scannerDataReadinessState(true, false, true))
        // A stale quote blocks new Futures entries, not read-only H1 scanning.
        assertEquals("RUNNABLE", scannerDataReadinessState(true, true, false))
        assertEquals("STOPPED", scannerDataReadinessState(false, true, false))
        assertEquals("WAITING_FOR_HISTORY",scannerDataReadinessState(true,false,false))
    }

    @Test fun completed_scan_never_hides_execution_blockers() {
        assertEquals("BLOCKED_KILL", scannerExecutionBlocker(
            paused = true, reconcileRequired = true, killLatched = true,
            accountReady = true, marketDataFresh = true, coordinatorState = "POSITION_UNPROTECTED"
        ))
        assertEquals("BLOCKED_FUTURES_RECONCILIATION", scannerExecutionBlocker(
            paused = false, reconcileRequired = false, killLatched = false,
            accountReady = true, marketDataFresh = true, coordinatorState = "POSITION_UNPROTECTED"
        ))
        assertEquals("PAUSED", scannerExecutionBlocker(
            paused = true, reconcileRequired = false, killLatched = false,
            accountReady = true, marketDataFresh = true, coordinatorState = "READY"
        ))
        assertEquals(null, scannerExecutionBlocker(
            paused = false, reconcileRequired = false, killLatched = false,
            accountReady = true, marketDataFresh = true, coordinatorState = "OPEN_PROTECTED"
        ))
    }

    @Test fun reconciledPendingOrderDoesNotHideIndependentPairAnalysisOrTrading() {
        assertEquals(null,scannerExecutionBlocker(
            paused=false,reconcileRequired=false,killLatched=false,
            accountReady=true,marketDataFresh=true,coordinatorState="ENTRY_PENDING",
            coordinatorReadyForEntry=true
        ))
        assertEquals("BLOCKED_FUTURES_RECONCILIATION",scannerExecutionBlocker(
            paused=false,reconcileRequired=false,killLatched=false,
            accountReady=true,marketDataFresh=true,coordinatorState="ENTRY_PENDING",
            coordinatorReadyForEntry=false
        ))
        assertEquals("BLOCKED_FUTURES_RECONCILIATION",scannerExecutionBlocker(
            paused=false,reconcileRequired=true,killLatched=false,
            accountReady=true,marketDataFresh=true,coordinatorState="ENTRY_PENDING",
            coordinatorReadyForEntry=true
        ))
        assertEquals("WAITING_FOR_MARKET_DATA",scannerExecutionBlocker(
            paused=false,reconcileRequired=false,killLatched=false,
            accountReady=true,marketDataFresh=false,coordinatorState="ENTRY_PENDING",
            coordinatorReadyForEntry=true
        ))
    }

    @Test fun h1RestScanIsThrottledButRuntimeOneSecondRecoveryIsUnaffected() {
        assertEquals(true,shouldScheduleReadOnlyScan(150_000L,0L))
        assertEquals(false,shouldScheduleReadOnlyScan(150_999L,150_000L))
        assertEquals(false,shouldScheduleReadOnlyScan(179_999L,150_000L))
        assertEquals(true,shouldScheduleReadOnlyScan(180_000L,150_000L))
        assertEquals(false,shouldScheduleReadOnlyScan(149_999L,150_000L))
    }

    @Test fun empty_demo_wallet_does_not_suppress_read_only_scanning() {
        assertEquals(false, dailyRiskBlocksScanner(
            accountFunded = false,
            dailyRiskAllowed = false,
            paused = false,
            killLatched = false
        ))
    }

    @Test fun known_daily_loss_breach_blocks_scan_and_preserves_kill_priority() {
        assertEquals(true, dailyRiskBlocksScanner(
            accountFunded = true,
            dailyRiskAllowed = false,
            paused = true,
            killLatched = false
        ))
        assertEquals(true, dailyRiskBlocksScanner(
            accountFunded = true,
            dailyRiskAllowed = false,
            paused = true,
            killLatched = true
        ))
    }

    @Test fun account_refresh_unknown_does_not_claim_a_confirmed_risk_breach() {
        assertEquals(false, dailyRiskBlocksScanner(
            accountFunded = true,
            dailyRiskAllowed = false,
            paused = false,
            killLatched = false
        ))
    }

    @Test fun start_remains_available_for_safe_reconciliation_even_when_kill_is_latched() {
        assertEquals(true, runtimeStartButtonEnabled(true, false, false))
        assertEquals(false, runtimeStartButtonEnabled(false, false, false))
        assertEquals(false, runtimeStartButtonEnabled(true, true, false))
        assertEquals(true, runtimeStartButtonEnabled(true, false, true))
    }


    @Test fun recovery_contract_accepts_an_intentional_stop_without_auto_run() {
        assertEquals(true, runtimeRecoveryContract(
            autoRunRequested = false,
            running = false,
            workerAlive = false,
            killLatched = false
        ))
    }

    @Test fun recovery_contract_rejects_a_dead_worker_when_auto_run_is_requested() {
        assertEquals(false, runtimeRecoveryContract(
            autoRunRequested = true,
            running = false,
            workerAlive = false,
            killLatched = false
        ))
    }

    @Test fun recovery_contract_rejects_runtime_worker_state_mismatch() {
        assertEquals(false, runtimeRecoveryContract(
            autoRunRequested = false,
            running = true,
            workerAlive = false,
            killLatched = false
        ))
    }

    @Test fun recovery_contract_allows_safe_monitoring_with_kill_latched_but_never_auto_entries() {
        assertEquals(true, runtimeRecoveryContract(
            autoRunRequested = true,
            running = true,
            workerAlive = true,
            killLatched = true
        ))
        assertEquals(false, runtimeRecoveryContract(
            autoRunRequested = true,
            running = false,
            workerAlive = false,
            killLatched = true
        ))
    }

    @Test fun runtime_diagnostic_does_not_pass_when_runtime_and_worker_are_both_stopped() {
        assertEquals("WARN", runtimeLifecycleSeverity(
            autoRunRequested = false, running = false, workerAlive = false
        ))
        assertEquals("FAIL", runtimeLifecycleSeverity(
            autoRunRequested = true, running = false, workerAlive = false
        ))
        assertEquals("FAIL", runtimeLifecycleSeverity(
            autoRunRequested = true, running = true, workerAlive = false
        ))
        assertEquals("PASS", runtimeLifecycleSeverity(
            autoRunRequested = true, running = true, workerAlive = true
        ))
    }

    @Test fun history_diagnostic_does_not_pass_for_idle_empty_history() {
        assertEquals("WARN", historyLifecycleSeverity(
            autoRunRequested = false, running = false, historyReady = false,
            warmupRunning = false, activeTasks = 0
        ))
        assertEquals("FAIL", historyLifecycleSeverity(
            autoRunRequested = true, running = true, historyReady = false,
            warmupRunning = false, activeTasks = 0
        ))
        assertEquals("PASS", historyLifecycleSeverity(
            autoRunRequested = true, running = true, historyReady = false,
            warmupRunning = true, activeTasks = 4
        ))
        assertEquals("PASS", historyLifecycleSeverity(
            autoRunRequested = true, running = true, historyReady = true,
            warmupRunning = false, activeTasks = 0
        ))
    }


    @Test fun dashboard_uses_live_quote_then_fresh_rest_then_last_closed_candle() {
        assertEquals(101.0, dashboardPrice(101.0, 100.0, 99.0, true, true)!!, 0.0)
        assertEquals(100.0, dashboardPrice(101.0, 100.0, 99.0, false, true)!!, 0.0)
        assertEquals(99.0, dashboardPrice(101.0, 100.0, 99.0, false, false)!!, 0.0)
    }

    @Test fun dashboard_rejects_non_finite_or_non_positive_prices() {
        assertEquals(99.0, dashboardPrice(Double.NaN, Double.NEGATIVE_INFINITY, 99.0, true, true)!!, 0.0)
        assertEquals(null, dashboardPrice(0.0, -1.0, Double.NaN, true, true))
    }

    @Test fun private_stream_reconciliation_retries_only_when_connected_and_throttled() {
        assertEquals(true, shouldRetryUserStreamReconciliation(true, true, false, 0L, 10_000L))
        assertEquals(false, shouldRetryUserStreamReconciliation(false, true, false, 0L, 10_000L))
        assertEquals(false, shouldRetryUserStreamReconciliation(true, false, false, 0L, 10_000L))
        assertEquals(false, shouldRetryUserStreamReconciliation(true, true, true, 0L, 10_000L))
        assertEquals(false, shouldRetryUserStreamReconciliation(true, true, false, 5_000L, 14_999L))
        assertEquals(true, shouldRetryUserStreamReconciliation(true, true, false, 5_000L, 15_000L))
        assertEquals(false, shouldRetryUserStreamReconciliation(true, true, false, 15_000L, 14_000L))
    }

    @Test fun websocket_reconnects_when_silent_or_handshake_stalls() {
        assertEquals(true, streamNeedsReconnect(true, 10_000L, 1_000L, 55_001L, 45_000L))
        assertEquals(true, streamNeedsReconnect(true, 0L, 10_000L, 55_001L, 45_000L))
        assertEquals(false, streamNeedsReconnect(true, 40_000L, 10_000L, 55_000L, 45_000L))
        assertEquals(false, streamNeedsReconnect(false, 1_000L, 1_000L, 100_000L, 45_000L))
    }

    @Test fun signedAccountMonitorIsNeverInitiatedInsideH1Scan() {
        val now = 1_800_000_000_000L
        assertFalse(shouldScheduleIndependentFuturesMonitor(now, now - 60_000, now - 60_000,
            "READY", scanning = true, reconciliationActive = false))
        assertFalse(shouldScheduleIndependentFuturesMonitor(now, now - 60_000, now - 60_000,
            "READY", scanning = false, reconciliationActive = true))
        assertTrue(shouldScheduleIndependentFuturesMonitor(now, now - 46_000, now - 46_000,
            "READY", scanning = false, reconciliationActive = false))
        assertFalse(shouldScheduleIndependentFuturesMonitor(now, now - 20_000, now - 20_000,
            "READY", scanning = false, reconciliationActive = false))
    }

    @Test fun protectedPositionAndPendingEntryStillReceiveFastIndependentMonitoring() {
        val now = 1_800_000_000_000L
        assertTrue(shouldScheduleIndependentFuturesMonitor(now, now - 4_000, now - 4_000,
            "OPEN_PROTECTED", scanning = false, reconciliationActive = false))
        assertTrue(shouldScheduleIndependentFuturesMonitor(now, now - 3_000, now - 3_000,
            "ENTRY_PENDING", scanning = false, reconciliationActive = false))
        assertTrue(shouldScheduleIndependentFuturesMonitor(now, now - 3_000, now - 3_000,
            "EXIT_PENDING", scanning = false, reconciliationActive = false))
        assertFalse(shouldScheduleIndependentFuturesMonitor(now, now - 1_000, now - 1_000,
            "OPEN_PROTECTED", scanning = false, reconciliationActive = false))
        // Previous exchange lock timeout must be retried and verified promptly;
        // do not wait 45 seconds merely because the coordinator is now flat.
        assertTrue(shouldScheduleIndependentFuturesMonitor(now, now - 4_000, now - 4_000,
            "READY", scanning = false, reconciliationActive = false,
            safetyLatchUnresolved = true))
    }

}
