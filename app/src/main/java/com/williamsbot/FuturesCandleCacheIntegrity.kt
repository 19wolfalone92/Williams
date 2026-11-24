package com.williamsbot

/** Verify an exchange-native, fixed-length closed-candle series before using
 * its SQLite/WS cache for H1 decision context or independent M15/D1 readings.
 * No REST calls, order mutations or speculative candles. Calendar months are
 * checked by their native calendar logic and are not passed here.
 */
/** Binance fixed kline UTC start: weekly rows start Monday 00:00 UTC. */
internal fun futuresFixedKlineOpenUtcAligned(openTimeMs: Long, intervalMs: Long): Boolean {
    if (openTimeMs < 0L || intervalMs <= 0L) return false
    val originMs = if (intervalMs == 7L * 86_400_000L) 4L * 86_400_000L else 0L
    return (openTimeMs - originMs) % intervalMs == 0L
}

internal fun futuresClosedCandleTimesContiguous(openTimes: List<Long>, intervalMs: Long): Boolean {
    if (openTimes.isEmpty() || intervalMs <= 0L) return false
    var previous = openTimes.first()
    if (previous <= 0L || !futuresFixedKlineOpenUtcAligned(previous, intervalMs)) return false
    for (i in 1 until openTimes.size) {
        val current = openTimes[i]
        if (!futuresFixedKlineOpenUtcAligned(current, intervalMs) ||
            current <= previous || current - previous != intervalMs) return false
        previous = current
    }
    return true
}
