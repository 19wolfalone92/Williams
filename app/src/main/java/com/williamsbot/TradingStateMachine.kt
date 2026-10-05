package com.williamsbot

/**
 * Explicit trading lifecycle finite-state machine.
 *
 * Safety rule: execution is allowed only in READY_FLAT/PROTECTED-derived
 * operational paths and never while reconciliation is required or the kill
 * switch is latched.
 */
enum class TradingState {
    STOPPED,
    INITIALIZING,
    SYNC_REQUIRED,
    READY_FLAT,
    ENTRY_PENDING,
    OPEN_UNPROTECTED,
    PROTECTED,
    EXIT_PENDING,
    RECONCILE_REQUIRED,
    KILL_SWITCH_LATCHED
}

class TradingStateMachine(
    initial: TradingState = TradingState.STOPPED,
    private val onTransition: ((TradingState, TradingState, String) -> Unit)? = null
) {
    @Volatile
    var state: TradingState = initial
        private set

    @Synchronized
    fun transition(next: TradingState, reason: String): Boolean {
        if (next == state) return true
        if (!allowed(state, next)) return false
        val previous = state
        state = next
        onTransition?.invoke(previous, next, reason)
        return true
    }

    @Synchronized
    fun force(next: TradingState, reason: String) {
        val previous = state
        state = next
        onTransition?.invoke(previous, next, reason)
    }

    fun executionAllowed(): Boolean =
        state == TradingState.READY_FLAT ||
            state == TradingState.PROTECTED

    private fun allowed(from: TradingState, to: TradingState): Boolean =
        when (from) {
            TradingState.STOPPED ->
                to in setOf(
                    TradingState.INITIALIZING,
                    TradingState.KILL_SWITCH_LATCHED
                )

            TradingState.INITIALIZING ->
                to in setOf(
                    TradingState.READY_FLAT,
                    TradingState.SYNC_REQUIRED,
                    TradingState.RECONCILE_REQUIRED,
                    TradingState.KILL_SWITCH_LATCHED,
                    TradingState.STOPPED
                )

            TradingState.SYNC_REQUIRED ->
                to in setOf(
                    TradingState.INITIALIZING,
                    TradingState.RECONCILE_REQUIRED,
                    TradingState.KILL_SWITCH_LATCHED,
                    TradingState.STOPPED
                )

            TradingState.READY_FLAT ->
                to in setOf(
                    TradingState.ENTRY_PENDING,
                    TradingState.INITIALIZING,
                    TradingState.RECONCILE_REQUIRED,
                    TradingState.KILL_SWITCH_LATCHED,
                    TradingState.STOPPED
                )

            TradingState.ENTRY_PENDING ->
                to in setOf(
                    TradingState.OPEN_UNPROTECTED,
                    TradingState.READY_FLAT,
                    TradingState.RECONCILE_REQUIRED,
                    TradingState.KILL_SWITCH_LATCHED,
                    TradingState.STOPPED
                )

            TradingState.OPEN_UNPROTECTED ->
                to in setOf(
                    TradingState.PROTECTED,
                    TradingState.EXIT_PENDING,
                    TradingState.RECONCILE_REQUIRED,
                    TradingState.KILL_SWITCH_LATCHED,
                    TradingState.STOPPED
                )

            TradingState.PROTECTED ->
                to in setOf(
                    TradingState.EXIT_PENDING,
                    TradingState.ENTRY_PENDING,
                    TradingState.RECONCILE_REQUIRED,
                    TradingState.KILL_SWITCH_LATCHED,
                    TradingState.STOPPED
                )

            TradingState.EXIT_PENDING ->
                to in setOf(
                    TradingState.READY_FLAT,
                    TradingState.PROTECTED,
                    TradingState.RECONCILE_REQUIRED,
                    TradingState.KILL_SWITCH_LATCHED,
                    TradingState.STOPPED
                )

            TradingState.RECONCILE_REQUIRED ->
                to in setOf(
                    TradingState.SYNC_REQUIRED,
                    TradingState.INITIALIZING,
                    TradingState.KILL_SWITCH_LATCHED,
                    TradingState.STOPPED
                )

            TradingState.KILL_SWITCH_LATCHED ->
                to in setOf(
                    TradingState.RECONCILE_REQUIRED,
                    TradingState.STOPPED
                )
        }
}
