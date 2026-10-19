package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WilliamsMtfEntryPolicyTest {
    @Test fun cancelledParentCannotResurrectButDoesNotFreezeANewH1Scenario() {
        val fence=JSONObject().put("parent_id","old").put("pivot_at_ms",-1L).put("expires_at_ms",500L)
        assertEquals("M15_PARENT_REVOKED_AFTER_CANCEL",williamsMtfReanchorBlocker(fence,"old",100))
        assertNull(williamsMtfReanchorBlocker(fence,"new",100))
        fence.put("pivot_at_ms",200L)
        assertNull(williamsMtfReanchorBlocker(fence,"old",100,200))
        assertEquals("M15_REANCHOR_TARGET_NOT_VALID",williamsMtfReanchorBlocker(fence,"old",100,150))
        assertNull(williamsMtfReanchorBlocker(fence,"new",100,150))
        assertNull(williamsMtfReanchorBlocker(fence,"old",500,150))
    }
    private val start=java.time.Instant.parse("2026-10-19T00:00:00Z").toEpochMilli()
    private fun bars(short:Boolean=false):List<WilliamsWaveBar> = (0..47).map { i ->
        val high=when(i) { 42->102.0;43->103.0;44->105.0;45->104.0;46->103.0;47->102.0;else->100.0 }
        val low=if(i>=43) 98.0 else 96.0
        val close=if(i>=43) 100.0 else 98.0
        if(short) WilliamsWaveBar(start+i*WILLIAMS_M15_MS,200-close,200-low,200-high,200-close,10.0)
        else WilliamsWaveBar(start+i*WILLIAMS_M15_MS,close,high,low,close,10.0)
    }
    private fun parent(short:Boolean=false)=WilliamsH1Parent("TEST:1h:"+if(short) "SHORT_REVERSAL" else "REVERSAL",
        if(short) "SHORT" else "LONG",start+40*WILLIAMS_M15_MS,if(short) 92.0 else 108.0,if(short) 110.0 else 90.0)
    private fun decision(short:Boolean=false,b:List<WilliamsWaveBar> = bars(short),
        p:WilliamsH1Parent=parent(short),mark:Double=if(short) 99.0 else 101.0,
        teeth:List<Double> = List(b.size) { if(short) 105.0 else 95.0 },now:Long=start+48*WILLIAMS_M15_MS)=
        selectWilliamsH1M15Entry(p,b,teeth,.1,mark,now)

    @Test fun mirroredConfirmedFractalsRefineTheSameH1Direction() {
        for(short in listOf(false,true)) {
            val d=decision(short);assertNotNull(d.reason,d.entry)
            val e=d.entry!!
            assertEquals(start+44*WILLIAMS_M15_MS,e.pivotAt)
            assertEquals(start+47*WILLIAMS_M15_MS,e.confirmedAt)
            assertEquals(if(short) 94.9 else 105.1,e.trigger,1e-9)
            assertEquals(if(short) 104.1 else 95.9,e.stop,1e-9)
            assertNull(williamsMtfProofBlocker(JSONObject().put("mtf_entry",e.toJson()).toString(),
                pDirection(short),e.parent.signalId,e.trigger,e.stop,start+48*WILLIAMS_M15_MS))
            assertTrue(e.clientId("BTCUSDT").length<=36)
            assertEquals(e.clientId("BTCUSDT"),e.clientId("BTCUSDT"))
        }
    }
    private fun pDirection(short:Boolean)=if(short) "SHORT" else "LONG"
    @Test fun twoClosedRightBarsAreMandatoryForBothSides() {
        for(short in listOf(false,true)) assertNull(decision(short,bars(short).take(46)).entry)
    }
    @Test fun formingOrMissingOrStaleCandlesCannotAuthorizeAnEntry() {
        val b=bars()
        assertEquals("M15_CLOSED_CONTIGUOUS_HISTORY_REQUIRED",decision(b=b,now=b.last().t).reason)
        assertEquals("M15_CLOSED_CONTIGUOUS_HISTORY_REQUIRED",decision(b=b.filterIndexed { i,_ -> i!=42 }).reason)
        assertEquals("M15_HISTORY_STALE",decision(now=start+51*WILLIAMS_M15_MS).reason)
        assertNull(decision(b=b.drop(5)).entry)
    }
    @Test fun confirmationPredatingParentAndWrongSideOfTeethAreRejected() {
        assertEquals("WAIT_M15_FRACTAL_AFTER_H1",decision(p=parent().copy(signalAt=start+44*WILLIAMS_M15_MS)).reason)
        for(short in listOf(false,true)) assertNull(decision(short,teeth=List(48) { if(short) 90.0 else 110.0 }).entry)
    }
    @Test fun parentExpiryAndStructuralInvalidationAreBinding() {
        assertEquals("H1_PARENT_EXPIRED",decision(p=parent().copy(expiresAt=start+48*WILLIAMS_M15_MS)).reason)
        for(short in listOf(false,true)) {
            val b=bars(short).toMutableList();val last=b.last()
            b[b.lastIndex]=if(short) last.copy(h=111.0) else last.copy(l=89.0)
            assertEquals("H1_PARENT_STRUCTURAL_INVALIDATION",decision(short,b).reason)
        }
    }
    @Test fun consumedTriggerAndPreviouslyBrokenLocalStopCannotBeChased() {
        for(short in listOf(false,true)) {
            assertEquals("M15_TRIGGER_ALREADY_CROSSED",decision(short,mark=if(short) 94.0 else 106.0).reason)
            val b=bars(short).toMutableList();val last=b.last()
            b[b.lastIndex]=if(short) last.copy(h=105.0) else last.copy(l=95.0)
            assertEquals("M15_STRUCTURAL_STOP_ALREADY_INVALIDATED",decision(short,b).reason)
        }
        assertEquals("M15_STOP_OUTSIDE_H1_SCENARIO",decision(p=parent().copy(invalidation=97.0)).reason)
    }
    @Test fun h1TriggerCrossingDoesNotDestroyAStillValidParentWaitingForM15() {
        assertNotNull(decision(p=parent().copy(trigger=100.0)).entry)
    }
    @Test fun proofRejectsOppositeDirectionMissingEvidenceTamperedPricesAndFutureConfirmation() {
        val e=decision().entry!!
        fun check(proof:JSONObject,side:String="LONG",price:Double=e.trigger)=
            williamsMtfProofBlocker(JSONObject().put("mtf_entry",proof).toString(),side,e.parent.signalId,price,e.stop,start+48*WILLIAMS_M15_MS)
        assertNotNull(check(e.toJson(),"SHORT"))
        assertNotNull(check(e.toJson(),price=106.0))
        assertNotNull(check(e.toJson().put("confirmed_at_ms",start+49*WILLIAMS_M15_MS)))
        assertNotNull(check(e.toJson().put("pivot_at_ms",e.pivotAt-WILLIAMS_M15_MS)))
        assertNotNull(check(e.toJson().put("trigger","unknown")))
        assertNotNull(williamsMtfProofBlocker("{}","LONG",e.parent.signalId,e.trigger,e.stop,start+48*WILLIAMS_M15_MS))
    }
    @Test fun filledParentCannotRepeatButZeroFillCancellationCanBeReplaced() {
        val id=parent().signalId
        val row=JSONObject().put("signal_id",id).put("intent_role","ENTRY").put("campaign_status","CLOSED_OR_STOPPED").put("executed_qty","0")
        assertFalse(williamsH1ParentAlreadyFilled(JSONArray().put(row),id))
        row.put("actual_entry_order_id","42").put("actual_entry_executed_qty","0")
        assertFalse(williamsH1ParentAlreadyFilled(JSONArray().put(row),id))
        row.put("actual_entry_executed_qty","0.1")
        assertTrue(williamsH1ParentAlreadyFilled(JSONArray().put(row),id))
        row.put("actual_entry_executed_qty","0")
        row.put("position_open_observed_at_ms",start)
        assertTrue(williamsH1ParentAlreadyFilled(JSONArray().put(row),id))
        assertFalse(williamsH1ParentAlreadyFilled(JSONArray().put(row),"different"))
    }
    @Test fun pendingLevelSurvivesRecoveryButNotBreakOfTeethOrProtection() {
        val e=decision().entry!!;val b=bars()
        assertNull(williamsPendingM15Invalidation(e,b,List(b.size) {95.0},101.0,start+48*WILLIAMS_M15_MS))
        assertNotNull(williamsPendingM15Invalidation(e,b,List(b.size) {106.0},101.0,start+48*WILLIAMS_M15_MS))
        assertNotNull(williamsPendingM15Invalidation(e,b,List(b.size) {95.0},95.0,start+48*WILLIAMS_M15_MS))
        assertEquals(e,williamsM15EntryFromJson(e.toJson()))
    }
}
