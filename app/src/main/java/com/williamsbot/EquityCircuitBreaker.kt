package com.williamsbot

/**
 * Hard daily equity-loss gate.
 *
 * It blocks new autonomous entries once equity is down by the configured
 * percentage from the UTC start-of-day baseline. It does NOT liquidate
 * protected positions automatically; existing OCO protection remains active.
 */
class EquityCircuitBreaker(
    private val maxDrawdownPct: Double = 0.03
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
        if (!equity.isFinite() || equity < 0.0) {
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

    /**
     * Model B supplies the exact decimal threshold decision; Double is for
     * diagnostics only. Never round a value across the emergency boundary.
     */
    @Synchronized
    fun observeVerifiedModelB(
        baseline: Double, equity: Double, emergencyReached: Boolean, nowMs: Long
    ): Snapshot {
        require(baseline.isFinite() && baseline > 0.0 && equity.isFinite() && equity >= 0.0)
        val day = java.time.Instant.ofEpochMilli(nowMs)
            .atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()
        if (day > dayKey || startEquity <= 0.0) {
            dayKey = day
            tripped = false
        }
        startEquity = baseline
        tripped = tripped || emergencyReached
        return Snapshot(equity, baseline, ((baseline - equity) / baseline).coerceAtLeast(0.0), tripped)
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
