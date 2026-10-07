package com.williamsbot

/**
 * Atomic admission gate between scanner proposals and Binance execution.
 *
 * The gate owns both the open/closed lifecycle and per-symbol reservation.
 * close() and tryAdmit() share the same monitor, so their ordering has a
 * well-defined linearization point. An admitted operation may remain
 * in-flight after close(); close() prevents only new admissions.
 * This class is the runtime admission barrier; callers must not split the
 * admission check from the network side effect.
 */
class ExecutionGate {
    private val reservedSymbols = LinkedHashSet<String>()
    private var open = true

    @Synchronized
    fun tryAdmit(symbol: String): Boolean {
        val normalized = symbol.uppercase()
        if (normalized.isBlank() || !open) return false
        return reservedSymbols.add(normalized)
    }

    @Synchronized
    fun close() {
        open = false
    }

    @Synchronized
    fun open() {
        open = true
    }

    @Synchronized
    fun isOpen(): Boolean = open

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
