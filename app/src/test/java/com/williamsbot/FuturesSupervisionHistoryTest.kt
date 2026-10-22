package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FuturesSupervisionHistoryTest {
    private fun record(symbol:String="BTCUSDT",direction:String="LONG")=JSONObject()
        .put("symbol",symbol).put("direction",direction).put("quantity","1").put("entry_price","100")
        .put("stop",if(direction=="LONG") "96" else "104").put("initial_stop",if(direction=="LONG") "95" else "105")
        .put("approved_at_ms",1000L).put("state","ACTIVE").put("protection_client_id","W2T_STOP")
        .put("last_confirmed_trail_at_ms",1500L)

    @Test fun threeFormerLongsAreArchivedSeparatelyFromTheNewBnbShortWithoutInventingAClosure() {
        val records=JSONArray().put(record("BNBUSDT")).put(record("XRPUSDT")).put(record("SOLUSDT"))
        val positions=JSONArray().put(JSONObject().put("symbol","BNBUSDT").put("positionSide","BOTH")
            .put("positionAmt","-0.19").put("entryPrice","744.32"))
        val reconciled=reconcileSupervisionHistory(records,positions,2000)
        assertEquals("ACTIVE",records.getJSONObject(0).getString("state")) // Commit only the returned copy.
        val history=futuresVisibleTradeHistory(JSONArray(),reconciled,JSONArray())
        assertEquals(3,history.length())
        for(i in 0 until 3) {
            val row=history.getJSONObject(i)
            assertEquals("LONG",row.getString("direction"))
            assertEquals("CLOSED_UNVERIFIED",row.getString("status"))
            assertTrue(row.isNull("exit_price"));assertTrue(row.isNull("pnl"))
            assertFalse(row.getBoolean("pnl_complete"));assertEquals(0L,row.getLong("closed_at"))
            assertEquals(2000L,row.getLong("closure_observed_at"))
            assertEquals(3,row.getJSONArray("management_history").length())
        }
        assertEquals(reconciled.toString(),reconcileSupervisionHistory(reconciled,positions,3000).toString())
    }

    @Test fun malformedPositionDataCannotBeMisreadAsAClosedPosition() {
        val bad=JSONArray().put(JSONObject().put("symbol","BTCUSDT").put("positionAmt","bad"))
        assertThrows(Exception::class.java) { reconcileSupervisionHistory(JSONArray().put(record()),bad,2000) }
    }

    @Test fun changesSuspendConsentAndNeverPretendThatAnUnchangedPositionIsStillApproved() {
        for(change in listOf("quantity","entry")) {
            val p=JSONObject().put("symbol","BTCUSDT").put("positionAmt",if(change=="quantity") "2" else "1")
                .put("entryPrice",if(change=="entry") "101" else "100").put("positionSide","BOTH")
            val result=reconcileSupervisionHistory(JSONArray().put(record()),JSONArray().put(p),2000)
            assertEquals("SUSPENDED_CHANGED",result.getJSONObject(0).getString("state"))
            assertEquals("SUSPENDED",futuresVisibleTradeHistory(JSONArray(),result,JSONArray()).getJSONObject(0).getString("status"))
            assertFalse(result.getJSONObject(0).has("position_absent_observed_at_ms"))
        }
    }

    private fun parent(direction:String)=JSONObject().put("symbol","BTCUSDT").put("clientAlgoId","W2T_STOP")
        .put("actualOrderId",42L).put("algoStatus","FINISHED").put("orderType","STOP_MARKET")
        .put("positionSide","BOTH").put("side",if(direction=="LONG") "SELL" else "BUY")
        .put("triggerPrice",if(direction=="LONG") "96" else "104")

    private fun child(direction:String)=JSONObject().put("symbol","BTCUSDT").put("orderId",42L)
        .put("side",if(direction=="LONG") "SELL" else "BUY").put("positionSide","BOTH")
        .put("status","FILLED").put("executedQty","1")

    private fun fills(direction:String)=JSONArray().apply {
        for(i in 0..1) put(JSONObject().put("symbol","BTCUSDT").put("orderId",42L).put("id",500L+i)
            .put("side",if(direction=="LONG") "SELL" else "BUY").put("positionSide","BOTH")
            .put("qty",if(i==0) "0.4" else "0.6")
            .put("price",if(direction=="LONG") {if(i==0) "95.9" else "96.1"} else {if(i==0) "104.1" else "103.9"})
            .put("realizedPnl",if(i==0) "-1.64" else "-2.34")
            .put("commission",if(i==0) "0.01" else "0.02").put("commissionAsset","USDT")
            .put("time",2000L+i))
    }

    @Test fun exactParentChildAndFillsProveActualExitPriceAndGrossPnlInBothDirections() {
        for(direction in listOf("LONG","SHORT")) {
            val r=record(direction=direction)
            val proof=requireNotNull(verifiedSupervisedStopClosure(r,parent(direction),child(direction),fills(direction)))
            assertEquals(if(direction=="LONG") 96.02 else 103.98,proof.getDouble("exit_price"),1e-10)
            assertEquals(-3.98,proof.getDouble("gross_pnl_usdt"),1e-10)
            assertEquals(0.03,proof.getDouble("exit_fee_usdt"),1e-10)
            assertEquals(2001L,proof.getLong("closed_at"))
            r.put("confirmed_closure",proof).put("state","CLOSED_EXCHANGE_CONFIRMED")
            val row=futuresVisibleTradeHistory(JSONArray(),JSONArray().put(r),JSONArray()).getJSONObject(0)
            assertEquals("CLOSED",row.getString("status"))
            assertFalse(row.getBoolean("pnl_complete"));assertTrue(row.isNull("pnl"))
            assertEquals(0L,row.getLong("opened_at")) // Never substitute approval for actual entry time.
        }
    }

    @Test fun wrongTradeOrIncompleteFillEvidenceCannotBeAssignedToTheFormerPosition() {
        for(mutation in 0..7) {
            val r=record();val a=parent("LONG");val c=child("LONG");val f=fills("LONG")
            when(mutation) {
                0 -> c.put("orderId",43L)
                1 -> f.getJSONObject(0).put("orderId",43L)
                2 -> f.getJSONObject(0).put("side","BUY")
                3 -> f.getJSONObject(0).put("symbol","XRPUSDT")
                4 -> f.getJSONObject(0).put("id",501L)
                5 -> f.remove(0)
                6 -> f.getJSONObject(0).remove("realizedPnl")
                7 -> a.put("triggerPrice","97")
            }
            assertNull("mutation="+mutation,verifiedSupervisedStopClosure(r,a,c,f))
        }
    }

    @Test fun unconvertibleExitFeeDoesNotEraseVerifiedGrossPnlOrInventNetPnl() {
        val f=fills("LONG");f.getJSONObject(0).put("commissionAsset","BNB")
        val proof=requireNotNull(verifiedSupervisedStopClosure(record(),parent("LONG"),child("LONG"),f))
        assertEquals(-3.98,proof.getDouble("gross_pnl_usdt"),1e-10)
        assertFalse(proof.getBoolean("exit_fee_known"));assertTrue(proof.isNull("exit_fee_usdt"))
    }

    @Test fun realJournalRowsReplacePlaceholdersAndOpenNativeCampaignIsVisibleBeforeItsExit() {
        val intent=JSONObject().put("client_id","W2H_NEW_SHORT").put("symbol","BNBUSDT")
            .put("direction","SHORT").put("actual_entry_price",744.32).put("confirmed_position_qty","0.19")
            .put("campaign_status","OPEN_PROTECTED").put("protective_stop","751.95")
            .put("position_open_observed_at_ms",3000L)
        val projected=futuresVisibleTradeHistory(JSONArray(),JSONArray().put(record("BNBUSDT")),JSONArray().put(intent))
        assertEquals(2,projected.length())
        assertEquals("SHORT",projected.getJSONObject(0).getString("direction"))
        assertEquals(744.32,projected.getJSONObject(0).getDouble("entry_price"),0.0)
        val closed=JSONObject().put("id","FUTURES:W2H_NEW_SHORT").put("status","CLOSED")
            .put("closed_at",4000L).put("pnl_complete",true).put("pnl",1.2)
        val merged=futuresVisibleTradeHistory(JSONArray().put(closed),JSONArray(),JSONArray().put(intent))
        assertEquals(1,merged.length());assertTrue(merged.getJSONObject(0).getBoolean("pnl_complete"))
        assertEquals(1.2,merged.getJSONObject(0).getDouble("pnl"),0.0)
    }

    @Test fun anUncertainProposedStopIsNeverDisplayedAsTheLastConfirmedStop() {
        val intent=JSONObject().put("client_id","W2H_NEW_SHORT").put("symbol","BNBUSDT")
            .put("direction","SHORT").put("actual_entry_price",744.32).put("confirmed_position_qty","0.19")
            .put("campaign_status","OPEN_PROTECTED").put("protection_status","SUBMISSION_UNKNOWN")
            .put("protective_stop","751.22").put("previous_protective_stop","751.95")
        var rows=futuresVisibleTradeHistory(JSONArray(),JSONArray(),JSONArray().put(intent))
        assertEquals(751.95,rows.getJSONObject(0).getDouble("last_stop"),0.0)
        intent.remove("previous_protective_stop")
        rows=futuresVisibleTradeHistory(JSONArray(),JSONArray(),JSONArray().put(intent))
        assertTrue(rows.getJSONObject(0).isNull("last_stop"))
    }

    @Test fun pendingNativeSwapHistoryUsesTheObservedStopAndDoesNotPromoteTheProposal() {
        val owner = JSONObject().put("client_id", "W2H_BNB").put("symbol", "BNBUSDT").put("direction", "SHORT")
            .put("campaign_status", "OPEN_PROTECTED").put("actual_entry_price", "744.32")
            .put("confirmed_position_qty", "0.19").put("protective_stop", "751.95")
            .put("protection_status", "REPLACEMENT_PENDING_PROTECTED")
            .put("native_stop_replacement", JSONObject().put("new_stop", "751.22"))
            .put("native_replacement_protection", JSONObject().put("confirmed", true).put("stop", "751.95"))
        fun history() = futuresVisibleTradeHistory(JSONArray(), JSONArray(), JSONArray().put(owner)).getJSONObject(0)
        assertEquals(751.95, history().getDouble("last_stop"), 0.0)
        owner.getJSONObject("native_replacement_protection").put("stop", "751.22")
        assertEquals(751.22, history().getDouble("last_stop"), 0.0)
        owner.getJSONObject("native_replacement_protection").put("confirmed", false)
        assertTrue(history().isNull("last_stop"))
        assertTrue(history().isNull("pnl"))
    }

}
