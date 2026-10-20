package com.williamsbot

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class WilliamsExecutionPolicyTest {
    private val h=3_600_000L
    @Test fun onlyTc2MayCreateNewRisk() {
        assertNull(williamsNewExposureBlocker(williamsTc2Profile))
        for(source in listOf(williamsNtdProfile,williamsCourseProfile,williamsUnifiedProfile,""))
            assertEquals("WILLIAMS_SOURCE_OBSERVATION_ONLY",williamsNewExposureBlocker(source))
    }
    @Test fun oldFractalNeedsCurrentStructuralProofInsteadOfAnAgeCutoff() {
        val at=480000L*h;val now=at+105*h
        for((direction,type,entry,stop) in listOf(
            listOf("LONG","FRACTAL","110.1","99.9"),listOf("SHORT","SHORT_FRACTAL","99.9","110.1"))) {
            val proof=williamsBookH1Proof("f",direction,type,williamsTc2Profile,at,entry.toDouble(),stop.toDouble(),.1)
            assertEquals(0L,proof.getLong("expires_at_ms"))
            assertNull(williamsBookH1ProofBlocker(JSONObject().put("book_h1_entry",proof).toString(),
                direction,"f",type,williamsTc2Profile,entry.toDouble(),stop.toDouble(),now))
        }
        assertFalse(williamsH1SignalTimeEligible(at,"REVERSAL",williamsTc2Profile,now))
        assertFalse(williamsH1SignalTimeEligible(at,"FRACTAL",williamsNtdProfile,now))
        val bars=(0..44).map { i -> WilliamsWaveBar(at+i*h,100.0,101.0,99.0,100.0,1.0) }
        assertNull(williamsClosedH1HistoryBlocker(bars,at+45*h+1000))
        assertEquals("H1_SNAPSHOT_STALE",williamsClosedH1HistoryBlocker(bars,at+46*h+1000))
    }
    @Test fun firstWiseManIsAllowedAgainstTheDailyTrend() {
        assertEquals(1,directionalSignalContextRank("REVERSAL",false,true,false,true))
        assertEquals(1,directionalSignalContextRank("SHORT_REVERSAL",true,false,true,false))
        assertEquals(0,directionalSignalContextRank("FRACTAL",false,true,false,true))
    }
    @Test fun figureElevenTwoGroupsNeedTwoClosedRightBars() {
        for(edges in listOf(listOf(1,2,5,2,1),listOf(1,2,5,5,2,1),listOf(1,2,5,3,5,3,5,2,1))) {
            val bars=edges.mapIndexed { i,e -> WilliamsWaveBar((480000L+i)*h,100.0,100.0+e,99.0,100.0,1.0) }
            for(short in listOf(false,true)) {
                val input=if(short) bars.map { it.copy(o=220-it.o,h=220-it.l,l=220-it.h,c=220-it.c) } else bars
                val kind=if(short) "LOW" else "HIGH"
                val center=edges.indexOfLast { it==5 }
                assertFalse(confirmedWilliamsEditionFractals(input.dropLast(1)).any { it.kind==kind && it.center==center })
                val found=confirmedWilliamsEditionFractals(input).single { it.kind==kind && it.center==center }
                assertEquals(input.lastIndex,found.confirmation)
            }
        }
    }
    @Test fun newAttemptRequiresConfirmedZeroFillCancellation() {
        val old=JSONObject().put("signal_id","f").put("intent_role","ENTRY")
            .put("campaign_status","CLOSED_OR_STOPPED").put("cancel_reconciled_status","CANCELED")
            .put("cancel_reconciled_at_ms",1000).put("position_observed_after_cancel","0")
        assertEquals(1,williamsBookH1RetryGeneration(JSONArray().put(old),"f"))
        assertNotEquals(williamsBookH1ClientId("BTCUSDT","f"),williamsBookH1ClientId("BTCUSDT","f",1))
        assertTrue(williamsBookH1ClientId("BTCUSDT","f",100).length<=36)
        for(change in listOf(
            JSONObject(old.toString()).put("executed_qty","0.01"),
            JSONObject(old.toString()).put("cancel_reconciled_status","CANCEL_SUBMISSION_UNKNOWN"),
            JSONObject(old.toString()).put("conditional_actual_order_id","123"),
            JSONObject(old.toString()).put("position_observed_after_cancel","NaN"),
            JSONObject(old.toString()).put("fill_observed_at_ms",999)))
            assertNull(williamsBookH1RetryGeneration(JSONArray().put(change),"f"))
    }
    @Test fun tc2OppositeH1TriggerIsAnAdvisoryNotAMarketExit() {
        assertFalse(williamsOpposingSignalCanMarketClose(williamsTc2Profile))
        assertTrue(williamsOpposingSignalCanMarketClose(williamsNtdProfile))
        assertTrue(williamsOpposingSignalCanMarketClose(williamsCourseProfile))
        assertFalse(williamsOpposingSignalCanMarketClose("UNKNOWN"))
    }

    @Test fun tc2H1WiseMenCanAddBeforeDailyOrH4AlligatorAlignment() {
        assertFalse(williamsAdditionRequiresHigherFrameTrendVeto(williamsTc2Profile))
        assertTrue(williamsAdditionRequiresHigherFrameTrendVeto(williamsNtdProfile))
        assertTrue(williamsAdditionRequiresHigherFrameTrendVeto(williamsCourseProfile))
        assertTrue(williamsAdditionRequiresHigherFrameTrendVeto(williamsUnifiedProfile))
        // Executable exposure permission is a separate gate: NTD & Course
        // may observe H1 signals but cannot create new live risk.
        assertNull(williamsNewExposureBlocker(williamsTc2Profile))
        assertNotNull(williamsNewExposureBlocker(williamsNtdProfile))
        assertNotNull(williamsNewExposureBlocker(williamsCourseProfile))
    }
}
