package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeStopExitAttributionTest {
    private fun owner() = JSONObject()
        .put("symbol","LTCUSDT")
        .put("direction","LONG")
        .put("protection_client_id","W2PS_LTCUSDT_EXACT")
    private fun trade(buyer:Boolean,orderId:String,time:Long,qty:String) = JSONObject()
        .put("symbol","LTCUSDT").put("buyer",buyer)
        .put("orderId",orderId).put("time",time).put("qty",qty)
    private fun stopEvent(clientId:String="W2PS_LTCUSDT_EXACT",childId:String="988711") =
        JSONObject().put("e","ALGO_UPDATE").put("E",14500L)
            .put("o",JSONObject().put("s","LTCUSDT")
                .put("caid",clientId).put("aid",123456789L)
                .put("ai",childId).put("S","SELL")
                .put("o","STOP_MARKET").put("X","TRIGGERED"))
    private fun fills() = JSONArray()
        .put(trade(true,"87245",2000,"2.693"))
        .put(trade(false,"988711",14800,"1.100"))
        .put(trade(false,"988711",14900,"1.593"))
    @Test fun exactStopClientAndChildIdAndAllSignedClosingFillsProveCause() {
        val proof=verifiedNativeStopExitAttribution(owner(),fills(),
            JSONArray().put(stopEvent()),1000,15000)
        assertEquals("VERIFIED_OWNED_PROTECTIVE_STOP_FILLED",proof.getString("status"))
        assertEquals("VERIFIED_PROTECTIVE_STOP_FILLED",proof.getString("reason"))
        assertEquals("988711",proof.getString("child_exchange_order_id"))
        assertEquals("2.693",proof.getString("signed_closing_quantity"))
        assertEquals(2,proof.getInt("signed_closing_fill_count"))
        assertFalse(proof.getBoolean("can_place_orders"))
    }
    @Test fun priceAndTemporalClosenessAloneAreNeverStopProof() {
        val bad=JSONArray().put(stopEvent(clientId="MANUAL_STOP"))
        val proof=verifiedNativeStopExitAttribution(owner(),fills(),bad,1000,15000)
        assertEquals("NOT_VERIFIED",proof.getString("status"))
        assertEquals("OWNED_STOP_CHILD_ORDER_ID_NOT_PROVEN_BY_PRIVATE_WS",proof.getString("reason"))
    }
    @Test fun independentMarketExitOrExtraSignedOrderCannotBeAttributedToStop() {
        val other=JSONArray().put(trade(true,"87245",2000,"2.693"))
            .put(trade(false,"988711",14800,"1.100"))
            .put(trade(false,"988712",14900,"1.593"))
        val proof=verifiedNativeStopExitAttribution(owner(),other,
            JSONArray().put(stopEvent()),1000,15000)
        assertEquals("MULTIPLE_SIGNED_EXIT_ORDERS_CANNOT_ATTRIBUTE_SINGLE_STOP",
            proof.getString("reason"))
    }
    @Test fun unfinishedNativeStopSwapOrMissingPrivateEventsRemainUnknown() {
        val swapped=owner().put("previous_protection_client_id","W2PS_OLD")
        assertEquals("NOT_VERIFIED",
            verifiedNativeStopExitAttribution(swapped,fills(),
                JSONArray().put(stopEvent()),1000,15000).getString("status"))
        assertEquals("NOT_VERIFIED",
            verifiedNativeStopExitAttribution(owner(),fills(),
                JSONArray(),1000,15000).getString("status"))
    }
    @Test fun wrongChildOrDirectionOrLateStreamEventDoesNotProveStop() {
        for (event in listOf(stopEvent(childId="988712"),
            stopEvent().apply { getJSONObject("o").put("S","BUY") },
            stopEvent().apply { put("E",500000) },
            stopEvent().apply { getJSONObject("o").put("o","TAKE_PROFIT_MARKET") })) {
            val proof=verifiedNativeStopExitAttribution(owner(),fills(),JSONArray().put(event),
                1000,15000)
            assertEquals("NOT_VERIFIED",proof.getString("status"))
        }
    }
}
