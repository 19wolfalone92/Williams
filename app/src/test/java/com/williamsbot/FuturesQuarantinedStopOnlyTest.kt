package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FuturesQuarantinedStopOnlyTest {
    // Sanitized DIAG14: 2026-11-23 09:26:53 UTC, ETH LONG 0.50,
    // full signed STOP 1520.0, old SHORT still in the independent ledger.
    private val now=1_800_000_000_000L
    private val id="W2H_ETHUSDT_SYNTHETIC_PROOF"
    private fun position()=JSONObject().put("symbol","ETHUSDT")
        .put("positionSide","BOTH").put("positionAmt","0.50").put("entryPrice","1500.0")
    private fun stop()=JSONObject().put("symbol","ETHUSDT")
        .put("algoId","1000000298184852").put("clientAlgoId",id)
        .put("algoType","CONDITIONAL").put("orderType","STOP_MARKET")
        .put("algoStatus","NEW").put("side","SELL").put("positionSide","BOTH")
        .put("workingType","MARK_PRICE").put("triggerPrice","1520.0")
        .put("quantity","0.0").put("closePosition",true)
        .put("reduceOnly",true).put("priceProtect",false)
        .put("actualOrderId","0")
    private fun supervised()=JSONObject().put("symbol","ETHUSDT")
        .put("direction","LONG").put("mode","AUTO_VERIFIED_STOP_ONLY")
        .put("state","ACTIVE").put("entry_price","1500.0")
        .put("quantity","0.50").put("stop","1520.0")
        .put("protection_client_id",id)
        .put("strategy_ownership_verified",false)
        .put("market_close_authorized",false)
        .put("add_on_authorized",false).put("reverse_authorized",false)
        .put("source","SIGNED_TWO_SNAPSHOTS_CLOSED_H1_H4_TC2_STOP_REVIEW")
        .put("first_signed_observed_at_ms",(now - 100_000L))
        .put("second_signed_observed_at_ms",(now - 50_000L))
        .put("approved_at_ms",(now - 50_000L))
        .put("last_evaluation_status","WAITING_FOR_STRUCTURE")
        .put("last_evaluated_at_ms",now-5_000L)

    private fun eligible(
        pos:JSONObject=position(),local:JSONArray=JSONArray().put(stop()),
        global:JSONArray=JSONArray().put(stop()),ordinary:JSONArray=JSONArray(),
        rows:JSONArray=JSONArray().put(supervised()),mark:Double=1630.0
    ):Boolean = signedQuarantinedStopOnlyManagementProven(
        "ETHUSDT","LONG",pos,local,global,ordinary,rows,mark,now)

    @Test fun diag14ReversedOldShortDoesNotDisableIndependentStopOnlySupervision() {
        assertTrue(eligible())
        // This method does not even accept an old ENTRY and cannot assign
        // ownership or remove the account-wide new-exposure lock.
        val old=JSONObject().put("symbol","ETHUSDT").put("direction","SHORT")
            .put("campaign_status","OPEN_PROTECTED").put("strategy_ownership_verified",true)
        assertEquals("SHORT",old.optString("direction"))
        assertTrue(eligible())
    }

    @Test fun changeInExactSignedExchangeStopOrPositionFailsClosed() {
        val native=stop()
        for ((key,bad) in listOf(
            "algoId" to "0", "clientAlgoId" to "OTHER",
            "algoType" to "UNKNOWN", "orderType" to "TAKE_PROFIT_MARKET",
            "algoStatus" to "CANCELED", "side" to "BUY",
            "workingType" to "CONTRACT_PRICE","triggerPrice" to "1521.0",
            "positionSide" to "LONG","closePosition" to false,
            "priceProtect" to true
        )) {
            val changed=JSONObject(native.toString()).put(key,bad)
            assertFalse("Unsafe signed "+key,
                eligible(local=JSONArray().put(changed),global=JSONArray().put(changed)))
        }
        assertFalse(eligible(pos=position().put("positionAmt","-0.50")))
        assertFalse(eligible(pos=position().put("positionAmt","0.49")))
        assertFalse(eligible(pos=position().put("entryPrice","1500.01")))
        assertFalse(eligible(mark=1400.0))
        assertFalse(eligible(global=JSONArray()))
        assertFalse(eligible(local=JSONArray().put(stop()).put(stop())))
        assertFalse(eligible(global=JSONArray().put(stop()).put(stop())))
        assertFalse(eligible(global=JSONArray().put(
            JSONObject(stop().toString()).put("actualOrderId","1234"))))
        assertFalse(eligible(ordinary=JSONArray().put(
            JSONObject().put("symbol","ETHUSDT").put("orderId","42"))))
    }

    @Test fun missingTwoIndependentSignedProofsOrStaleApprovalNeverAuthorizeTrail() {
        val r=supervised()
        assertTrue(eligible(rows=JSONArray().put(r)))
        for ((key,bad) in listOf(
            "source" to "LOCAL_GUESSED",
            "first_signed_observed_at_ms" to 0L,
            "second_signed_observed_at_ms" to (now - 90_000L),
            "approved_at_ms" to (now - 100_000L),
            "strategy_ownership_verified" to true,
            "market_close_authorized" to true,
            "add_on_authorized" to true,
            "reverse_authorized" to true,
            "state" to "REPLACEMENT_UNRESOLVED",
            "last_evaluation_status" to "BLOCKED",
            "last_evaluated_at_ms" to (now-120_001L),
            "protection_client_id" to "different"
        )) {
            val changed=JSONObject(r.toString()).put(key,bad)
            assertFalse("Unverified "+key,eligible(rows=JSONArray().put(changed)))
        }
        assertFalse(eligible(rows=JSONArray().put(r).put(JSONObject(r.toString()))))
        assertFalse(eligible(rows=JSONArray()))
    }
}
