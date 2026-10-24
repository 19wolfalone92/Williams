package com.williamsbot

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WilliamsCourseCorrectionTest {
    @Test fun firstARetracementDoesNotRequireTheFutureEndOfA() {
        val s=WilliamsCourseCorrectionState("correction","motive","LONG",100)
        val e=WilliamsCourseCorrectionEvidence(120.0,100.0,Double.NaN,110.0,200,true,true)
        val p=planWilliamsCourseCorrection(s,"A_ENTRY",e,300,301,true,true,true,true)
        assertEquals("PROPOSED",p.status);assertEquals(3,p.targetUnits)
    }

    @Test fun sharedSourceZonesRequireCausalEvidenceAndDistinctFlatOwner() {
        val payload=javaClass.classLoader!!.getResource("williams_course_correction_contract.json")!!.readText()
        val cases=JSONObject(payload).getJSONArray("cases")
        for(side in listOf("LONG","SHORT")) for(i in 0 until cases.length()) {
            val c=cases.getJSONObject(i)
            fun mirror(p:Double)=if(side=="LONG") p else 200-p
            val s=WilliamsCourseCorrectionState("correction","motive",side,100,
                c.getString("previous"),c.getInt("units"))
            val e=WilliamsCourseCorrectionEvidence(mirror(120.0),mirror(100.0),mirror(80.0),
                mirror(c.getDouble("price")),c.optLong("known_at",200),c.optBoolean("degree",true),true,
                c.optBoolean("a_complete",true),c.optBoolean("c_complete",true))
            val p=planWilliamsCourseCorrection(s,c.getString("stage"),e,300,301,
                c.optBoolean("flat",true),true,c.optBoolean("protected",true),c.optBoolean("risk",true))
            assertEquals(c.getString("id")+side,c.getString("expected"),
                if(p.status=="PROPOSED") p.status else p.reason)
            assertEquals(if(side=="LONG") "SHORT" else "LONG",p.side)
            assertFalse(p.canPlaceOrders)
            if(p.status=="PROPOSED") {
                assertEquals(courseCorrectionTargets[c.getString("stage")],p.targetUnits)
                assertEquals(p.targetUnits-s.confirmedUnits,p.delta)
            }
        }
    }

    @Test fun correctiveLifecycleRequiresExactFillsRejectsForeignOwnersAndIsIdempotent() {
        for(side in listOf("LONG","SHORT")) {
            var s=WilliamsCourseCorrectionState("correction","motive",side,100)
            fun mirror(p:Double)=if(side=="LONG") p else 200-p
            for((i,stage) in courseCorrectionStages.withIndex()) {
                val close=300L+i*100L
                val e=WilliamsCourseCorrectionEvidence(mirror(120.0),mirror(100.0),mirror(80.0),
                    mirror(listOf(110.0,80.0,100.0,60.0)[i]),close-1,true,true,true,true)
                val p=planWilliamsCourseCorrection(s,stage,e,close,close+1,true,true,true,true)
                assertEquals("PROPOSED",p.status)
                val attempts=listOf<()->Unit>(
                    { acknowledgeWilliamsCourseCorrectionFill(s,p,"partial",p.delta-1,p.targetUnits,close,true,true) },
                    { acknowledgeWilliamsCourseCorrectionFill(s.copy(motiveCampaignId="foreign"),p,"foreign",p.delta,p.targetUnits,close,true,true) },
                    { acknowledgeWilliamsCourseCorrectionFill(s,p.copy(side=side),"wrong-side",p.delta,p.targetUnits,close,true,true) },
                    { acknowledgeWilliamsCourseCorrectionFill(s,p,"unprotected",p.delta,p.targetUnits,close,true,false) })
                for(attempt in attempts) {
                    try { attempt();fail("Unverified correction transition") } catch(_:IllegalArgumentException) { }
                }
                s=acknowledgeWilliamsCourseCorrectionFill(s,p,"fill-$i",p.delta,p.targetUnits,close,true,true)
                assertEquals(s,acknowledgeWilliamsCourseCorrectionFill(s,p,"fill-$i",p.delta,p.targetUnits,close,true,true))
            }
            assertEquals(0,s.confirmedUnits);assertEquals(4,s.revision)
        }
        val phase=williamsMarketPhaseContext(JSONObject())
        assertEquals(listOf(3,1,4,0),phase.getJSONObject("course_correction_policy").getJSONArray("targets")
            .let { a -> (0 until a.length()).map { a.getInt(it) } })
        assertFalse(phase.getJSONObject("course_correction_policy").getBoolean("can_place_orders"))
    }
}
