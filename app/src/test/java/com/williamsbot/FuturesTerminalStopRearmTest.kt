package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Terminal-only STOP recovery: no unknown/duplicate POST may bypass exchange proof. */
class FuturesTerminalStopRearmTest {
    private val now=1_800_000_000_000L

    private fun owner(direction:String)=JSONObject()
        .put("symbol","ETHUSDT").put("direction",direction)
        .put("intent_role","ENTRY").put("campaign_status","OPEN_PROTECTED")
        .put("protection_status","SUBMISSION_UNKNOWN")
        .put("strategy_ownership_verified",true)
        .put("client_id","W2E_ETHUSDT_verified_entry")
        .put("protection_client_id","W2PS_ETHUSDT_before")
        .put("protective_stop","1648.34")
        .put("confirmed_position_qty","0.463")
        .put("actual_entry_price","1641.32")
        .put("protection_attempt_at_ms",now-60_000L)

    private fun stop(direction:String)=JSONObject()
        .put("symbol","ETHUSDT").put("clientAlgoId","W2PS_ETHUSDT_before")
        .put("algoId","1000000297658142")
        .put("orderType","STOP_MARKET")
        .put("side",if(direction=="LONG") "SELL" else "BUY")
        .put("positionSide","BOTH").put("workingType","MARK_PRICE")
        .put("closePosition",true).put("triggerPrice","1648.34")
        .put("algoStatus","CANCELED").put("actualOrderId","0")

    private fun positions(direction:String):JSONArray=JSONArray().put(JSONObject()
        .put("symbol","ETHUSDT").put("positionSide","BOTH")
        .put("positionAmt",if(direction=="LONG") "0.463" else "-0.463")
        .put("entryPrice","1641.32").put("leverage","5").put("marginType","isolated"))

    private fun blocker(
        direction:String,owner:JSONObject=owner(direction),former:JSONObject?=stop(direction),
        first:JSONArray=positions(direction),second:JSONArray?=positions(direction),
        symbolAlgos:JSONArray=JSONArray(),accountAlgos:JSONArray?=JSONArray(),
        symbolOrders:JSONArray?=JSONArray(),accountOrders:JSONArray?=JSONArray(),
        mark:Double=if(direction=="LONG") 1712.46 else 1600.0,
        at:Long=now
    ) = futuresTerminalStopRearmBlocker(owner,former,first,second,
        symbolAlgos,accountAlgos,symbolOrders,accountOrders,"ETHUSDT",direction,mark,at)

    @Test fun terminalStopWithStableSignedEvidenceMayPrepareFreshStopForBothDirections() {
        for(direction in listOf("LONG","SHORT")) {
            assertNull("$direction: correct terminal signed proof",blocker(direction))
            val id=terminalStopRearmClientId("ETHUSDT","W2E_ETHUSDT_verified_entry",1)
            assertTrue(id.startsWith("W2R_ETHUSDT_"))
            assertTrue(id.length<=36)
            assertNotEquals(id,terminalStopRearmClientId(
                "ETHUSDT","W2E_ETHUSDT_verified_entry",2))
            assertEquals(id,terminalStopRearmClientId(
                "ETHUSDT","W2E_ETHUSDT_verified_entry",1))
        }
    }

    @Test fun unknownOrTriggeredStopNeverGrantsMutationAuthority() {
        for(direction in listOf("LONG","SHORT")) {
            assertNotNull(blocker(direction,former=null))
            assertNotNull(blocker(direction,former=stop(direction).put("algoStatus","NEW")))
            assertNotNull(blocker(direction,former=stop(direction).put("actualOrderId","888")))
            assertNotNull(blocker(direction,former=stop(direction).remove("actualOrderId")))
            assertNotNull(blocker(direction,former=stop(direction).put("clientAlgoId","other")))
            assertNotNull(blocker(direction,former=stop(direction).put("side",
                if(direction=="LONG") "BUY" else "SELL")))
            assertNotNull(blocker(direction,former=stop(direction).put("triggerPrice","1648.35")))
        }
    }

    @Test fun anyOpenOrUnknownExchangeOrderBlocksRearm() {
        for(direction in listOf("LONG","SHORT")) {
            val foreign=JSONObject().put("symbol","ETHUSDT").put("algoId",999L)
            assertNotNull(blocker(direction,symbolAlgos=JSONArray().put(foreign)))
            assertNotNull(blocker(direction,accountAlgos=JSONArray().put(foreign)))
            assertNotNull(blocker(direction,symbolOrders=JSONArray().put(foreign)))
            assertNotNull(blocker(direction,accountOrders=JSONArray().put(foreign)))
            assertNotNull(blocker(direction,accountOrders=null))
            assertNotNull(blocker(direction,symbolAlgos=JSONArray().put(JSONObject())))
            // Independent positions/orders in other symbols are allowed.
            assertNull(blocker(direction,accountOrders=JSONArray().put(
                JSONObject().put("symbol","BTCUSDT"))))
        }
    }

    @Test fun driftStaleAndOwnershipConflictsBlockRearm() {
        for(direction in listOf("LONG","SHORT")) {
            assertNotNull(blocker(direction,second=null))
            assertNotNull(blocker(direction,second=positions(direction).apply {
                getJSONObject(0).put("positionAmt",if(direction=="LONG") "0.462" else "-0.462")
            }))
            assertNotNull(blocker(direction,second=positions(direction).apply {
                getJSONObject(0).put("entryPrice","1641.35")
            }))
            assertNotNull(blocker(direction,owner=owner(direction).put("strategy_ownership_verified",false)))
            assertNotNull(blocker(direction,owner=owner(direction).put("previous_protection_client_id","other")))
            assertNotNull(blocker(direction,owner=owner(direction).put("protection_status","NOT_SUBMITTED")))
            assertNotNull(blocker(direction,owner=owner(direction).put("campaign_status","CLOSED_OR_STOPPED")))
            assertEquals("TERMINAL_REARM_COOLDOWN",
                blocker(direction,owner=owner(direction).put("protection_attempt_at_ms",now-300L)))
            assertNotNull(blocker(direction,mark=if(direction=="LONG") 1640.0 else 1660.0))
            assertNotNull(blocker(direction,at=0L))
        }
    }
}
