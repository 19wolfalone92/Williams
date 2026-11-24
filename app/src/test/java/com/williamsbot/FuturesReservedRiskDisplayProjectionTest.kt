package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** No change to signed risk admissions: new O(N) display totals match legacy. */
class FuturesReservedRiskDisplayProjectionTest {
    private fun entry(id:String,risk:Double,state:String="OPEN_PROTECTED") =
        JSONObject().put("intent_role","ENTRY").put("client_id",id)
            .put("risk_pct",risk).put("campaign_status",state)
    private fun add(id:String,risk:Double,status:String,filled:String="0") =
        JSONObject().put("intent_role","ADD_ON").put("entry_client_id",id)
            .put("risk_pct",risk).put("order_status",status)
            .put("executed_qty",filled)
    private fun legacy(rows:JSONArray):Double=(0 until rows.length()).sumOf { i ->
        val owner=rows.optJSONObject(i) ?: return@sumOf 0.0
        if(owner.optString("intent_role","ENTRY")=="ENTRY" && isEntryCampaignReserved(owner))
            futuresCampaignReservedRisk(owner,rows) else 0.0
    }
    @Test fun diag20OldShortAndRecoveredLongStillReserveUnchanged() {
        val rows=JSONArray().put(entry("oldShort",0.0004))
            .put(entry("recoveredLong",0.006,"POSITION_OPEN"))
            .put(JSONObject().put("intent_role","EXIT")
                .put("exchange_status","EXIT_TERMINAL_POSITION_OPEN"))
        assertEquals(0.0064, futuresCockpitTotalReservedRisk(rows),1e-12)
        assertEquals(legacy(rows),futuresCockpitTotalReservedRisk(rows),1e-12)
    }
    @Test fun pendingPartiallyFilledAndCanceledAddOnsMatchLegacy() {
        val rows=JSONArray().put(entry("BTC",0.001))
            .put(entry("ETH",0.002,"ENTRY_PENDING"))
            .put(entry("flat",0.004,"CLOSED_OR_STOPPED"))
            .put(add("BTC",0.0002,"NEW"))
            .put(add("BTC",0.0003,"FILLED","0.2"))
            .put(add("BTC",0.0004,"CANCELED","0"))
            .put(add("ETH",0.0001,"CANCELED","0.01"))
            .put(add("flat",0.0001,"NEW"))
        assertEquals(legacy(rows),futuresCockpitTotalReservedRisk(rows),1e-12)
    }
    @Test fun nullRiskKeepsConservativeFallbackAndOtherRolesCannotReserve() {
        val e=entry("unknown",0.001)
        e.put("risk_pct", JSONObject.NULL)
        val rows=JSONArray().put(e)
            .put(JSONObject().put("intent_role","EXIT")
                .put("risk_pct",0.9).put("exchange_status","SUBMISSION_UNKNOWN"))
            .put(add("unknown",0.00025,"NEW"))
        assertEquals(legacy(rows),futuresCockpitTotalReservedRisk(rows),1e-12)
    }
    @Test fun blankOrDuplicatedOwnerIdsRetainLegacyAccounting() {
        val rows=JSONArray().put(entry("",0.0004))
            .put(entry("",0.0002))
            .put(add("",0.0003,"NEW"))
        assertEquals(legacy(rows),futuresCockpitTotalReservedRisk(rows),1e-12)
    }
    @Test fun malformedRelatedFillRetainsFailClosedError() {
        val rows=JSONArray().put(entry("A",0.001)).put(add("A",0.002,"NEW","?"))
        assertThrows(IllegalStateException::class.java) {
            futuresCockpitTotalReservedRisk(rows)
        }
    }
    @Test fun historicEmptyJournalAndMalformedUnrelatedRowHaveNoReservedRisk() {
        assertEquals(0.0,futuresCockpitTotalReservedRisk(JSONArray()),0.0)
        val rows=JSONArray().put(entry("done",0.001,"CLOSED_OR_STOPPED"))
            .put("old malformed row")
        assertEquals(0.0,futuresCockpitTotalReservedRisk(rows),0.0)
    }
}
