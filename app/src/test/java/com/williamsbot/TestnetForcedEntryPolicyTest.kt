package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class TestnetForcedEntryPolicyTest {
    @Test
    fun baseline_reconstruction_excludes_transfers_but_counts_trading_pnl() {
        val income = JSONArray()
            .put(JSONObject().put("incomeType", "REALIZED_PNL").put("income", "50.0").put("time", 1792000000000L))
            .put(JSONObject().put("incomeType", "COMMISSION").put("income", "-5.0").put("time", 1792000001000L))
            .put(JSONObject().put("incomeType", "TRANSFER").put("income", "100.0").put("time", 1792000002000L))
        assertEquals(5075.0, reconstructFuturesDailyRiskBaseline(5100.0, -20.0, income)!!, 1e-9)
    }

    @Test
    fun baseline_reconstruction_fails_closed_on_missing_unrealized_pnl_or_truncated_income() {
        assertNull(reconstructFuturesDailyRiskBaseline(5000.0, null, JSONArray()))
        val fullPage = JSONArray()
        repeat(1000) {
            fullPage.put(JSONObject().put("incomeType", "COMMISSION").put("income", "-0.01").put("time", 1792000000000L))
        }
        assertNull(reconstructFuturesDailyRiskBaseline(5000.0, 0.0, fullPage))
    }

    @Test
    fun forced_h1_levels_are_directionally_mirrored_and_structural() {
        val highs = listOf(101.0, 102.0, 104.0, 103.0, 102.0)
        val lows = listOf(99.0, 99.5, 100.0, 98.0, 98.5)
        val long = forcedH1EntryLevels("LONG", highs, lows, 1.0)
        val short = forcedH1EntryLevels("SHORT", highs, lows, 1.0)
        assertNotNull(long)
        assertEquals(105.0, long!!.triggerPrice, 0.0)
        assertEquals(97.0, long.protectiveStop, 0.0)
        assertNotNull(short)
        assertEquals(97.0, short!!.triggerPrice, 0.0)
        assertEquals(105.0, short.protectiveStop, 0.0)
    }

    @Test
    fun forced_h1_levels_reject_bad_direction_tick_and_history() {
        val highs = listOf(101.0, 102.0, 104.0, 103.0, 102.0)
        val lows = listOf(99.0, 99.5, 100.0, 98.0, 98.5)
        assertNull(forcedH1EntryLevels("BOTH", highs, lows, 1.0))
        assertNull(forcedH1EntryLevels("LONG", highs, lows, 0.0))
        assertNull(forcedH1EntryLevels("SHORT", highs.take(4), lows.take(4), 1.0))
    }

    @Test
    fun directional_context_preserves_long_and_short_and_applies_daily_veto() {
        assertEquals(2, directionalSignalContextRank("LONG_BREAKOUT", false, false, true, false))
        assertEquals(2, directionalSignalContextRank("SHORT_BREAKOUT", false, false, false, true))
        assertEquals(0, directionalSignalContextRank("LONG_BREAKOUT", false, true, true, false))
        assertEquals(0, directionalSignalContextRank("SHORT_BREAKOUT", true, false, false, true))
        assertEquals(1, directionalSignalContextRank("LONG_REVERSAL", false, false, false, true))
        assertEquals(0, directionalSignalContextRank("UNKNOWN_SIGNAL", false, false, true, true))
    }
}
