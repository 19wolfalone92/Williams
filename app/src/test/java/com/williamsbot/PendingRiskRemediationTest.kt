package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PendingRiskRemediationTest {
    private val blocker = "ACTUAL_FUTURES_RISK_EXCEEDS_PORTFOLIO_CAP: measured risk exceeds 0.60%"
    private fun position(symbol:String,qty:String) =
        JSONObject().put("symbol",symbol).put("positionAmt",qty)
    private fun live(symbol:String,actual:Double) = JSONObject()
        .put("symbol",symbol).put("intent_role","ENTRY")
        .put("campaign_status","OPEN_PROTECTED")
        .put("protection_status","PROTECTED")
        .put("strategy_ownership_verified",true)
        .put("actual_all_in_risk_pct",actual)
        .put("risk_pct",actual)
        .put("client_id","live-$symbol")
    private fun pending(symbol:String, risk:Double) = JSONObject()
        .put("symbol",symbol).put("intent_role","ENTRY")
        .put("campaign_status","ENTRY_PENDING")
        .put("order_status","NEW").put("exchange_status","NEW")
        .put("strategy_ownership_verified",true)
        .put("executed_qty","0").put("risk_pct",risk)
        .put("client_id","pending-$symbol").put("created_at_ms",200L)
    private fun openPositions()=JSONArray().put(position("ETHUSDT","-0.499"))
        .put(position("SOLUSDT","-11.49"))
    private fun current()=JSONArray().put(live("ETHUSDT",0.002027944887553803))
        .put(live("SOLUSDT",0.0020018321474615636))
        .put(pending("XRPUSDT",0.001972800245543236))

    @Test fun observedDiagnosisProposesOnlyCancelingUnfilledXrp() {
        val next=proposePendingRiskRemediation(blocker,openPositions(),current())
        assertNotNull(next)
        assertEquals("XRPUSDT",next!!.symbol)
        assertEquals("pending-XRPUSDT",next.clientId)
        assertTrue(next.totalWithPendingRiskPct>0.006)
        assertTrue(next.measuredOpenRiskPct<0.006)
    }

    @Test fun unknownOrPartiallyFilledEntryCannotBeCancelledBlindly() {
        for (status in listOf("SUBMISSION_UNKNOWN","CANCEL_SUBMISSION_UNKNOWN",
                 "PARTIALLY_FILLED","FILLED","TRIGGERED")) {
            val rows=current().put(2, pending("XRPUSDT",0.001972800245543236)
                .put("order_status",status))
            assertNull(status,proposePendingRiskRemediation(blocker,openPositions(),rows))
        }
        assertNull(proposePendingRiskRemediation(blocker,openPositions(),
            current().put(2,pending("XRPUSDT",0.001972800245543236)
                .put("executed_qty","0.3"))))
    }

    @Test fun neverCancelAnOrderIfTheExchangeShowsExposureInThatSymbol() {
        val exposed=openPositions().put(position("XRPUSDT","-40.0"))
        assertNull(proposePendingRiskRemediation(blocker,exposed,current()))
    }

    @Test fun missingPositionOrUnownedProtectionNeverAuthorizesRemediation() {
        assertNull(proposePendingRiskRemediation(blocker,JSONArray(),current()))
        val rows=current().put(0,live("ETHUSDT",0.0020279)
            .put("protection_status","UNKNOWN"))
        assertNull(proposePendingRiskRemediation(blocker,openPositions(),rows))
    }

    @Test fun neverCancelWhenOpenRiskAlreadyExceedsCapOrRiskHasRoom() {
        val over=current().put(0,live("ETHUSDT",0.004))
        assertNull(proposePendingRiskRemediation(blocker,openPositions(),over))
        val safe=current().put(2,pending("XRPUSDT",0.001))
        assertNull(proposePendingRiskRemediation(blocker,openPositions(),safe))
        assertNull(proposePendingRiskRemediation("PENDING_ORDER_UNKNOWN",openPositions(),current()))
    }
}
