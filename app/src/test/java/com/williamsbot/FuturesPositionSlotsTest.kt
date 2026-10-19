package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

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

    private fun confirmedEntry(symbol: String, lifecycle: String, orderStatus: String) =
        entry(symbol, lifecycle)
            .put("direction", "LONG")
            .put("exchange_status", orderStatus)
            .put("order_status", orderStatus)

    @Test
    fun multipleExchangeConfirmedPendingEntriesDoNotFreezeOtherSymbols() {
        val intents = JSONArray()
            .put(confirmedEntry("BTCUSDT", "ENTRY_PENDING", "NEW"))
            .put(confirmedEntry("ETHUSDT", "ENTRY_PENDING", "ACCEPTED"))
        org.junit.Assert.assertTrue(confirmedPendingEntriesAllowConcurrentAdmission(intents))
    }

    @Test
    fun concurrentAdmissionAllowsProtectedPositionsBesideAConfirmedPendingEntry() {
        val intents = JSONArray()
            .put(confirmedEntry("BTCUSDT", "OPEN_PROTECTED", "FILLED")
                .put("protection_status", "PROTECTED")
                .put("protection_client_id", "W2PS_BTC"))
            .put(confirmedEntry("ETHUSDT", "ENTRY_PENDING", "NEW"))
        org.junit.Assert.assertTrue(confirmedPendingEntriesAllowConcurrentAdmission(intents))
    }

    @Test
    fun concurrentAdmissionBlocksUnknownOrPartiallyReconciledExecution() {
        val ambiguous = JSONArray().put(
            confirmedEntry("BTCUSDT", "ENTRY_PENDING", "SUBMISSION_UNKNOWN")
        )
        org.junit.Assert.assertFalse(confirmedPendingEntriesAllowConcurrentAdmission(ambiguous))

        val fillUnknown = JSONArray().put(
            confirmedEntry("BTCUSDT", "FILL_CONFIRMATION_PENDING", "FILLED")
        )
        org.junit.Assert.assertFalse(confirmedPendingEntriesAllowConcurrentAdmission(fillUnknown))

        val unresolvedExit = JSONArray()
            .put(confirmedEntry("BTCUSDT", "ENTRY_PENDING", "NEW"))
            .put(JSONObject().put("intent_role", "EXIT")
                .put("symbol", "ETHUSDT").put("exchange_status", "SUBMISSION_UNKNOWN"))
        org.junit.Assert.assertFalse(confirmedPendingEntriesAllowConcurrentAdmission(unresolvedExit))
    }

    private fun utcMillis(hour: Int, minute: Int): Long =
        GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(2026, Calendar.OCTOBER, 15, hour, minute, 0)
        }.timeInMillis

    @Test
    fun configuredEntryWindowIsUtcAndDefaultPolicyAllowsAllHours() {
        val policy=WilliamsEntryTimePolicy("UTC_WINDOW","08:00","20:00")
        assertTrue(WilliamsEntryTimePolicy().allows(utcMillis(1,0)))
        assertTrue(WilliamsEntryTimePolicy().allows(utcMillis(23,0)))
        assertFalse(policy.allows(0L))
        assertFalse(policy.allows(utcMillis(7, 59)))
        assertTrue(policy.allows(utcMillis(8, 0)))
        assertTrue(policy.allows(utcMillis(19, 59)))
        assertFalse(policy.allows(utcMillis(20, 0)))
        // The helper is exclusively an entry gate; position management is called
        // independently by the scanner and remains outside this time predicate.
    }
}
