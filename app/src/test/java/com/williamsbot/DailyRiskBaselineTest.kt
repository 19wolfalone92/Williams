package com.williamsbot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

class DailyRiskBaselineTest {
    @Test
    fun unverifiedOrInvalidUtcDailyRiskBaselineNeverOpensExecutionGate() {
        assertFalse(futuresDailyRiskBaselineEstablished(null, "INITIAL_FLAT_SNAPSHOT"))
        assertFalse(futuresDailyRiskBaselineEstablished("5000", null))
        assertFalse(futuresDailyRiskBaselineEstablished("5000", "UNKNOWN"))
        assertFalse(futuresDailyRiskBaselineEstablished("5000", " null "))
        assertFalse(futuresDailyRiskBaselineEstablished("NaN", "INITIAL_FLAT_SNAPSHOT"))
        assertFalse(futuresDailyRiskBaselineEstablished("Infinity", "INITIAL_FLAT_SNAPSHOT"))
        assertFalse(futuresDailyRiskBaselineEstablished("0", "INITIAL_FLAT_SNAPSHOT"))
        assertFalse(futuresDailyRiskBaselineEstablished("-10", "INITIAL_FLAT_SNAPSHOT"))
        assertTrue(futuresDailyRiskBaselineEstablished("4998.03288909", "INITIAL_FLAT_SNAPSHOT"))
        assertTrue(futuresDailyRiskBaselineEstablished("4500", "RECONSTRUCTED_FROM_FUTURES_INCOME"))
    }

    @Test
    fun baselineCanBeInitializedForFlatIdleAccountWithNoTradingIncome() {
        assertTrue(canInitializeDailyRiskBaseline(
            hasOpenPosition = false,
            hasActiveExecution = false,
            hasTradingIncome = false
        ))
    }

    @Test
    fun baselineCannotResetAfterOpenPositionPendingExecutionOrTradingPnl() {
        assertFalse(canInitializeDailyRiskBaseline(true, false, false))
        assertFalse(canInitializeDailyRiskBaseline(false, true, false))
        assertFalse(canInitializeDailyRiskBaseline(false, false, true))
        assertFalse(canInitializeDailyRiskBaseline(true, true, true))
    }

    @Test
    fun baselineSourceRepairRequiresAnExistingValidPersistedBaseline() {
        assertTrue(canRepairDailyRiskBaselineSource("5000.00", null))
        assertTrue(canRepairDailyRiskBaselineSource("5000.00", "UNKNOWN"))
        assertTrue(canRepairDailyRiskBaselineSource("5000.00", "null"))
        assertFalse(canRepairDailyRiskBaselineSource(null, null))
        assertFalse(canRepairDailyRiskBaselineSource("0", "UNKNOWN"))
        assertFalse(canRepairDailyRiskBaselineSource("-1", "UNKNOWN"))
        assertFalse(canRepairDailyRiskBaselineSource("NaN", "UNKNOWN"))
        assertFalse(canRepairDailyRiskBaselineSource("5000", "EXCHANGE_ACCOUNT_SNAPSHOT"))
    }

    private fun pendingEntry(
        status: String,
        reconciledAt: Long,
        executed: String = "0"
    ): JSONObject = JSONObject()
        .put("intent_role", "ENTRY")
        .put("campaign_status", "ENTRY_PENDING")
        .put("order_status", status)
        .put("client_id", "TEST_PENDING")
        .put("executed_qty", executed)
        .put("last_reconciled_ms", reconciledAt)

    @Test
    fun freshExchangeConfirmedUnfilledRestingEntryDoesNotEraseUtcRiskBaseline() {
        val now = 1_792_501_000_000L
        for (status in listOf("NEW", "PENDING", "WORKING", "ACCEPTED")) {
            assertTrue(unfilledPendingIntentsAllowFlatDailyBaseline(
                JSONArray().put(pendingEntry(status, now - 2_000L)), now
            ))
        }
        assertTrue(unfilledPendingIntentsAllowFlatDailyBaseline(JSONArray(), now))
    }

    @Test
    fun uncertainOrFilledIntentsAreNeverTreatedAsFlatSafeForUtcReset() {
        val now = 1_792_501_000_000L
        for (status in listOf(
            "TRIGGERING", "TRIGGERED", "SUBMISSION_UNKNOWN",
            "CANCEL_SUBMISSION_UNKNOWN", "PARTIALLY_FILLED", "FILLED"
        )) {
            assertFalse(unfilledPendingIntentsAllowFlatDailyBaseline(
                JSONArray().put(pendingEntry(status, now - 1_000L)), now
            ))
        }
        assertFalse(unfilledPendingIntentsAllowFlatDailyBaseline(
            JSONArray().put(pendingEntry("NEW", now - 31_000L)), now
        ))
        assertFalse(unfilledPendingIntentsAllowFlatDailyBaseline(
            JSONArray().put(pendingEntry("NEW", now - 100L, "0.001")), now
        ))
        assertFalse(unfilledPendingIntentsAllowFlatDailyBaseline(
            JSONArray().put(pendingEntry("NEW", now - 100L)
                .put("actual_entry_executed_qty", "0.001")), now
        ))
        assertFalse(unfilledPendingIntentsAllowFlatDailyBaseline(
            JSONArray().put(pendingEntry("NEW", now - 100L)
                .put("intent_role", "EXIT")), now
        ))
        assertFalse(unfilledPendingIntentsAllowFlatDailyBaseline(
            JSONArray().put(JSONObject().put("intent_role", "ENTRY")
                .put("campaign_status", "FILL_CONFIRMATION_PENDING")), now
        ))
        assertFalse(unfilledPendingIntentsAllowFlatDailyBaseline(
            JSONArray().put(JSONObject.NULL), now
        ))
    }
}
