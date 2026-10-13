package com.williamsbot

/**
 * Hard daily equity-loss gate.
 *
 * The UTC-day baseline and trip latch are persisted by the caller. A process
 * restart must never reset a daily loss circuit breaker inside the same day.
 */
class EquityCircuitBreaker(
    private val maxDrawdownPct: Double = 0.03,
    loadState: (() -> PersistedState)? = null,
    private val saveState: ((PersistedState) -> Boolean)? = null
) {
    data class PersistedState(
        val dayKey: String,
        val startEquity: Double,
        val tripped: Boolean
    )

    data class Snapshot(
        val equity: Double,
        val startEquity: Double,
        val drawdownPct: Double,
        val tripped: Boolean
    )

    private var dayKey: String = ""
    private var startEquity = 0.0
    private var tripped = false

    init {
        require(maxDrawdownPct.isFinite() && maxDrawdownPct in 0.0..1.0) {
            "maxDrawdownPct must be finite and in [0, 1]"
        }
        val saved = loadState?.invoke()
        if (saved != null) {
            require(saved.startEquity.isFinite() && saved.startEquity >= 0.0) {
                "persisted daily start equity is invalid"
            }
            dayKey = saved.dayKey
            startEquity = saved.startEquity
            tripped = saved.tripped
        }
    }

    private fun persistOrTrip() {
        val saver = saveState ?: return
        try {
            val committed = saver(PersistedState(dayKey, startEquity, tripped))
            if (!committed) throw IllegalStateException("daily breaker state commit failed")
        } catch (error: Exception) {
            // Do not admit new risk if the daily baseline/latch cannot be saved.
            tripped = true
            throw IllegalStateException("daily breaker persistence unavailable", error)
        }
    }

    @Synchronized
    fun observe(equity: Double, nowMs: Long = System.currentTimeMillis()): Snapshot {
        if (!equity.isFinite() || equity <= 0.0) {
            tripped = true
            persistOrTrip()
            return Snapshot(equity, startEquity, 0.0, true)
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

        persistOrTrip()
        return Snapshot(
            equity = equity,
            startEquity = startEquity,
            drawdownPct = lossPct,
            tripped = tripped
        )
    }

    @Synchronized
    fun isTripped(): Boolean = tripped

    @Synchronized
    fun persistedState(): PersistedState = PersistedState(dayKey, startEquity, tripped)
}
