package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Signed hints DO NOT grant financial mutation; handoff re-verifies Binance. */
class SignedManualHandoffHintsTest {
    private fun position(side:String="LONG", qty:String="0.463")=JSONObject()
        .put("symbol","ETHUSDT").put("positionSide","BOTH")
        .put("positionAmt",if(side=="LONG") qty else "-$qty")
        .put("entryPrice","1641.32")
    private fun manual(side:String="LONG",qty:String="0.463")=JSONObject()
        .put("symbol","ETHUSDT").put("algoId","77")
        .put("clientAlgoId","MANUAL_ETH_STOP_77")
        .put("algoType","CONDITIONAL").put("orderType","STOP_MARKET")
        .put("algoStatus","NEW").put("side",if(side=="LONG") "SELL" else "BUY")
        .put("positionSide","BOTH").put("workingType","CONTRACT_PRICE")
        .put("triggerPrice","1648.34").put("quantity",qty)
        .put("reduceOnly",true).put("closePosition",false)
        .put("priceProtect",true).put("actualOrderId","")

    @Test fun everyExchangeSignedLongAndShortPositionCanWakeItsManualHandoff() {
        for(side in listOf("LONG","SHORT")) {
            val positions=JSONArray().put(position(side))
                .put(JSONObject().put("symbol","BTCUSDT")
                    .put("positionSide","BOTH").put("positionAmt","0"))
            val result=signedManualPriceProtectedStopHandoffHints(positions,
                JSONArray().put(manual(side)))
            assertEquals(setOf("ETHUSDT"),result)
        }
    }

    @Test fun triggeredUnknownOrWrongQuantityStopCannotWakeAutomaticHandoff() {
        for(side in listOf("LONG","SHORT")) {
            val pos=JSONArray().put(position(side))
            val candidate=manual(side)
            val mutations=listOf(
                JSONObject(candidate.toString()).put("priceProtect",false),
                JSONObject(candidate.toString()).put("actualOrderId","987"),
                JSONObject(candidate.toString()).put("quantity","0.462"),
                JSONObject(candidate.toString()).put("side",if(side=="LONG") "BUY" else "SELL"),
                JSONObject(candidate.toString()).put("algoStatus","FINISHED"),
                JSONObject(candidate.toString()).put("workingType","MARK_PRICE"),
                JSONObject(candidate.toString()).apply { remove("actualOrderId") }
            )
            for(bad in mutations) {
                assertTrue("Must not wake false STOP hint: $bad",
                    signedManualPriceProtectedStopHandoffHints(pos,JSONArray().put(bad)).isEmpty())
            }
            assertTrue(signedManualPriceProtectedStopHandoffHints(
                pos,JSONArray().put(candidate).put(JSONObject(candidate.toString())
                    .put("algoId","88").put("clientAlgoId","EXTRA_STOP"))).isEmpty())
            assertTrue(signedManualPriceProtectedStopHandoffHints(
                JSONArray().put(position(side,"0")),JSONArray().put(candidate)).isEmpty())
        }
    }

    @Test fun anyMalformedAccountWideSignedRowSuppressesAllMutationHints() {
        assertTrue(signedManualPriceProtectedStopHandoffHints(
            JSONArray().put(position()).put(JSONObject()),
            JSONArray().put(manual())).isEmpty())
        assertTrue(signedManualPriceProtectedStopHandoffHints(
            JSONArray().put(position()),
            JSONArray().put(manual()).put(JSONObject())).isEmpty())
        assertTrue(signedManualPriceProtectedStopHandoffHints(
            JSONArray().put(position()).put(position()),JSONArray().put(manual())).isEmpty())
    }

    @Test fun triggeredChildIdentityChangesSignedOrderBookFingerprint() {
        val previous=manual()
        val child=JSONObject(previous.toString()).put("actualOrderId","9912")
        assertNotEquals(signedExternalContractStopFingerprint(previous),
            signedExternalContractStopFingerprint(child))
    }
}
