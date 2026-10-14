package com.williamsbot

internal const val FUTURES_EXECUTION_DOOR_KEY = "__FUTURES_ACCOUNT__"

/** Pure policy contract used by the runtime gate and deterministic unit tests. */
internal fun executionDoorBlocker(
    running: Boolean,
    paused: Boolean,
    reconcileRequired: Boolean,
    killLatched: Boolean,
    allowPaused: Boolean = false,
    allowStopped: Boolean = false,
    allowKillLatched: Boolean = false,
    allowReconcileRequired: Boolean = false
): String? = when {
    !running && !allowStopped -> "Runtime is not running"
    paused && !allowPaused -> "Runtime is paused"
    reconcileRequired && !allowReconcileRequired -> "RECONCILE_REQUIRED"
    killLatched && !allowKillLatched -> "KILL_SWITCH_LATCHED"
    else -> null
}

/**
 * Atomic admission gate between scanner proposals and Binance execution.
 *
 * The gate is deliberately small: it owns only the reservation invariant.
 * Network I/O must never run while holding the gate.
 */
class ExecutionGate {
    private val reservedSymbols = LinkedHashSet<String>()

    @Synchronized
    fun tryReserve(symbol: String): Boolean {
        val normalized = symbol.uppercase()
        if (normalized.isBlank()) return false
        return reservedSymbols.add(normalized)
    }

    @Synchronized
    fun release(symbol: String) {
        reservedSymbols.remove(symbol.uppercase())
    }

    @Synchronized
    fun isReserved(symbol: String): Boolean =
        reservedSymbols.contains(symbol.uppercase())

    @Synchronized
    fun reservedCount(): Int = reservedSymbols.size
}
