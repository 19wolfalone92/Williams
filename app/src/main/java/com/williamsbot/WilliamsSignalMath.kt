package com.williamsbot

/**
 * A historical conditional-entry signal is no longer actionable once a later
 * closed candle has already touched its trigger or invalidated the signal
 * candle's opposite extreme. Re-arming it afterwards would be a new, unproven
 * setup rather than execution of the original hypothesis.
 *
 * This mirrors the Native Futures engine's signal freshness/invalidation rule
 * and fails closed on malformed inputs.
 */
internal fun isLongSignalStillActionable(
    signalIndex: Int,
    currentIndex: Int,
    highs: List<Double>,
    lows: List<Double>,
    triggerPrice: Double
): Boolean {
    if (highs.size != lows.size || highs.isEmpty()) return false
    if (signalIndex !in highs.indices || currentIndex !in highs.indices) return false
    if (signalIndex > currentIndex || !triggerPrice.isFinite() || triggerPrice <= 0.0) return false
    val sourceLow = lows[signalIndex]
    if (!sourceLow.isFinite()) return false

    for (i in signalIndex + 1..currentIndex) {
        val high = highs[i]
        val low = lows[i]
        if (!high.isFinite() || !low.isFinite()) return false
        if (high >= triggerPrice || low <= sourceLow) return false
    }
    return true
}
