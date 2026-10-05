package com.williamsbot

import kotlin.math.max

/**
 * Rolling peak-to-current equity breaker.
 *
 * It is deliberately strategy-scoped: the caller supplies the equity that
 * Williams controls. Manual/foreign positions are not liquidated.
 */
class EquityCircuitBreaker(
    private val windowMs: Long = 60L * 60L * 1000L,
    private val maxDrawdownPct: Double = 0.05
) {
    data class Snapshot(
        val equity: Double,
        val peak: Double,
        val drawdownPct: Double,
        val tripped: Boolean
    )

    private var peakEquity = 0.0
    private var peakAt = 0L
    private var tripped = false

    @Synchronized
    fun observe(equity: Double, nowMs: Long = System.currentTimeMillis()): Snapshot {
        if (!equity.isFinite() || equity <= 0.0) {
            return Snapshot(equity, peakEquity, 0.0, tripped)
        }

        if (peakEquity <= 0.0 || nowMs - peakAt > windowMs) {
            peakEquity = equity
            peakAt = nowMs
        } else {
            peakEquity = max(peakEquity, equity)
            if (equity >= peakEquity) peakAt = nowMs
        }

        val drawdown = if (peakEquity > 0.0) {
            ((peakEquity - equity) / peakEquity).coerceAtLeast(0.0)
        } else 0.0

        if (drawdown >= maxDrawdownPct) tripped = true
        return Snapshot(equity, peakEquity, drawdown, tripped)
    }

    @Synchronized
    fun reset(equity: Double, nowMs: Long = System.currentTimeMillis()) {
        peakEquity = equity.coerceAtLeast(0.0)
        peakAt = nowMs
        tripped = false
    }

    @Synchronized
    fun isTripped(): Boolean = tripped
}
