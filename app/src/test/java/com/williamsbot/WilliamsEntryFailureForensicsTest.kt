package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WilliamsEntryFailureForensicsTest {
    private fun row(event:String,at:Long,state:String,detail:String,symbol:String="TRXUSDT") =
        JSONObject().put("e",event).put("E",at).put("state",state)
            .put("detail",detail).put("symbol",symbol)

    @Test fun realPhoneMarginAndClockFailuresRemainVisibleWithoutDeclaringCurrentGateBlocked() {
        val now=180_000_000L
        val privateId="secret-client-id-must-not-be-exported"
        val events=JSONArray()
            .put(row("COCKPIT_ORDER_RESPONSE",now-10_000L,"UNCONFIRMED_OR_REJECTED",
                "FUTURES_ENTRY_CONTEXT_REJECTED: FUTURES_HALF_MARGIN_UNKNOWN:FOREIGN_ASSET_IDENTITY $privateId"))
            .put(row("COCKPIT_ORDER_RESPONSE",now-20_000L,"UNCONFIRMED_OR_REJECTED",
                "FUTURES_HALF_MARGIN_UNKNOWN:FOREIGN_ASSET_IDENTITY"))
            .put(row("COCKPIT_ORDER_RESPONSE",now-30_000L,"UNCONFIRMED_OR_REJECTED",
                "FUTURES_LEVERAGE_STOP_BUFFER: isolated margin cannot cover structural stop"))
            .put(row("COCKPIT_RECONCILIATION",now-40_000L,"RECONCILE_REQUIRED",
                "POSITION_MANAGEMENT_RECONCILIATION; ok=false; Futures server clock probe failed; signed request blocked",
                ""))
            .put(row("COCKPIT_EXECUTION",now-50_000L,"ENTRY_PENDING",
                "order_status=SUBMISSION_UNKNOWN; protection_status=NOT_SUBMITTED"))
            .put(row("COCKPIT_ORDER_RESPONSE",now+1_000L,"UNCONFIRMED_OR_REJECTED",
                "FUTURES_HALF_MARGIN_UNKNOWN:FOREIGN_ASSET_IDENTITY"))
            .put(row("COCKPIT_ORDER_RESPONSE",now-86_400_001L,"UNCONFIRMED_OR_REJECTED",
                "FUTURES_HALF_MARGIN_UNKNOWN:FOREIGN_ASSET_IDENTITY"))
        val report=WilliamsEntryFailureForensics.summarize(events,now)
        assertEquals(3,report.getInt("rejected_order_response_count_24h"))
        assertEquals(1,report.getInt("clock_reconciliation_failure_count_24h"))
        assertEquals(1,report.getInt("submission_unknown_observation_count_24h"))
        assertEquals(2,report.getJSONObject("occurrence_counts").getInt("FOREIGN_ASSET_IDENTITY"))
        assertEquals(1,report.getJSONObject("occurrence_counts").getInt("ISOLATED_LEVERAGE_STOP_BUFFER"))
        assertEquals(3,report.getJSONArray("representative_failures").length())
        assertEquals(now-10_000L,report.getLong("latest_rejected_order_response_at_ms"))
        assertFalse(report.getBoolean("captures_current_execution_gate"))
        assertFalse(report.getBoolean("can_place_or_retry_orders"))
        assertTrue(report.getBoolean("history_may_be_truncated"))
        assertFalse(report.toString().contains(privateId))
    }

    @Test fun oldPhoneRejectionsCannotBeMisreportedAsCurrentVersionFailures() {
        val now = 180_000_000L
        val update = now - 90_000L
        val events = JSONArray()
            .put(row("COCKPIT_ORDER_RESPONSE", update - 1000L, "UNCONFIRMED_OR_REJECTED",
                "FUTURES_HALF_MARGIN_UNKNOWN:FOREIGN_ASSET_IDENTITY"))
            .put(row("COCKPIT_RECONCILIATION", update - 2000L, "RECONCILE_REQUIRED",
                "ok=false; Futures server clock probe failed", ""))
            .put(row("COCKPIT_ORDER_RESPONSE", update + 30_000L, "UNCONFIRMED_OR_REJECTED",
                "FUTURES_LEVERAGE_STOP_BUFFER"))
            .put(row("COCKPIT_EXECUTION", update + 31_000L, "ENTRY_PENDING",
                "SUBMISSION_UNKNOWN"))
        val lifecycle = JSONArray()
            .put(JSONObject().put("code", "PACKAGE_REPLACED_OBSERVED")
                .put("at_ms", update - 5_000L))
            .put(JSONObject().put("code", "PACKAGE_REPLACED_OBSERVED")
                .put("at_ms", update))
        val seen = WilliamsEntryFailureForensics.lastObservedPackageUpdateAt(lifecycle, now)
        assertEquals(update, seen)
        val result = WilliamsEntryFailureForensics.summarize(events, now, seen)
        assertEquals(2, result.getInt("rejected_order_response_count_24h"))
        assertEquals(1, result.getInt("rejected_order_response_count_since_package_update"))
        assertEquals(0, result.getInt("clock_failure_count_since_package_update"))
        assertEquals(1, result.getInt("submission_unknown_count_since_package_update"))
        assertTrue(result.getBoolean("post_package_update_counts_available"))
        assertFalse(result.getBoolean("latest_rejection_predates_package_update"))
        val beforeOnly = WilliamsEntryFailureForensics.summarize(
            JSONArray().put(row("COCKPIT_ORDER_RESPONSE", update - 1000L,
                "UNCONFIRMED_OR_REJECTED", "FUTURES_HALF_MARGIN_UNKNOWN")), now, update)
        assertEquals(0, beforeOnly.getInt("rejected_order_response_count_since_package_update"))
        assertTrue(beforeOnly.getBoolean("latest_rejection_predates_package_update"))
        assertEquals(1, beforeOnly.getInt("rejected_order_response_count_24h"))
    }

    @Test fun absentUpdateIsNotFakeNoFailuresSinceUpdate() {
        val data = WilliamsEntryFailureForensics.summarize(JSONArray(), 180_000_000L)
        assertFalse(data.getBoolean("post_package_update_counts_available"))
        assertTrue(data.isNull("rejected_order_response_count_since_package_update"))
        assertEquals(0, WilliamsEntryFailureForensics.lastObservedPackageUpdateAt(
            JSONArray().put(JSONObject().put("code", "BOOT_COMPLETED_OBSERVED")
                .put("at_ms", 180_000_000L)), 180_000_000L))
    }

    @Test fun noRetainedFailuresDoesNotBecomeFalseExchangeSuccess() {
        val report=WilliamsEntryFailureForensics.summarize(JSONArray(),180_000_000L)
        assertEquals(0,report.getInt("rejected_order_response_count_24h"))
        assertTrue(report.getString("interpretation").contains("NOT proof"))
        assertFalse(report.getBoolean("can_place_or_retry_orders"))
    }
}
