package com.williamsbot

import org.json.JSONObject

/** Diagnostic ONLY: explains which closed-candle invariant prevents an
 * independent timeframe reading. No scanner gates, REST, backfill or orders.
 * Evidence is restricted to timestamps and counts; no API credentials.
 */
internal object WilliamsHistoryIntegrity {
    fun inspect(bars: List<WilliamsWaveBar>, frame: String, observedAtMs: Long): JSONObject {
        val frameMs = williamsWaveFrames[frame]
        val result = JSONObject().put("schema", "WILLIAMS_HISTORY_INTEGRITY_V1")
            .put("frame", frame).put("checked_bars", bars.size)
            .put("read_only", true).put("can_authorize_entries", false)
            .put("root_cause", "NOT_VERIFIED")
            .put("missing_interval_count", 0L)
            .put("gap_count", 0)
            .put("first_bad_bar_open_ms", JSONObject.NULL)
            .put("expected_next_open_ms", JSONObject.NULL)
            .put("observed_next_open_ms", JSONObject.NULL)
            .put("latest_closed_at_ms", JSONObject.NULL)
        if (frameMs == null || frameMs <= 0L)
            return result.put("root_cause", "UNSUPPORTED_FRAME")
        result.put("frame_ms", frameMs)
        if (observedAtMs <= 0L)
            return result.put("root_cause", "CLOCK_UNVERIFIED")
        if (bars.isEmpty())
            return result.put("root_cause", "NO_CLOSED_BARS")
        val lastOpen = bars.last().t
        if (lastOpen > 0L && lastOpen <= observedAtMs-frameMs)
            result.put("latest_closed_at_ms", lastOpen + frameMs)
                .put("latest_close_age_ms", observedAtMs-lastOpen-frameMs)
        var firstInvalid = -1
        for ((index, bar) in bars.withIndex()) {
            if (!validWilliamsEditionBars(listOf(bar))) { firstInvalid=index;break }
        }
        if (firstInvalid >= 0) {
            return result.put("root_cause", "INVALID_OHLCV")
                .put("first_bad_bar_open_ms", bars[firstInvalid].t)
        }
        val firstMisaligned = bars.indexOfFirst { it.t % frameMs != 0L }
        if (firstMisaligned >= 0) {
            return result.put("root_cause", "BAR_TIME_MISALIGNED")
                .put("first_bad_bar_open_ms", bars[firstMisaligned].t)
        }
        var gaps = 0
        var missing = 0L
        var firstExpected: Long? = null
        var firstObserved: Long? = null
        for (i in 1 until bars.size) {
            val earlier = bars[i-1].t
            val current = bars[i].t
            if (current <= earlier)
                return result.put("root_cause", "DUPLICATE_OR_REVERSED_BAR")
                    .put("first_bad_bar_open_ms", current)
            if (current-earlier != frameMs) {
                gaps++
                missing += (current-earlier)/frameMs-1L
                if (firstExpected == null) {
                    firstExpected = earlier+frameMs
                    firstObserved = current
                }
            }
        }
        if (gaps > 0) return result.put("root_cause", "MISSING_CANDLE_INTERVALS")
            .put("gap_count", gaps).put("missing_interval_count", missing)
            .put("expected_next_open_ms", firstExpected)
            .put("observed_next_open_ms", firstObserved)
        if (lastOpen > observedAtMs-frameMs)
            return result.put("root_cause", "OPEN_OR_FUTURE_BAR")
                .put("first_bad_bar_open_ms", lastOpen)
        if (bars.size < 39)
            return result.put("root_cause", "INSUFFICIENT_HISTORY")
                .put("minimum_bars", 39)
        if (observedAtMs-lastOpen-frameMs >= 2L*frameMs)
            return result.put("root_cause", "STALE_CLOSED_HISTORY")
        return result.put("root_cause", "CONTIGUOUS_CLOSED")
            .put("closed_history_complete_for_observed_window", true)
    }
}
