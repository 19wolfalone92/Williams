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
}
