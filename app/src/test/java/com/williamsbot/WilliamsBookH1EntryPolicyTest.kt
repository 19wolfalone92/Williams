package com.williamsbot

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class WilliamsBookH1EntryPolicyTest {
    private val t=Instant.parse("2026-10-19T07:00:00Z").toEpochMilli()
    private val now=t+WILLIAMS_H1_MS+30_000L
    private fun proof(side:String,profile:String=williamsTc2Profile):JSONObject =
        williamsBookH1Proof("WM1:ETH:1h:$side",side,
            if(side=="LONG") "REVERSAL" else "SHORT_REVERSAL",profile,t,
            if(side=="LONG") 105.0 else 95.0,
            if(side=="LONG") 99.0 else 101.0,.1)
    private fun blocker(side:String,proof:JSONObject,entry:Double,stop:Double,
                        profile:String=williamsTc2Profile,at:Long=now):String? =
        williamsBookH1ProofBlocker(JSONObject().put("book_h1_entry",proof).toString(),side,
            proof.optString("signal_id"),proof.optString("signal_type"),profile,entry,stop,at)

    @Test fun tc2UsesExactH1TriggerInBothDirections() {
        assertNull(blocker("LONG",proof("LONG"),105.0,99.0))
        assertNull(blocker("SHORT",proof("SHORT"),95.0,101.0))
        assertNotEquals(williamsBookH1ClientId("ETHUSDT","WM1:one"),
                        williamsBookH1ClientId("ETHUSDT","WM1:two"))
        assertEquals(williamsBookH1ClientId("ETHUSDT","WM1:one"),
                     williamsBookH1ClientId("ETHUSDT","WM1:one"))
        assertTrue(williamsBookH1ClientId("ETHUSDT","WM1:one").length<=36)
    }
    @Test fun noM15FractalOrM15HistoryIsRequired() {
        val context=JSONObject().put("book_h1_entry",proof("LONG"))
        assertFalse(context.has("mtf_entry"))
        assertNull(williamsBookH1ProofBlocker(context.toString(),"LONG",
            "WM1:ETH:1h:LONG","REVERSAL",williamsTc2Profile,105.0,99.0,now))
    }
    @Test fun rejectsMissingTamperedOrOppositeSideEvidence() {
        val p=proof("SHORT")
        assertNotNull(williamsBookH1ProofBlocker("{}","SHORT",p.getString("signal_id"),
            "SHORT_REVERSAL",williamsTc2Profile,95.0,101.0,now))
        assertNotNull(blocker("LONG",p,95.0,101.0))
        assertNotNull(blocker("SHORT",p,94.9,101.0))
        assertNotNull(blocker("SHORT",p,95.0,101.0,"NTD_FIVE_DIMENSIONS"))
        assertNotNull(blocker("SHORT",p,95.0,101.0,at=t+5*WILLIAMS_H1_MS))
        assertNotNull(blocker("SHORT",p,95.0,101.0,at=t))
    }
    @Test fun bookStopMayNotBeSilentlyWidened() {
        assertEquals("H1_BOOK_STOP_CHANGED",blocker("LONG",proof("LONG"),105.0,100.0))
        assertNotNull(blocker("LONG",proof("LONG"),105.0,98.0))
        assertNull(blocker("LONG",proof("LONG",williamsCourseProfile),105.0,100.0,williamsCourseProfile))
        assertNotNull(blocker("SHORT",proof("SHORT",williamsCourseProfile),95.0,102.0,williamsCourseProfile))
    }
    @Test fun teethCheckAtH1TriggerWorksForLongAndShort() {
        assertNull(williamsBookFractalTeethBlocker("FRACTAL","LONG",105.0,104.0))
        assertNotNull(williamsBookFractalTeethBlocker("FRACTAL","LONG",105.0,105.0))
        assertNull(williamsBookFractalTeethBlocker("SHORT_FRACTAL","SHORT",95.0,96.0))
        assertNotNull(williamsBookFractalTeethBlocker("SHORT_FRACTAL","SHORT",95.0,94.0))
        assertNull(williamsBookFractalTeethBlocker("REVERSAL","LONG",105.0,110.0))
        assertNull(williamsBookFractalTeethBlocker("SUPER_AO","LONG",105.0,110.0))
    }
    private fun h1Bars(firstHigh:Double=103.0,secondHigh:Double=104.0,
                       firstLow:Double=98.0,secondLow:Double=97.0)=listOf(
        WilliamsWaveBar(t,100.0,firstHigh,firstLow,100.0,10.0),
        WilliamsWaveBar(t+WILLIAMS_H1_MS,100.0,secondHigh,secondLow,100.0,10.0))
    @Test fun consumedH1LevelCannotBeRearmedAfterPriceRetreat() {
        assertFalse(williamsBookH1TriggerConsumed("LONG",t,105.0,t+WILLIAMS_H1_MS+20_000L,h1Bars()))
        assertTrue(williamsBookH1TriggerConsumed("LONG",t,105.0,t+WILLIAMS_H1_MS+20_000L,h1Bars(secondHigh=106.0)))
        assertTrue(williamsBookH1TriggerConsumed("SHORT",t,95.0,t+WILLIAMS_H1_MS+20_000L,h1Bars(secondLow=94.0)))
        assertFalse(williamsBookH1TriggerConsumed("SHORT",t,95.0,t+WILLIAMS_H1_MS+20_000L,h1Bars()))
        assertTrue(williamsBookH1TriggerConsumed("LONG",t,105.0,t,h1Bars()))
    }
    @Test fun gappedOrMalformedH1BarsFailClosed() {
        val gap=listOf(h1Bars().first(),h1Bars().last().copy(t=t+2*WILLIAMS_H1_MS))
        assertTrue(williamsBookH1TriggerConsumed("LONG",t,105.0,t+3*WILLIAMS_H1_MS,gap))
        assertTrue(williamsBookH1TriggerConsumed("LONG",t,105.0,t+2*WILLIAMS_H1_MS,
            listOf(h1Bars().first(),h1Bars().last().copy(h=Double.NaN))))
    }
}
