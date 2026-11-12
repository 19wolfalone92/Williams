package com.williamsbot

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Diagnostic #14 (2026-11-12): signed STOP supervision must wake before a busy UI/event loop. */
class PrivateFuturesFillUrgencyTest {
    private fun order(status: String, qty: String, symbol: String = "BNBUSDT"): JSONObject =
        JSONObject().put("e", "ORDER_TRADE_UPDATE")
            .put("o", JSONObject().put("s", symbol).put("X", status).put("z", qty))

    @Test fun realFilledAndPartiallyFilledPositionsWakeSignedReconciliation() {
        assertTrue(privateFuturesFillDemandsImmediateReconcile(order("FILLED", "0.08")))
        assertTrue(privateFuturesFillDemandsImmediateReconcile(order("PARTIALLY_FILLED", "0.001")))
        assertTrue(privateFuturesFillDemandsImmediateReconcile(order("FILLED", "1e-8")))
    }

    @Test fun pendingOrdersAndZeroQuantityDoNotGenerateExtraSignedRestPasses() {
        assertFalse(privateFuturesFillDemandsImmediateReconcile(order("NEW", "0")))
        assertFalse(privateFuturesFillDemandsImmediateReconcile(order("TRIGGERING", "0")))
        assertFalse(privateFuturesFillDemandsImmediateReconcile(order("FILLED", "0")))
        assertFalse(privateFuturesFillDemandsImmediateReconcile(order("PARTIALLY_FILLED", "-1")))
    }

    @Test fun brokenOrUnrelatedEventsNeverAuthorizeUrgentPath() {
        assertFalse(privateFuturesFillDemandsImmediateReconcile(order("FILLED", "NaN")))
        assertFalse(privateFuturesFillDemandsImmediateReconcile(order("FILLED", "0.08", "")))
        assertFalse(privateFuturesFillDemandsImmediateReconcile(
            JSONObject().put("e", "ALGO_UPDATE").put("o",
                JSONObject().put("s", "BNBUSDT").put("X", "FILLED").put("z", "0.08"))
        ))
        assertFalse(privateFuturesFillDemandsImmediateReconcile(JSONObject().put("e", "ORDER_TRADE_UPDATE")))
        assertFalse(privateFuturesFillDemandsImmediateReconcile(
            JSONObject().put("e", "ACCOUNT_UPDATE").put("a",
                JSONObject().put("P", org.json.JSONArray()))
        ))
    }
}
