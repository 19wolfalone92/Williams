package com.williamsbot

import org.junit.Assert.*
import org.junit.Test

class WilliamsCourseCampaignTest {
    @Test fun bookStagesAreExecutedInBothDirectionsOnlyAfterConfirmedFills() {
        for(side in listOf("LONG","SHORT")) {
            var state=WilliamsCourseState("owned-$side",side,w3ToW1Ratio=2.0)
            for((i,stage) in courseStages.withIndex()) {
                val t=100_000L+i*3_600_000L
                val p=planWilliamsCourse(state,stage,t,t+1000,
                    waveConfirmed=true,exchangeReconciled=true,protectionVerified=true,
                    newRiskAdmitted=true)
                assertEquals("PROPOSED",p.status)
                assertEquals(williamsCourseTarget(stage,2.0),p.targetUnits)
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
    @Test fun executionStagesRequireANewFormationForEveryIncreaseAndNeverExitOnAWaveLabelAlone() {
        assertEquals("W2",williamsCourseNextStage("W1","W2"))
        assertNull(williamsCourseNextStage("W2","W3"))
        assertEquals("W3",williamsCourseNextStage("W2","W3",true))
        assertNull(williamsCourseNextStage("W3","W3",true))
        assertNull(williamsCourseNextStage("W3","W3",true,1.09))
        assertEquals("W3_EXTENSION",williamsCourseNextStage("W3","W3",true,1.1))
        assertEquals("W4",williamsCourseNextStage("W3","W4"))
        assertEquals("W4",williamsCourseNextStage("W3_EXTENSION","W4"))
        assertNull(williamsCourseNextStage("W4","W5"))
        assertEquals("W5",williamsCourseNextStage("W4","W5",true))
        assertNull(williamsCourseNextStage("W5","A",true))
    }

    @Test fun optionalW3ExtensionMayBeSkippedWithoutFabricatedFills() {
        for(side in listOf("LONG","SHORT")) {
            val s=WilliamsCourseState("owner-"+side,side,stage="W3",
                confirmedUnits=6,revision=3,lastSignalCloseMs=1000L)
            val p=planWilliamsCourse(s,"W4",2000,2500,true,true,true,true)
            assertEquals("PROPOSED",p.status)
            assertEquals(-3,p.signedUnitDelta)
            val confirmed=acknowledgeWilliamsCourseFill(s,p,"ex-fill-"+side,
                -3,3,2000,true,true)
            assertEquals("W4",confirmed.stage)
            assertEquals(3,confirmed.confirmedUnits)
            assertFalse(p.canPlaceOrders)
        }
    }

    @Test fun differentCampaignSideRevisionOrBarCannotHijackCourseFill() {
        val owner=WilliamsCourseState("owned","LONG")
        val correct=planWilliamsCourse(owner,"W1",100000,100500,true,true,true,true)
        val foreign=planWilliamsCourse(owner.copy(campaignId="attacker"),"W1",100000,100500,true,true,true,true)
        val wrongSide=planWilliamsCourse(owner.copy(side="SHORT"),"W1",100000,100500,true,true,true,true)
        val wrongTime=planWilliamsCourse(owner,"W1",100001,100500,true,true,true,true)
        val tampered=correct.copy(idempotencyKey="COURSE_FAKE",signedUnitDelta=3)
        for(p in listOf(foreign,wrongSide,wrongTime,tampered)) {
            try {
                acknowledgeWilliamsCourseFill(owner,p,"new-exchange-id",3,3,100000,true,true)
                fail("Foreign or tampered proposal changed Course state: "+p)
            } catch(expected:IllegalArgumentException) {
                assertEquals(0,owner.revision)
            }
        }
        assertEquals(1,acknowledgeWilliamsCourseFill(owner,correct,"genuine-fill",
            3,3,100000,true,true).revision)
    }

    @Test fun w5EightUnitBranchCannotBeReplayedWithDifferentWaveLengthEvidence() {
        for(side in listOf("LONG","SHORT")) {
            val s=WilliamsCourseState("owned",side,stage="W4",confirmedUnits=3,revision=4,w3ToW1Ratio=1.5)
            val p=planWilliamsCourse(s,"W5",100000,100500,true,true,true,true)
            assertEquals(8,p.targetUnits);assertEquals(5,p.signedUnitDelta)
            assertEquals(8,acknowledgeWilliamsCourseFill(s,p,"verified",5,8,100000,true,true).confirmedUnits)
            for(changed in listOf(s.copy(w3ToW1Ratio=null),s.copy(w3ToW1Ratio=2.0))) {
                try {
                    acknowledgeWilliamsCourseFill(changed,p,"different",5,8,100000,true,true)
                    fail("W5 proposal accepted without its original length evidence")
                } catch(expected:IllegalArgumentException) { assertEquals(4,changed.revision) }
            }
            assertEquals("COURSE_W5_W3_W1_RATIO_UNCONFIRMED",
                planWilliamsCourse(s.copy(w3ToW1Ratio=null),"W5",100000,100500,true,true,true,true).reason)
        }
    }
}
