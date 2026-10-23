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
        assertNotNull(blocker("SHORT",JSONObject(p.toString()).apply { remove("trigger_price_basis") },95.0,101.0))
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

    @Test fun additionProofPinsCampaignWithoutResettingItsProtectiveStop() {
        for(side in listOf("LONG","SHORT")) {
            val entry=if(side=="LONG") 105.0 else 95.0
            val signalStop=if(side=="LONG") 102.0 else 98.0
            val campaignStop=if(side=="LONG") 99.0 else 101.0
            val type=if(side=="LONG") "FRACTAL" else "SHORT_FRACTAL"
            val p=williamsBookH1Proof("new-fractal",side,type,williamsTc2Profile,t,
                entry,signalStop,.1,"owner-1",campaignStop)
            val setup=JSONObject().put("book_h1_entry",p).toString()
            assertNull(williamsBookH1ProofBlocker(setup,side,"new-fractal",type,williamsTc2Profile,
                entry,campaignStop,now,"owner-1"))
            assertNotNull(williamsBookH1ProofBlocker(setup,side,"new-fractal",type,williamsTc2Profile,
                entry,campaignStop,now,"other-owner"))
            assertNotNull(williamsBookH1ProofBlocker(setup,side,"new-fractal",type,williamsTc2Profile,
                entry,campaignStop,now))
            assertNotNull(williamsBookH1ProofBlocker(setup,side,"new-fractal",type,williamsTc2Profile,
                entry,signalStop,now,"owner-1"))
        }
    }

    @Test fun freshFormingBarVetoesPastTouchAfterRetreatInBothDirections() {
        val closed=listOf(h1Bars().first())
        val live=h1Bars().last()
        assertNull(williamsBookH1ObservedBreakoutBlocker("LONG",t,105.0,now,closed,listOf(live)))
        assertNull(williamsBookH1ObservedBreakoutBlocker("SHORT",t,95.0,now,closed,listOf(live)))
        assertNotNull(williamsBookH1ObservedBreakoutBlocker("LONG",t,105.0,now,closed,listOf(live.copy(h=106.0))))
        assertNotNull(williamsBookH1ObservedBreakoutBlocker("SHORT",t,95.0,now,closed,listOf(live.copy(l=94.0))))
        assertNotNull(williamsBookH1ObservedBreakoutBlocker("LONG",t,105.0,now,closed,emptyList()))
        assertNotNull(williamsBookH1ObservedBreakoutBlocker("LONG",t,105.0,now,closed,closed))
        assertNotNull(williamsBookH1ObservedBreakoutBlocker("LONG",t,105.0,now,closed,listOf(live.copy(h=Double.NaN))))
        // At a new hour an old raw snapshot cannot prove the new bar untouched.
        assertNotNull(williamsBookH1ObservedBreakoutBlocker("LONG",t,105.0,t+2*WILLIAMS_H1_MS,closed,listOf(live)))
    }

    @Test fun historyBeginningAfterSignalKnowledgeCannotProveUntouchedBreakout() {
        val late=h1Bars().last().copy(t=t+2*WILLIAMS_H1_MS)
        assertTrue(williamsBookH1TriggerConsumed("LONG",t,105.0,t+2*WILLIAMS_H1_MS+1,listOf(late)))
    }

    @Test fun tc2BreakoutPriceRatherThanHistoricFractalExtremeControlsActivation() {
        // The red Teeth is between the center-bar pivot and the exact
        // one-tick-stop ENTRY. The book says activation occurs above/below
        // Teeth; adding a non-book pivot-margin discards a valid setup.
        assertNull(williamsBookFractalTeethBlocker("FRACTAL","LONG",
            105.10,105.05,.10))
        assertNotNull(williamsBookFractalTeethBlocker("FRACTAL","LONG",
            105.10,105.10,.10))
        assertNull(williamsBookFractalTeethBlocker("SHORT_FRACTAL","SHORT",
            94.90,94.95,.10))
        assertNotNull(williamsBookFractalTeethBlocker("SHORT_FRACTAL","SHORT",
            94.90,94.90,.10))
    }

    @Test fun activationTeethUsesCurrentDisplayedLineFromClosedPricesOnly() {
        val bars=(0 until 50).map { index ->
            val price=100.0+index
            WilliamsWaveBar(t+index*WILLIAMS_H1_MS,price,price+1,price-1,price,10.0)
        }
        val at=bars.last().t+WILLIAMS_H1_MS+30_000L
        val raw=smmaSeededValues(bars.map { (it.h+it.l)/2 },8)
        val lastClosedLine=shiftAlligatorForDisplay(raw,5).last()!!
        val active=williamsBookH1ActivationTeeth(bars,at)
        assertEquals(raw[bars.size-5]!!,active,1e-12)
        assertTrue(active>lastClosedLine)
        // An extreme between the previous and current red line must be refused.
        val extreme=(active+lastClosedLine)/2
        assertNull(williamsBookFractalTeethBlocker("FRACTAL","LONG",extreme+.1,lastClosedLine,.1))
        assertNotNull(williamsBookFractalTeethBlocker("FRACTAL","LONG",extreme+.1,active,.1))
        assertTrue(williamsBookH1ActivationTeeth(bars,at+WILLIAMS_H1_MS).isNaN())
    }
}
