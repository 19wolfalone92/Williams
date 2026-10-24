package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

internal data class CourseExitCaseData(val report:JSONObject,val side:String,val origin:Long,val stage:String,
    val opened:Long,val now:Long,val bars:List<WilliamsWaveBar>,val contract:Double,val correction:JSONObject) {
    fun observe()=williamsCourseExitPlan(report,side,origin,stage,opened,now,bars,contract,correction)
}

/** Explicit synthetic source cases, not independent historical Elliott labels. */
internal fun courseExitCase(c:JSONObject,motiveSide:String,base:Long=0L,mirrorSum:Double=400.0):CourseExitCaseData {
    val hour=3_600_000L;val kind=c.getString("kind");val fault=c.optString("fault")
    val corrective=kind in setOf("C_ADD","C_INVALID")
    val side=if(corrective) { if(motiveSide=="LONG") "SHORT" else "LONG" } else motiveSide
    val direction=if(motiveSide=="LONG") "UP" else "DOWN"
    fun flip(p:Double)=if(motiveSide=="LONG") p else mirrorSum-p
    val end=when { corrective->180;fault=="OLD_REACH"->157;kind=="W3"->124;kind=="W5_STALL"->146;else->150 }
    val asOf=base+(end+3)*hour
    val count=when { corrective->9;kind=="W3"->3;kind=="W5_STALL"->5;else->6 }
    val raw=listOf(100.0,120.0,108.0,148.0,130.0,160.0,120.0,142.0,105.0)
    fun anchor(p:Double,t:Int,i:Int,duration:Long,dir:String)=JSONObject()
        .put("kind",if((i%2==0)==(dir=="UP")) "LOW" else "HIGH").put("price",p)
        .put("extreme_at",base+t*hour).put("known_at",base+t*hour+3*duration)
    val parent=JSONObject().put("scenario_id","course-parent").put("frame","1h").put("status","PROVISIONAL").put("direction",direction)
        .put("label",when { corrective->"C";count==6->"W5";count==5->"W4";else->"W2" }).put("observed_at",asOf)
        .put("scale_frame","30m").put("scale_bars",101).put("target_zone",true).put("divergence",true).put("squat",true).put("terminal_fractal",true)
        .put("anchors",JSONArray(raw.take(count).mapIndexed { i,p -> anchor(flip(p),100+i*10,i,hour,direction) }))
    val childDirection=if(corrective) { if(direction=="UP") "DOWN" else "UP" } else direction
    val childPrices=when { corrective->listOf(142.0,128.0,135.0,111.0,120.0,105.0)
        kind=="W5_STALL"->listOf(130.0,145.0,138.0,158.0);else->listOf(130.0,140.0,135.0,155.0,145.0,160.0) }
    val start=if(corrective) 170 else 140
    val child=JSONObject().put("scenario_id","course-child").put("frame","5m").put("status","PROVISIONAL").put("direction",childDirection)
        .put("label",if(kind=="W5_STALL") "W3" else "W5").put("observed_at",asOf).put("scale_bars",121)
        .put("target_zone",true).put("divergence",true).put("squat",true).put("terminal_fractal",true)
        .put("anchors",JSONArray(childPrices.mapIndexed { i,p -> anchor(flip(p),start+i*2,i,300_000L,childDirection) }))
    val peak=when(fault) { "NOT_REACHED"->127.0;"EXTENDED"->131.0;else->129.0 }
    val knots=when(kind) {
        "W3"->listOf(0 to 100.0,100 to 100.0,110 to 120.0,120 to 108.0,124 to peak,126 to minOf(peak-1,128.0))
        "W5_STALL"->listOf(0 to 100.0,100 to 100.0,110 to 120.0,120 to 108.0,130 to 148.0,140 to 130.0,142 to 145.0,144 to 138.0,146 to 158.0,148 to 150.0)
        else->listOf(0 to 100.0)+raw.mapIndexed { i,p ->100+i*10 to p }
    }
    fun price(i:Int):Double {
        for((x,y) in knots.zipWithNext()) if(i in x.first..y.first)
            return x.second+(y.second-x.second)*(i-x.first)/(y.first-x.first)
        return knots.last().second
    }
    var bars=(1 until end+3).map { i -> val p=flip(price(i));WilliamsWaveBar(base+i*hour,p,p+.05,p-.05,p,1000.0+i) }
    val contract=flip(if(fault=="NOT_CROSSED") 120.1 else when { kind=="C_INVALID"->161.0;corrective->106.0;kind=="W3"->119.9;kind=="W5_STALL"->144.9;else->159.0 })
    val scenarios=JSONArray(if(kind=="W3") listOf(parent) else listOf(parent,child))
    val report=JSONObject().put("as_of",asOf).put("frame_errors",JSONObject()).put("scenarios",scenarios)
    var origin=base+100*hour;var opened=base+(if(corrective) 155 else 101)*hour;var now=asOf+1000
    val correction=if(corrective) JSONObject().put("motive_campaign_id","motive-owner").put("flat_confirmed_at_ms",base+154*hour).put("tick",.1) else JSONObject()
    when(fault) {
        "CHILD_MISSING"->scenarios.remove(1)
        "CHILD_AMBIGUOUS"->scenarios.put(JSONObject(child.toString()))
        "PARENT_AMBIGUOUS"->scenarios.put(JSONObject(parent.toString()))
        "PARENT_SIGN_MISSING"->parent.put("divergence",false)
        "CHILD_SIGN_MISSING"->child.put("squat",false)
        "FUTURE_CHILD"->child.getJSONArray("anchors").getJSONObject(child.getJSONArray("anchors").length()-1).put("known_at",asOf+1)
        "PREENTRY"->opened=asOf+1
        "ORIGIN"->origin-=hour
        "GAP"->bars=bars.filterIndexed { i,_ ->i!=80 }
        "H1_ERROR"->report.getJSONObject("frame_errors").put("1h","MISSING")
        "CHILD_ERROR"->report.getJSONObject("frame_errors").put("5m","MISSING")
        "STALE"->now+=hour
        "NO_HANDOFF"->correction.remove("motive_campaign_id")
        "LATE_FLAT"->correction.put("flat_confirmed_at_ms",opened)
        "LIVE_INVALIDATED","OTHER_INVALIDATED"->child.put("status","INVALIDATED").put("invalid_reasons",JSONArray().put(
            if(fault=="LIVE_INVALIDATED") "LIVE_PRICE_INVALIDATED_COUNT" else "W3_SHORTEST_OR_NEGATIVE_MOTIVE_LEG"))
    }
    return CourseExitCaseData(report,side,origin,if(corrective) "C_ADD" else if(kind=="W5_STALL") "W5" else kind,opened,now,bars,contract,correction)
}

