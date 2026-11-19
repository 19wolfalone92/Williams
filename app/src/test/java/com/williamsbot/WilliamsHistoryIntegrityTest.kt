package com.williamsbot

import org.junit.Assert.*
import org.junit.Test

class WilliamsHistoryIntegrityTest {
    private val step=900_000L
    private val start=1710000000000L / step * step
    private fun bar(at:Long) = WilliamsWaveBar(at,100.0,101.0,99.0,100.0,10.0)
    private fun series(n:Int=45) = (0 until n).map { bar(start+it*step) }

    @Test fun anActualSqliteGapIsExplainedWithoutTradingAuthority() {
        val original=series()
        val withGap=original.filterIndexed { index,_ -> index != 20 }
        val result=WilliamsHistoryIntegrity.inspect(withGap,"15m",start+46*step)
        assertEquals("MISSING_CANDLE_INTERVALS",result.getString("root_cause"))
        assertEquals(1L,result.getLong("missing_interval_count"))
        assertEquals(1,result.getInt("gap_count"))
        assertEquals(start+20*step,result.getLong("expected_next_open_ms"))
        assertEquals(start+21*step,result.getLong("observed_next_open_ms"))
        assertFalse(result.getBoolean("can_authorize_entries"))
    }

    @Test fun cleanClosedSeriesIsObservedButNotAnEntryCertificate() {
        val result=WilliamsHistoryIntegrity.inspect(series(),"15m",start+45*step)
        assertEquals("CONTIGUOUS_CLOSED",result.getString("root_cause"))
        assertTrue(result.getBoolean("closed_history_complete_for_observed_window"))
        assertFalse(result.getBoolean("can_authorize_entries"))
    }

    @Test fun invalidUnclosedStaleAndInsufficientCasesHaveDifferentCodes() {
        val base=series()
        assertEquals("INVALID_OHLCV",WilliamsHistoryIntegrity.inspect(
            base.dropLast(1)+WilliamsWaveBar(base.last().t,100.0,98.0,99.0,100.0,10.0),
            "15m",start+45*step).getString("root_cause"))
        assertEquals("OPEN_OR_FUTURE_BAR",WilliamsHistoryIntegrity.inspect(
            base,"15m",start+44*step).getString("root_cause"))
        assertEquals("STALE_CLOSED_HISTORY",WilliamsHistoryIntegrity.inspect(
            base,"15m",start+48*step).getString("root_cause"))
        assertEquals("INSUFFICIENT_HISTORY",WilliamsHistoryIntegrity.inspect(
            base.take(15),"15m",start+15*step).getString("root_cause"))
        assertEquals("NO_CLOSED_BARS",WilliamsHistoryIntegrity.inspect(
            emptyList(),"15m",start+45*step).getString("root_cause"))
    }
}
