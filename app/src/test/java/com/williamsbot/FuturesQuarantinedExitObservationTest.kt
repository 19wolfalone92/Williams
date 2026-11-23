package com.williamsbot

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FuturesQuarantinedExitObservationTest {
    private fun intent()=JSONObject()
        .put("symbol","ETHUSDT").put("direction","LONG")
        .put("intent_role","EXIT").put("kind","MARKET_REDUCE_ONLY")
        .put("client_id","W2X_ETHUSDT_970401a8").put("quantity","0.463")
        .put("exchange_status","SUBMISSION_UNKNOWN")
    private fun signed(status:String="FILLED",executed:String="0.463")=JSONObject()
        .put("symbol","ETHUSDT").put("clientOrderId","W2X_ETHUSDT_970401a8")
        .put("side","SELL").put("type","MARKET").put("positionSide","BOTH")
        .put("reduceOnly",true).put("origQty","0.463")
        .put("executedQty",executed).put("orderId","10290101")
        .put("status",status)

    @Test fun diag14UnknownExitCannotBeMarkedFlatJustBecauseSignedMarketExitFilled() {
        val local=intent()
        val receipt=verifiedQuarantinedSignedExit(local,signed())!!
        assertEquals("FILLED",receipt.signedStatus)
        assertEquals("0.463",receipt.executedQuantity)
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
            "origQty" to "0.462",
            "executedQty" to "0.464",
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
}
