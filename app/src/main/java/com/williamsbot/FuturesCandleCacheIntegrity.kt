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

/** Native UTC close boundary for the durable history store. No network or
 * phone clock. Unsupported/off-grid input is never treated as a closed row. */
internal fun nativeHistoryCloseMs(openMs: Long, frame: String): Long? {
    if (openMs <= 0L) return null
    if (frame == "1M") {
        val utc = java.time.Instant.ofEpochMilli(openMs).atZone(java.time.ZoneOffset.UTC)
        if (utc.dayOfMonth != 1 || utc.toLocalTime() != java.time.LocalTime.MIDNIGHT) return null
        return runCatching { utc.plusMonths(1).toInstant().toEpochMilli() - 1L }.getOrNull()
    }
    val step = when(frame) {
        "1m" -> 60_000L
        "3m" -> 180_000L
        "5m" -> 300_000L
        "15m" -> 900_000L
        "30m" -> 1_800_000L
        "1h" -> 3_600_000L
        "2h" -> 7_200_000L
        "4h" -> 14_400_000L
        "6h" -> 21_600_000L
        "8h" -> 28_800_000L
        "12h" -> 43_200_000L
        "1d" -> 86_400_000L
        "3d" -> 259_200_000L
        "1w" -> 604_800_000L
        else -> return null
    }
    if (!futuresFixedKlineOpenUtcAligned(openMs, step) || openMs > Long.MAX_VALUE-step) return null
    return openMs+step-1L
}
