package com.williamsbot

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FuturesSignedEntryOrderIdentityTest {
    @Test fun absentOrConflictingSignedOneWayModeCannotConfirmTriggeredEntry() {
        for(side in listOf("BUY","SELL")) {
            val child=JSONObject().put("symbol","BTCUSDT")
                .put("side",side).put("positionSide","BOTH")
                .put("clientOrderId","W2E_ACTUAL_CHILD")
            assertTrue(signedFuturesEntryOrderIdentityMatches(child,"BTCUSDT",side))
            val missing=JSONObject(child.toString()).apply { remove("positionSide") }
            assertFalse("Missing mode must not default to BOTH",
                signedFuturesEntryOrderIdentityMatches(missing,"BTCUSDT",side))
            assertFalse(signedFuturesEntryOrderIdentityMatches(
                JSONObject(child.toString()).put("positionSide","LONG"),
                "BTCUSDT",side))
            assertFalse(signedFuturesEntryOrderIdentityMatches(
                JSONObject(child.toString()).put("symbol","ETHUSDT"),
                "BTCUSDT",side))
            assertFalse(signedFuturesEntryOrderIdentityMatches(
                JSONObject(child.toString()).put("clientOrderId",""),
                "BTCUSDT",side))
            assertFalse(signedFuturesEntryOrderIdentityMatches(
                child,"BTCUSDT",if(side=="BUY") "SELL" else "BUY"))
        }
    }
}
