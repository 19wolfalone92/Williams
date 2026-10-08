package com.williamsbot

/**
 * Hard daily equity-loss gate.
 *
 * It blocks new autonomous entries once equity is down by the configured
 * percentage from the UTC start-of-day baseline. It does NOT liquidate
 * protected positions automatically; existing OCO protection remains active.
 */
class EquityCircuitBreaker(
    private val maxDrawdownPct: Double = 0.01
) {
    data class Snapshot(
        val equity: Double,
        val startEquity: Double,
        val drawdownPct: Double,
        val tripped: Boolean
    )

    private var dayKey: String = ""
    private var startEquity = 0.0
    private var tripped = false

    @Synchronized
    fun observe(equity: Double, nowMs: Long = System.currentTimeMillis()): Snapshot {
        if (!equity.isFinite() || equity <= 0.0) {
            return Snapshot(equity, startEquity, 0.0, tripped)
        }

        val day = java.time.Instant.ofEpochMilli(nowMs)
            .atZone(java.time.ZoneOffset.UTC)
            .toLocalDate()
            .toString()

        if (day != dayKey || startEquity <= 0.0) {
            dayKey = day
            startEquity = equity
            tripped = false
        }

        val lossPct = ((startEquity - equity) / startEquity)
            .coerceAtLeast(0.0)

        if (lossPct >= maxDrawdownPct) {
            tripped = true
        }

        return Snapshot(
            equity = equity,
            startEquity = startEquity,
            drawdownPct = lossPct,
            tripped = tripped
        )
    }

    @Synchronized
    fun reset(equity: Double, nowMs: Long = System.currentTimeMillis()) {
        dayKey = java.time.Instant.ofEpochMilli(nowMs)
            .atZone(java.time.ZoneOffset.UTC)
            .toLocalDate()
            .toString()
        startEquity = equity.coerceAtLeast(0.0)
        tripped = false
    }

    @Synchronized
    fun isTripped(): Boolean = tripped
}
