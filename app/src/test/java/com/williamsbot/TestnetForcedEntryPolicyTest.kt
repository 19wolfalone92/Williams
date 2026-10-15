package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class TestnetForcedEntryPolicyTest {
    @Test
    fun baseline_reconstruction_subtracts_all_income_but_daily_trading_pnl_excludes_capital_flow() {
        val income = JSONArray()
            .put(JSONObject().put("incomeType", "REALIZED_PNL").put("income", "50.0").put("time", 1792000000000L))
            .put(JSONObject().put("incomeType", "COMMISSION").put("income", "-5.0").put("time", 1792000001000L))
            .put(JSONObject().put("incomeType", "TRANSFER").put("income", "100.0").put("time", 1792000002000L))

        val summary = summarizeFuturesDailyIncome(income)!!
        assertEquals(145.0, summary.totalIncomeUsdt, 1e-9)
        assertEquals(45.0, summary.tradingIncomeUsdt, 1e-9)
        assertEquals(100.0, summary.capitalFlowUsdt, 1e-9)
        assertEquals(true, summary.hasTradingActivity)

        // Current equity = opening equity 4975 + trading PnL 45 + transfer 100
        // + current unrealized PnL (-20) = 5100. Reconstructed opening equity
        // must not treat the external transfer as trading performance.
        assertEquals(4975.0, reconstructFuturesDailyRiskBaseline(5100.0, -20.0, income)!!, 1e-9)
        val dailyTradingPnl = summary.tradingIncomeUsdt - 20.0
        assertEquals(25.0, dailyTradingPnl, 1e-9)
    }

    @Test
    fun daily_income_summary_rejects_truncated_or_malformed_ledger() {
        val fullPage = JSONArray()
        repeat(1000) {
            fullPage.put(JSONObject().put("incomeType", "COMMISSION").put("income", "-0.01").put("time", 1792000000000L))
        }
        assertNull(summarizeFuturesDailyIncome(fullPage, maximumRows = 1000))
        assertNull(summarizeFuturesDailyIncome(JSONArray().put(JSONObject().put("incomeType", "TRANSFER"))))
    }

    @Test
    fun capital_flow_only_ledger_reconstructs_baseline_and_does_not_hide_unrealized_loss() {
        val transferOnly = JSONArray()
            .put(JSONObject().put("incomeType", "TRANSFER").put("income", "100.0").put("time", 1792000000000L))
        val decision = selectFuturesDailyBaseline(
            currentEquityUsdt = 5100.0,
            unrealizedPnlUsdt = -20.0,
            incomeRows = transferOnly,
            hasOpenOrUnknownPositionOrPendingExecution = false
        )
        assertNotNull(decision)
        assertEquals(5020.0, decision!!.baselineEquityUsdt, 1e-9)
        assertEquals("RECONSTRUCTED_FROM_CAPITAL_FLOW_LEDGER", decision.source)
        val summary = summarizeFuturesDailyIncome(transferOnly)!!
        assertEquals(0.0, summary.tradingIncomeUsdt, 0.0)
        assertEquals(-20.0, summary.tradingIncomeUsdt - 0.0 + (-20.0), 1e-9)
    }

    @Test
    fun empty_income_ledger_is_allowed_only_for_a_flat_reconciled_account() {
        val flat = selectFuturesDailyBaseline(
            currentEquityUsdt = 5000.0,
            unrealizedPnlUsdt = 0.0,
            incomeRows = JSONArray(),
            hasOpenOrUnknownPositionOrPendingExecution = false
        )
        assertNotNull(flat)
        assertEquals(5000.0, flat!!.baselineEquityUsdt, 0.0)
        assertEquals("INITIAL_FLAT_SNAPSHOT", flat.source)

        assertNull(
            selectFuturesDailyBaseline(
                currentEquityUsdt = 5000.0,
                unrealizedPnlUsdt = 0.0,
                incomeRows = JSONArray(),
                hasOpenOrUnknownPositionOrPendingExecution = true
            )
        )
    }

    @Test
    fun baseline_reconstruction_fails_closed_on_missing_unrealized_pnl_or_truncated_income() {
        assertNull(reconstructFuturesDailyRiskBaseline(5000.0, null, JSONArray()))
        val fullPage = JSONArray()
        repeat(1000) {
            fullPage.put(JSONObject().put("incomeType", "COMMISSION").put("income", "-0.01").put("time", 1792000000000L))
        }
        assertNull(reconstructFuturesDailyRiskBaseline(5000.0, 0.0, fullPage, maximumRows = 1000))
    }

    @Test
    fun complete_recursively_paginated_income_can_exceed_one_thousand_rows() {
        val allRows = JSONArray()
        repeat(1200) { index ->
            allRows.put(
                JSONObject()
                    .put("incomeType", "COMMISSION")
                    .put("income", "-0.01")
                    .put("time", 1792000000000L + index)
            )
        }
        val summary = summarizeFuturesDailyIncome(allRows)
        assertNotNull(summary)
        assertEquals(-12.0, summary!!.tradingIncomeUsdt, 1e-8)
        assertEquals(0.0, summary.capitalFlowUsdt, 0.0)
        assertEquals(true, summary.hasTradingActivity)
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

    @Test
    fun order_direction_is_explicit_and_unknown_signal_never_defaults_to_long() {
        assertEquals("LONG", campaignSignalDirection("REVERSAL"))
        assertEquals("LONG", campaignSignalDirection("SUPER_AO"))
        assertEquals("LONG", campaignSignalDirection("FRACTAL"))
        assertEquals("LONG", campaignSignalDirection("LONG_BREAKOUT"))
        assertEquals("SHORT", campaignSignalDirection("SHORT_REVERSAL"))
        assertEquals("SHORT", campaignSignalDirection("SHORT_SUPER_AO"))
        assertNull(campaignSignalDirection("UNKNOWN_SIGNAL"))
        assertNull(campaignSignalDirection(""))
    }

    @Test
    fun canonical_long_signal_types_emitted_by_engine_are_not_misclassified_as_unknown() {
        // longCampaignSignals emits these unprefixed types; the short mirror prefixes SHORT_.
        assertEquals(2, directionalSignalContextRank("SUPER_AO", false, false, true, false))
        assertEquals(2, directionalSignalContextRank("FRACTAL", false, false, true, false))
        assertEquals(1, directionalSignalContextRank("REVERSAL", false, false, false, true))
        assertEquals(0, directionalSignalContextRank("SUPER_AO", false, true, true, false))
        assertEquals(2, directionalSignalContextRank("SHORT_SUPER_AO", false, false, false, true))
        assertEquals(1, directionalSignalContextRank("SHORT_REVERSAL", false, false, true, false))
    }
}
