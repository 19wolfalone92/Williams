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
        if (!equity.isFinite() || equity <= 0.0) {
            tripped = true
            return Snapshot(equity, startEquity, Double.POSITIVE_INFINITY, tripped)
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
     * Restore the same UTC day's baseline and latched trip after process restart.
     * The caller must verify the persisted day key before restoring this state.
     */
    @Synchronized
    fun restore(
        startEquity: Double,
        wasTripped: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ) {
        require(startEquity.isFinite() && startEquity > 0.0) {
            "persisted daily equity baseline is invalid"
        }
        dayKey = java.time.Instant.ofEpochMilli(nowMs)
            .atZone(java.time.ZoneOffset.UTC)
            .toLocalDate()
            .toString()
        this.startEquity = startEquity
        tripped = wasTripped
    }

    @Synchronized
    fun reset(equity: Double, nowMs: Long = System.currentTimeMillis()) {
        require(equity.isFinite() && equity > 0.0) {
            "cannot establish daily equity baseline from invalid equity"
        }
        dayKey = java.time.Instant.ofEpochMilli(nowMs)
            .atZone(java.time.ZoneOffset.UTC)
            .toLocalDate()
            .toString()
        startEquity = equity
        tripped = false
    }

    @Synchronized
    fun isTripped(): Boolean = tripped
}
