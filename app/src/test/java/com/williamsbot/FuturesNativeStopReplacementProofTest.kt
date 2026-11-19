package com.williamsbot

import java.math.BigDecimal
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FuturesNativeStopReplacementProofTest {
    private fun stop(direction:String, qty:String?=null) = JSONObject()
        .put("symbol","BTCUSDT").put("clientAlgoId","W2PS_TEST")
        .put("algoId","9001").put("algoStatus","NEW")
        .put("positionSide","BOTH").put("workingType","MARK_PRICE")
        .put("priceProtect",false)
        .put("orderType","STOP_MARKET")
        .put("side",if(direction=="LONG") "SELL" else "BUY")
        .put("triggerPrice",if(direction=="LONG") "95" else "105")
        .put("actualOrderId","")
        .put("closePosition",qty==null).apply {
            if(qty!=null) { put("quantity",qty);put("reduceOnly",true) }
        }

    @Test fun missingSignedOneWayModeNeverAuthorizesBridgeOrFinalStop() {
        for(direction in listOf("LONG","SHORT")) {
            val stopPrice=if(direction=="LONG") "95" else "105"
            for(qty in listOf<String?>(null,"1.0")) {
                val original=stop(direction,qty)
                val amount=qty?.toBigDecimal()
                assertTrue(nativeReplacementStopMatchesSignedExchangeRow(
                    original,"BTCUSDT",direction,"W2PS_TEST",stopPrice,amount))
                for(field in listOf("positionSide","workingType","algoId",
                    "clientAlgoId","symbol","side","triggerPrice","priceProtect")) {
                    val missing=JSONObject(original.toString()).apply { remove(field) }
                    assertFalse("$direction: missing signed $field",
                        nativeReplacementStopMatchesSignedExchangeRow(
                            missing,"BTCUSDT",direction,"W2PS_TEST",stopPrice,amount))
                }
                assertFalse(nativeReplacementStopMatchesSignedExchangeRow(
                    JSONObject(original.toString()).put("positionSide","LONG"),
                    "BTCUSDT",direction,"W2PS_TEST",stopPrice,amount))
                assertFalse(nativeReplacementStopMatchesSignedExchangeRow(
                    JSONObject(original.toString()).put("workingType","CONTRACT_PRICE"),
                    "BTCUSDT",direction,"W2PS_TEST",stopPrice,amount))
                assertFalse(nativeReplacementStopMatchesSignedExchangeRow(
                    JSONObject(original.toString()).put("priceProtect",true),
                    "BTCUSDT",direction,"W2PS_TEST",stopPrice,amount))
                if(qty!=null) assertFalse(nativeReplacementStopMatchesSignedExchangeRow(
                    JSONObject(original.toString()).put("quantity","0.5"),
                    "BTCUSDT",direction,"W2PS_TEST",stopPrice,BigDecimal("1.0")))
            }
        }
    }

    @Test fun oldTerminalStopWithMissingSignedModeCannotAuthorizeSafetyExit() {
        for(direction in listOf("LONG","SHORT")) {
            val price=if(direction=="LONG") "95" else "105"
            val prior=stop(direction).put("algoStatus","CANCELED")
            assertTrue(matchesTerminalUntriggeredFuturesProtectiveStop(
                prior,"BTCUSDT",direction,"W2PS_TEST",price))
            assertFalse(matchesTerminalUntriggeredFuturesProtectiveStop(
                JSONObject(prior.toString()).apply { remove("positionSide") },
                "BTCUSDT",direction,"W2PS_TEST",price))
            assertFalse(matchesTerminalUntriggeredFuturesProtectiveStop(
                JSONObject(prior.toString()).put("actualOrderId","99"),
                "BTCUSDT",direction,"W2PS_TEST",price))
        }
    }

    @Test fun missingModeInSignedPositionSnapshotMustNeverDefaultToBoth() {
        val valid=JSONObject().put("symbol","BTCUSDT")
            .put("positionAmt","1").put("positionSide","BOTH")
        assertEquals(1,validateFuturesPositionSnapshot(
            JSONArray().put(valid)).length())
        try {
            validateFuturesPositionSnapshot(JSONArray()
                .put(JSONObject(valid.toString()).apply { remove("positionSide") }))
            fail("Missing signed positionSide was accepted as BOTH")
        } catch(expected:IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("Malformed"))
        }
    }
}
