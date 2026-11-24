package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * DIAG18: a negative, exact reduce-only MARKET POST receipt must not poison
 * all unrelated pending-entry observation. No entry or STOP mutation is
 * authorized by this pure predicate; the coordinator repeats signed gates.
 */
class DefinitiveReduceOnlyExitRejectionTest {
    private fun pending(): JSONObject = JSONObject()
        .put("symbol","BTCUSDT").put("intent_role","ENTRY")
        .put("campaign_status","ENTRY_PENDING").put("order_status","NEW")
        .put("client_id","W2H_BTCUSDT_confirmed")

    private fun rejected(): JSONObject = JSONObject()
        .put("symbol","ETHUSDT").put("direction","LONG")
        .put("intent_role","EXIT").put("kind","MARKET_REDUCE_ONLY")
        .put("quantity","0.463").put("client_id","W2X_ETHUSDT_rejected")
        .put("exchange_status","EXIT_TERMINAL_POSITION_OPEN")
        .put("order_status","REJECTED")
        .put("rejection_http_status",400)
        .put("exchange_rejection_code",-2022)
        .put("rejection_before_post_receipt",true)
        .put("rejected_at_ms",1_800_000_000_000L)

    @Test fun definitiveNegativePostIsTerminalNotAnAmbiguousExit() {
        assertTrue(confirmedPendingEntriesAllowConcurrentAdmission(
            JSONArray().put(pending()).put(rejected())))
        assertFalse(confirmedPendingEntriesAllowConcurrentAdmission(
            JSONArray().put(pending()).put(rejected().put("exchange_rejection_code",-2019))))
        assertFalse(confirmedPendingEntriesAllowConcurrentAdmission(
            JSONArray().put(pending()).put(rejected().put("exchange_status","SUBMISSION_UNKNOWN"))))
        assertFalse(confirmedPendingEntriesAllowConcurrentAdmission(
            JSONArray().put(pending().put("order_status","SUBMISSION_UNKNOWN")).put(rejected())))
        // No existing pending ENTRY means no concurrent-entry admission.
        assertFalse(confirmedPendingEntriesAllowConcurrentAdmission(JSONArray().put(rejected())))
    }
}
