package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class R51QuoteRiskTest {
    private fun row(id:String, amount:Double, equity:Double)=JSONObject()
        .put("client_id",id).put("intent_role","ENTRY").put("campaign_status","ENTRY_PENDING")
        .put("risk_pct",amount/equity).put("risk_equity_snapshot_usdt",equity)
        .put("risk_budget_usdt",amount).put("risk_quote_usdt",amount)
    @Test fun changingEquityDoesNotRebaseHistoricReservations() {
        val all=JSONArray().put(row("A",10.0,5000.0)).put(row("B",10.0,6000.0)).put(row("C",10.0,5000.0))
        assertNull(futuresQuoteRiskTransportBlocker(all,"C",5000.0))
        assertNotNull(futuresQuoteRiskTransportBlocker(all,"C",4900.0))
        assertNotNull(futuresQuoteRiskTransportBlocker(all,"C",0.0))
    }
    @Test fun unknownAndDuplicateReservationsCannotAuthorizeExposure() {
        val a=row("A",10.0,5000.0)
        val b=row("B",10.0,5000.0)
        b.put("risk_quote_usdt","unknown")
        assertNotNull(futuresQuoteRiskTransportBlocker(JSONArray().put(a).put(b),"A",5000.0))
        assertNotNull(futuresQuoteRiskTransportBlocker(JSONArray().put(a).put(a),"A",5000.0))
    }
    @Test fun historicalBasisAndUnresolvedPartialChildAreRetained() {
        val a=row("A",10.0,5000.0)
        a.remove("risk_quote_usdt")
        val b=row("B",10.0,5000.0)
        val child=row("CHILD",10.0,6000.0)
            .put("intent_role","ADD_ON").put("entry_client_id","A")
            .put("order_status","CANCELED").put("executed_qty","0.1")
        val all=JSONArray().put(a).put(b).put(child)
        assertEquals(20.0,futuresCampaignRiskRows(a,all).sumOf { futuresQuoteRiskUsdt(it) },1e-12)
        assertNull(futuresQuoteRiskTransportBlocker(all,"B",5000.0))
        assertNotNull(futuresQuoteRiskTransportBlocker(all,"B",4900.0))
    }
}
