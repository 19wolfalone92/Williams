package com.williamsbot

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FuturesQuarantinedExitObservationTest {
    private fun intent()=JSONObject()
        .put("symbol","ETHUSDT").put("direction","LONG")
        .put("intent_role","EXIT").put("kind","MARKET_REDUCE_ONLY")
        .put("client_id","W2X_ETHUSDT_SYNTHETIC").put("quantity","0.50")
        .put("exchange_status","SUBMISSION_UNKNOWN")
    private fun signed(status:String="FILLED",executed:String="0.50")=JSONObject()
        .put("symbol","ETHUSDT").put("clientOrderId","W2X_ETHUSDT_SYNTHETIC")
        .put("side","SELL").put("type","MARKET").put("positionSide","BOTH")
        .put("reduceOnly",true).put("origQty","0.50")
        .put("executedQty",executed).put("orderId","10290101")
        .put("status",status)

    @Test fun diag14UnknownExitCannotBeMarkedFlatJustBecauseSignedMarketExitFilled() {
        val local=intent()
        val receipt=verifiedQuarantinedSignedExit(local,signed())!!
        assertEquals("FILLED",receipt.signedStatus)
        assertEquals("0.50",receipt.executedQuantity)
        assertFalse(receipt.stillActive)
        // The side/position remains an external fact; no ledger mutation
        // occurs in this pure observation function.
        assertEquals("SUBMISSION_UNKNOWN",local.optString("exchange_status"))
        assertNull(verifiedQuarantinedSignedExit(local,null))
        assertEquals("SUBMISSION_UNKNOWN",local.optString("exchange_status"))
    }

    @Test fun everyNonTerminalSignedStatusRequiresFurtherReconciliation() {
        for(s in listOf("NEW","PARTIALLY_FILLED","PENDING_CANCEL"))
            assertTrue(s,verifiedQuarantinedSignedExit(intent(),signed(s,"0.123"))!!.stillActive)
        for(s in listOf("FILLED","CANCELED","EXPIRED","EXPIRED_IN_MATCH","REJECTED"))
            assertFalse(s,verifiedQuarantinedSignedExit(intent(),signed(s,"0.123"))!!.stillActive)
        assertThrows(IllegalStateException::class.java) {
            verifiedQuarantinedSignedExit(intent(),signed("UNKNOWN_EXCHANGE_STATUS"))
        }
    }

    @Test fun wrongExchangeIdentityNeverResolvesUnknownExit() {
        val valid=signed()
        for((name,bad) in listOf(
            "symbol" to "BTCUSDT",
            "clientOrderId" to "UNRELATED",
            "side" to "BUY",
            "positionSide" to "LONG",
            "type" to "LIMIT",
            "reduceOnly" to false,
            "origQty" to "0.49",
            "executedQty" to "0.51",
            "orderId" to "0"
        )) {
            val changed=JSONObject(valid.toString()).put(name,bad)
            assertThrows(name,IllegalStateException::class.java) {
                verifiedQuarantinedSignedExit(intent(),changed)
            }
        }
        assertNull(verifiedQuarantinedSignedExit(
            intent().put("kind","STOP"),valid))
        assertNull(verifiedQuarantinedSignedExit(
            intent().put("intent_role","ENTRY"),valid))
    }

    @Test fun diag16UnknownExitNeverAbortsSignedStopSweepOrReplaysSubmission() {
        // Diagnostic #16: exact EXIT clientOrderId is NOT_FOUND, signed ETH
        // LONG still exists, historical SHORT exit ownership is unresolved.
        for (live in listOf(false, true))
            for (standard in listOf(false, true))
                for (conditional in listOf(false, true)) {
                    val result = classifyUnqueryableFuturesExit(live, standard, conditional)
                    val conflict = live || standard || conditional
                    assertEquals(conflict, result.blocksNewRisk)
                    assertEquals(!conflict, result.flatSnapshotOnly)
                    assertFalse("No signed non-placement receipt permits a second EXIT POST",
                        result.mayRetrySubmission)
                }
        val unknown = intent()
        val disposition = classifyUnqueryableFuturesExit(true, false, false)
        assertTrue(disposition.blocksNewRisk)
        assertFalse(disposition.flatSnapshotOnly)
        // Pure classification must leave both signed proof and durable intent
        // untouched: the coordinator may only add quarantine audit fields.
        assertEquals("SUBMISSION_UNKNOWN", unknown.optString("exchange_status"))
    }
}
