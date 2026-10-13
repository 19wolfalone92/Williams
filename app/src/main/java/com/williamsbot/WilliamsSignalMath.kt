package com.williamsbot

/**
 * Causal pending-signal predicates. TTL is an implementation/safety policy,
 * not a Bill Williams rule. Unknown/non-finite data blocks action.
 */
internal object WilliamsSignalMath {
    fun isLongSignalStillActionable(
        signalIndex: Int,
        currentIndex: Int,
        highs: List<Double>,
        lows: List<Double>,
        trigger: Double
    ): Boolean {
        if (
            signalIndex !in highs.indices ||
            signalIndex !in lows.indices ||
            currentIndex !in highs.indices ||
            currentIndex !in lows.indices ||
            currentIndex < signalIndex ||
            !trigger.isFinite() ||
            trigger <= 0.0
        ) return false

        val signalHigh = highs[signalIndex]
        val signalLow = lows[signalIndex]
        if (
            !signalHigh.isFinite() ||
            !signalLow.isFinite() ||
            signalLow <= 0.0 ||
            signalHigh < signalLow ||
            signalHigh >= trigger
        ) return false

        if (currentIndex == signalIndex) return true
        for (i in (signalIndex + 1)..currentIndex) {
            val high = highs[i]
            val low = lows[i]
            if (!high.isFinite() || !low.isFinite() || low <= 0.0 || high < low) return false
            // A trigger that was already traded must not be re-armed later.
            if (high >= trigger) return false
            // The setup bar's structural low invalidates a pending LONG.
            if (low < signalLow) return false
        }
        return true
    }

    fun isExpired(currentTimeMs: Long, expiresAtMs: Long): Boolean =
        currentTimeMs >= expiresAtMs
}
