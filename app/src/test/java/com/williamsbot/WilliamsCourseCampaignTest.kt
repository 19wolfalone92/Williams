package com.williamsbot

import org.junit.Assert.*
import org.junit.Test

class WilliamsCourseCampaignTest {
    @Test fun bookStagesAreExecutedInBothDirectionsOnlyAfterConfirmedFills() {
        for(side in listOf("LONG","SHORT")) {
            var state=WilliamsCourseState("owned-$side",side)
            for((i,stage) in courseStages.withIndex()) {
                val t=100_000L+i*3_600_000L
                val p=planWilliamsCourse(state,stage,t,t+1000,
                    waveConfirmed=true,exchangeReconciled=true,protectionVerified=true,
                    newRiskAdmitted=true)
                assertEquals("PROPOSED",p.status)
                assertEquals(courseTargets.getValue(stage),p.targetUnits)
                assertFalse(p.canPlaceOrders)
                val filled=acknowledgeWilliamsCourseFill(state,p,"fill-$i",p.signedUnitDelta,
                    p.targetUnits,t,true,true)
                assertEquals(p.targetUnits,filled.confirmedUnits)
                assertEquals(stage,filled.stage)
                assertEquals(filled,acknowledgeWilliamsCourseFill(filled,p,"fill-$i",
                    p.signedUnitDelta,p.targetUnits,t,true,true))
                state=filled
            }
            assertEquals("EXIT",state.stage)
            assertEquals(0,state.confirmedUnits)
        }
    }
    @Test fun stopRiskAndReconciliationCannotBeBypassed() {
        val s=WilliamsCourseState("owner","LONG")
        fun proposal(w:Boolean=true,e:Boolean=true,p:Boolean=true,r:Boolean=true,slots:Int=10) =
            planWilliamsCourse(s,"W1",100_000,101_000,w,e,p,r,slots)
        assertEquals("WAVE_DEGREE_UNCONFIRMED",proposal(w=false).reason)
        assertEquals("EXCHANGE_RECONCILIATION_REQUIRED",proposal(e=false).reason)
        assertEquals("PROTECTIVE_STOP_NOT_VERIFIED",proposal(p=false).reason)
        assertEquals("RISK_GATE_BLOCKS_NEW_EXPOSURE",proposal(r=false).reason)
        assertEquals("RISK_SLOT_CAP",proposal(slots=2).reason)
        assertEquals("NON_SEQUENTIAL_STAGE",planWilliamsCourse(s,"W3",100_000,101_000,
            true,true,true,true).reason)
    }
    @Test fun staleFillOrPartialFillDoesNotChangeState() {
        val s=WilliamsCourseState("owner","SHORT")
        val p=planWilliamsCourse(s,"W1",100_000,101_000,true,true,true,true)
        try {
            acknowledgeWilliamsCourseFill(s,p,"partial",1,1,100_000,true,true)
            fail("partial fill unexpectedly confirmed complete Course stage")
        } catch(expected:IllegalArgumentException) {
            assertEquals(0,s.confirmedUnits)
        }
        try {
            acknowledgeWilliamsCourseFill(s,p,"filled",3,3,100_000,false,true)
            fail("exchange-unreconciled fill was accepted")
        } catch(expected:IllegalArgumentException) {
            assertEquals(0,s.revision)
        }
    }
}
