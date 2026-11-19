package com.williamsbot

/** Verify an exchange-native, fixed-length closed-candle series before using
 * its SQLite/WS cache for H1 decision context or independent M15/D1 readings.
 * No REST calls, order mutations or speculative candles. Calendar months are
 * checked by their native calendar logic and are not passed here.
 */
internal fun futuresClosedCandleTimesContiguous(openTimes: List<Long>, intervalMs: Long): Boolean {
    if (openTimes.isEmpty() || intervalMs <= 0L) return false
    var previous = openTimes.first()
    if (previous <= 0L) return false
    for (i in 1 until openTimes.size) {
        val current = openTimes[i]
        if (current <= previous || current - previous != intervalMs) return false
        previous = current
    }
    return true
}