class WilliamsCourseExitTest {
    @Test fun sharedTerminalAndFailedCountCasesRequireCausalPinnedEvidence() {
        val cases=JSONObject(javaClass.classLoader!!.getResource("williams_course_exit_contract.json")!!.readText()).getJSONArray("cases")
        for(side in listOf("LONG","SHORT")) for(i in 0 until cases.length()) {
            val c=cases.getJSONObject(i);val data=courseExitCase(c,side);val p=data.observe()
            assertEquals(c.getString("id")+side+p,c.getString("expected"),if(p.optString("status")=="OBSERVED_COURSE_SOURCE_RULE") p.getString("reason") else "BLOCKED")
            for(field in listOf("can_place_orders","can_close","can_reverse","wave_degree_confirmed")) assertFalse(p.getBoolean(field))
        }
    }
    @Test fun correctionHandoffNeedsFilledExitSignedFlatAndCleanOrdersIndependently() {
        for(side in listOf("LONG","SHORT")) for(filled in listOf(false,true)) for(flat in listOf(false,true)) for(clean in listOf(false,true)) {
            val p=williamsCourseClosedMotiveHandoff("owner",side,100,"COURSE_W5_TERMINAL_CONFIRMED",200,filled,flat,clean)
            assertEquals(filled&&flat&&clean,p!=null)
            if(p!=null) { assertNotEquals(side,p.getString("side"));assertFalse(p.getBoolean("can_place_orders")) }
        }
    }
    @Test fun exitProofCannotBeReplayedOnDifferentOwnerPriceBasisOrClock() {
        val now=200_000L;val opened=100_000L
        val p=JSONObject().put("owner_id","owner").put("symbol","BTCUSDT").put("side","LONG")
            .put("reason","COURSE_W5_TERMINAL_CONFIRMED").put("trigger_price_basis","CONTRACT_PRICE").put("observed_at_ms",now-1000)
        fun blocked(proof:JSONObject)=williamsCourseExitProofBlocker(proof,"owner","BTCUSDT","LONG","COURSE_W5_TERMINAL_CONFIRMED",opened,now)
        assertNull(blocked(p))
        for((key,value) in listOf("owner_id" to "other","symbol" to "ETHUSDT","side" to "SHORT","reason" to "TAKE_PROFIT",
            "trigger_price_basis" to "MARK_PRICE","observed_at_ms" to now+1,"observed_at_ms" to 1L))
            assertNotNull(blocked(JSONObject(p.toString()).put(key,value)))
        assertFalse("CORRECTION_PARENT_PRICE_INVALIDATED" in williamsCourseBookExitReasons)
    }
}
