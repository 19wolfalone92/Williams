package com.williamsbot

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
        if (normalized.isBlank() || reservedSymbols.isNotEmpty()) return false
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
