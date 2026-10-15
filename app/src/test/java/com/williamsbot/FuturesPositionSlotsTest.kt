package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FuturesPositionSlotsTest {
    private fun entry(symbol: String, status: String? = null): JSONObject =
        JSONObject()
            .put("intent_role", "ENTRY")
            .put("symbol", symbol)
            .apply { if (status != null) put("campaign_status", status) }

    @Test
    fun aggregateRiskAllowsSeveralCampaignsOnlyWithinPortfolioCap() {
        assertNull(futuresPortfolioRiskBlocker(0.002, listOf(0.002, 0.001)))
        assertEquals(
            "Aggregate Futures campaign risk would exceed 0.60% equity",
            futuresPortfolioRiskBlocker(0.002, listOf(0.0025, 0.0025))
        )
    }

    @Test
    fun unknownExistingRiskReservationBlocksAnotherEntry() {
        assertEquals(
            "An active Futures campaign has an unknown/invalid risk reservation; reconciliation required",
            futuresPortfolioRiskBlocker(0.002, listOf(Double.NaN))
        )
    }

    @Test
    fun allowsDistinctSymbolsUntilConfiguredPortfolioSlotLimit() {
        val intents = JSONArray().put(entry("BTCUSDT", "OPEN_PROTECTED"))
        assertNull(futuresPositionSlotBlocker("ETHUSDT", listOf("BTCUSDT"), intents, 10))
    }

    @Test
    fun countsOpenPositionsAndPendingCampaignsWithoutDoubleCountingSameSymbol() {
        val intents = JSONArray()
            .put(entry("BTCUSDT", "OPEN_PROTECTED"))
            .put(entry("ETHUSDT", "ENTRY_PENDING"))
        assertEquals(setOf("BTCUSDT", "ETHUSDT"),
            reservedFuturesPositionSymbols(listOf("BTCUSDT"), intents))
        assertEquals("Maximum concurrent Futures campaigns reached (2)",
            futuresPositionSlotBlocker("SOLUSDT", listOf("BTCUSDT"), intents, 2))
    }

    @Test
    fun sameSymbolIsBlockedEvenIfOnlyPersistedCampaignIsKnown() {
        val intents = JSONArray().put(entry("BTCUSDT", "ENTRY_PENDING"))
        assertEquals("BTCUSDT already has an open or unresolved campaign",
            futuresPositionSlotBlocker("btcusdt", emptyList(), intents, 10))
    }

    @Test
    fun unknownIntentReservesASlotButTerminalCampaignDoesNot() {
        val unknown = JSONArray().put(entry("BTCUSDT"))
        assertEquals("Maximum concurrent Futures campaigns reached (1)",
            futuresPositionSlotBlocker("ETHUSDT", emptyList(), unknown, 1))
        val terminal = JSONArray().put(entry("BTCUSDT", "CLOSED_OR_STOPPED"))
        assertNull(futuresPositionSlotBlocker("ETHUSDT", emptyList(), terminal, 10))
    }
}
