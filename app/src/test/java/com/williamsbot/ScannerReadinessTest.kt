package com.williamsbot

import org.junit.Assert.assertEquals
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
        assertEquals("WAITING_FOR_MARKET_DATA", scannerDataReadinessState(true, true, false))
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

    @Test fun start_remains_available_to_run_safe_exchange_reconciliation() {
        assertEquals(true, runtimeStartButtonEnabled(true, false, false))
        assertEquals(false, runtimeStartButtonEnabled(false, false, false))
        assertEquals(false, runtimeStartButtonEnabled(true, true, false))
        assertEquals(false, runtimeStartButtonEnabled(true, false, true))
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

    @Test fun recovery_contract_never_accepts_auto_run_with_kill_latched() {
        assertEquals(false, runtimeRecoveryContract(
            autoRunRequested = true,
            running = true,
            workerAlive = true,
            killLatched = true
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

    @Test fun websocket_reconnects_when_silent_or_handshake_stalls() {
        assertEquals(true, streamNeedsReconnect(true, 10_000L, 1_000L, 55_001L, 45_000L))
        assertEquals(true, streamNeedsReconnect(true, 0L, 10_000L, 55_001L, 45_000L))
        assertEquals(false, streamNeedsReconnect(true, 40_000L, 10_000L, 55_000L, 45_000L))
        assertEquals(false, streamNeedsReconnect(false, 1_000L, 1_000L, 100_000L, 45_000L))
    }
}
