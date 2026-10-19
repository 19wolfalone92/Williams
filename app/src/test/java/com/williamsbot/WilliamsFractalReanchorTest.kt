package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Safety regression: level replacement is a proposal, never an exchange mutation. */
class WilliamsFractalReanchorTest {
    private val now=1_800_000_000_000L
    private fun pending(side:String, price:Double) = JSONObject()
        .put("intent_role","ENTRY").put("kind","STOP").put("campaign_status","ENTRY_PENDING")
        .put("order_status","NEW").put("exchange_status","NEW")
        .put("direction",side).put("strategy_profile",williamsTc2Profile)
        .put("signal_type",if(side=="LONG") "FRACTAL" else "SHORT_FRACTAL")
        .put("signal_id","old").put("signal_bar_time_ms",now-9_000_000L)
        .put("entry_price",price.toString()).put("client_id","old-client")
    private fun signal(side:String,trigger:Double,stop:Double)=JSONObject()
        .put("signal_id","new-h1-fractal").put("type",if(side=="LONG") "FRACTAL" else "SHORT_FRACTAL")
        .put("role","ENTRY").put("strategy_profile",williamsTc2Profile)
        .put("execution_semantics","STOP_ENTRY")
        .put("signal_bar_time_ms",now-4_000_000L)
        .put("formation_at_ms",now-11_200_000L)
        .put("trigger_price",trigger).put("protective_price",stop)
    private fun find(p:JSONObject,s:JSONObject,mark:Double)=
        confirmedBetterFractal(p,JSONArray().put(s),mark,0.1,now)

    @Test fun lowerConfirmedLongFractalCanSupersedeHigherUnfilledLongEntry() {
        val result=find(pending("LONG",105.0),signal("LONG",101.0,96.0),98.0)
        assertEquals("new-h1-fractal",requireNotNull(result).getString("signal_id"))
        assertEquals(101.0,result.getDouble("trigger_price"),0.0)
    }
    @Test fun higherConfirmedShortFractalCanSupersedeLowerUnfilledShortEntry() {
        assertNotNull(find(pending("SHORT",95.0),signal("SHORT",97.0,105.0),100.0))
    }
    @Test fun neverMoveLevelAwayOrBehindPrice() {
        assertNull(find(pending("LONG",105.0),signal("LONG",106.0,96.0),98.0))
        assertNull(find(pending("LONG",105.0),signal("LONG",97.0,96.0),98.0))
        assertNull(find(pending("SHORT",95.0),signal("SHORT",94.0,105.0),100.0))
        assertNull(find(pending("SHORT",95.0),signal("SHORT",101.0,105.0),100.0))
    }
    @Test fun incompleteOrLookAheadFractalNeverPasses() {
        val unconfirmed=signal("LONG",101.0,96.0).put("signal_bar_time_ms",now-500L)
        assertNull(find(pending("LONG",105.0),unconfirmed,98.0))
        val noFractalCenter=signal("LONG",101.0,96.0).put("formation_at_ms",now-500L)
        assertNull(find(pending("LONG",105.0),noFractalCenter,98.0))
        val expired=signal("LONG",101.0,96.0).put("signal_bar_time_ms",now-20_000_000L)
        assertNull(find(pending("LONG",105.0),expired,98.0))
    }
    @Test fun missingConfirmationOrWrongSourceOrSideNeverPasses() {
        assertNull(find(pending("LONG",105.0),signal("LONG",101.0,96.0).put("strategy_profile",williamsNtdProfile),98.0))
        assertNull(find(pending("LONG",105.0),signal("SHORT",101.0,110.0),98.0))
        assertNull(find(pending("LONG",105.0),signal("LONG",101.0,110.0),98.0))
        assertNull(find(pending("LONG",105.0),signal("LONG",101.0,96.0).put("signal_id","old"),98.0))
    }
    @Test fun ambiguousOrFilledOrdersCannotBeRepriced() {
        for (status in listOf("SUBMISSION_UNKNOWN","CANCEL_SUBMISSION_UNKNOWN","PARTIALLY_FILLED","TRIGGERED","FILLED")) {
            assertNull(status,find(pending("LONG",105.0).put("order_status",status)
                .put("exchange_status",status),signal("LONG",101.0,96.0),98.0))
        }
        assertNull(find(pending("LONG",105.0).put("kind","MARKET"),signal("LONG",101.0,96.0),98.0))
        assertNull(find(pending("LONG",105.0).put("campaign_status","OPEN_PROTECTED"),signal("LONG",101.0,96.0),98.0))
        assertNull(find(pending("LONG",105.0).put("intent_role","ADD_ON"),signal("LONG",101.0,96.0),98.0))
    }
}
