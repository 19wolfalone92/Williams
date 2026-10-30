package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject

/** Course PDF80-84. Price/volume/oscillator proxies do not certify a unique
 * Elliott degree. Only the source-owning execution door may submit an exit. */
internal val williamsCourseTerminalReasons=setOf("COURSE_W5_TERMINAL_CONFIRMED","COURSE_C_TERMINAL_CONFIRMED")
internal val williamsCourseOppositeReasons=setOf("COURSE_OPPOSITE_FORMATION_TRIGGERED","OPPOSITE_WILLIAMS_LEVEL_TRIGGERED","OPPOSITE_WILLIAMS_TRIGGER_IN_HIGHER_CORRECTION")
internal val williamsCourseFailureReasons=setOf("COURSE_W3_STALL_INVALIDATED","COURSE_W5_STALL_INVALIDATED","COURSE_CORRECTION_INVALIDATED")
internal val williamsCourseBookExitReasons=williamsCourseTerminalReasons+williamsCourseOppositeReasons+williamsCourseFailureReasons+
    setOf("COURSE_STAGE_TRIM","COURSE_HIGHER_FRAME_AIRBAG_TRIGGERED")
private fun courseExitRows(a:JSONArray?)=if(a==null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }
private fun courseExitAnchors(s:JSONObject,asOf:Long):List<JSONObject>? {
    val reasons=s.optJSONArray("invalid_reasons")
    if(s.optString("status")=="INVALIDATED" && reasons!=null && reasons.length()>0 &&
        (0 until reasons.length()).all { reasons.optString(it)=="LIVE_PRICE_INVALIDATED_COUNT" })
        return williamsCourseCausalAnchors(JSONObject(s.toString()).put("status","PROVISIONAL"),asOf)
    return williamsCourseCausalAnchors(s,asOf)
}
private fun courseExitTerminal(s:JSONObject,asOf:Long):List<JSONObject>? {
    val a=courseExitAnchors(s,asOf) ?: return null
    if(a.size<6) return null
    val frozen=JSONObject(s.toString()).put("anchors",JSONArray(a.take(6))).put("label","W5").put("status","PROVISIONAL")
    return a.take(6).takeIf { williamsCourseFourSignEvidence(frozen).optString("status")=="FOUR_PROXY_FLAGS_OBSERVED_NOT_CERTIFIED" }
}

internal fun williamsCourseExitPlan(h:JSONObject,side:String,originAt:Long,stage:String,openedAt:Long,nowMs:Long,
    history:List<WilliamsWaveBar>,contract:Double,correction:JSONObject=JSONObject()):JSONObject {
    val out=JSONObject().put("status","BLOCKED").put("blocker","COURSE_EXIT_OWNER_OR_HISTORY_UNVERIFIED")
        .put("can_place_orders",false).put("can_close",false).put("can_reverse",false).put("wave_degree_confirmed",false)
    fun blocked(why:String)=JSONObject(out.toString()).put("blocker",why)
    val asOf=h.optLong("as_of");val errors=h.optJSONObject("frame_errors") ?: JSONObject()
    val sign=when(side) { "LONG","BUY"->1.0;"SHORT","SELL"->-1.0;else->return out }
    if(originAt<=0 || originAt>=openedAt || openedAt>nowMs || asOf<=0 || asOf>nowMs || nowMs-asOf>=WILLIAMS_H1_MS ||
        errors.has("1h") || !contract.isFinite() || contract<=0) return out
    val bars=history.filter { it.t+WILLIAMS_H1_MS<=asOf }
    if(bars.size<45 || nowMs-(bars.last().t+WILLIAMS_H1_MS) !in 0 until WILLIAMS_H1_MS ||
        bars.any { it.t<=0 || listOf(it.o,it.h,it.l,it.c).any { v -> !v.isFinite() || v<=0 } || it.h<maxOf(it.o,it.c,it.l) || it.l>minOf(it.o,it.c) } ||
        bars.zipWithNext().any { (x,y) -> y.t-x.t!=WILLIAMS_H1_MS }) return blocked("COURSE_EXIT_CLOSED_H1_HISTORY_UNVERIFIED")
    val corrective=stage in setOf("A_ENTRY","A_REDUCE","C_ADD")
    val direction=if(sign>0) "UP" else "DOWN"
    val wanted=if(corrective) { if(sign>0) "DOWN" else "UP" } else direction
    val all=courseExitRows(h.optJSONArray("scenarios"))
    val candidates=all.mapNotNull { s -> courseExitAnchors(s,asOf)?.takeIf { a ->
        s.optString("frame")=="1h" && s.optString("direction")==wanted && a.first().getLong("extreme_at")==originAt
    }?.let { s to it } }
    if(candidates.size!=1) return blocked("COURSE_EXIT_PINNED_PARENT_AMBIGUOUS_OR_MISSING")
    val (parent,a)=candidates.single()
    fun observed(reason:String,known:Long,end:JSONObject,child:JSONObject?=null,boundary:Double?=null):JSONObject {
        if(known<=openedAt || known>asOf || (reason in williamsCourseTerminalReasons && nowMs-known>18_000_000L) || end.getLong("extreme_at")<openedAt)
            return blocked("COURSE_EXIT_PREENTRY_OR_STALE_EVIDENCE")
        val p=JSONObject(out.toString()).put("status","OBSERVED_COURSE_SOURCE_RULE").put("blocker",JSONObject.NULL)
            .put("reason",reason).put("origin_at",originAt).put("signal_close_ms",known)
            .put("parent_id",parent.optString("scenario_id")).put("terminal_id",child?.optString("scenario_id") ?: "")
            .put("source","COURSE_PDF80_84").put("trigger_price_basis","CONTRACT_PRICE")
            .put("rule_id","COURSE:$reason:$originAt:${end.getLong("extreme_at")}:$known")
        if(boundary!=null) p.put("boundary",boundary)
        return p
    }
    if(corrective) {
        if(correction.optString("motive_campaign_id").isBlank() || correction.optLong("flat_confirmed_at_ms")<=0 ||
            correction.optLong("flat_confirmed_at_ms")>=openedAt || a.size<6) return blocked("COURSE_CORRECTION_FLAT_HANDOFF_UNVERIFIED")
        val tick=correction.optDouble("tick",Double.NaN)
        if(!tick.isFinite() || tick<=0) return blocked("COURSE_CORRECTION_TICK_UNVERIFIED")
        val boundary=a[5].getDouble("price")-sign*tick
        if(sign*(contract-boundary)<=0) return JSONObject(out.toString()).put("status","OBSERVED_COURSE_SOURCE_RULE")
            .put("blocker",JSONObject.NULL).put("reason","COURSE_CORRECTION_INVALIDATED").put("origin_at",originAt)
            .put("source_known_at_ms",a[5].getLong("known_at")).put("boundary",boundary).put("source","COURSE_PDF83_84")
            .put("trigger_price_basis","CONTRACT_PRICE").put("rule_id","COURSE:COURSE_CORRECTION_INVALIDATED:$originAt:${a[5].getLong("extreme_at")}")
    }
    if(stage=="W3" && a.size==3) {
        val rows=bars.filter { it.t>=a[2].getLong("extreme_at") }
        val w1=sign*(a[1].getDouble("price")-a[0].getDouble("price"))
        if(rows.isNotEmpty() && w1>0) {
            val peak=rows.maxOf { sign*(if(sign>0) it.h else it.l) }
            val ratio=(peak-sign*a[2].getDouble("price"))/w1
            val reached=rows.firstOrNull { sign*((if(sign>0) it.h else it.l)-a[2].getDouble("price"))>=w1 }
            if(reached!=null && ratio>=1 && ratio<1.1 && sign*(contract-a[1].getDouble("price"))<=0)
                return observed("COURSE_W3_STALL_INVALIDATED",reached.t+WILLIAMS_H1_MS,JSONObject().put("extreme_at",reached.t),boundary=a[1].getDouble("price"))
        }
    }
    if(stage=="W5" && a.size>=5) {
        val children=all.mapNotNull { s -> courseExitAnchors(s,asOf)?.takeIf { c -> c.size==4 &&
            !errors.has(s.optString("frame")) && (williamsWaveFrames[s.optString("frame")] ?: WILLIAMS_H1_MS+1)<=WILLIAMS_H1_MS &&
            s.optString("direction")==direction && c[0].getLong("extreme_at")==a[4].getLong("extreme_at") &&
            c[0].getDouble("price")==a[4].getDouble("price") && sign*(contract-c[1].getDouble("price"))<=0
        }?.let { s to it } }
        if(children.size==1) {
            val (s,c)=children.single()
            return observed("COURSE_W5_STALL_INVALIDATED",c[3].getLong("known_at"),c[3],s,c[1].getDouble("price"))
        }
        if(children.size>1) return blocked("COURSE_EXIT_INTERNAL_DEGREE_AMBIGUOUS")
    }
    val start:JSONObject;val end:JSONObject;val reason:String
    if(stage=="W5" && a.size>=6) {
        if(courseExitTerminal(parent,asOf)==null) return blocked("COURSE_W5_TERMINAL_SIGNS_INCOMPLETE")
        start=a[4];end=a[5];reason="COURSE_W5_TERMINAL_CONFIRMED"
    } else if(stage=="C_ADD" && a.size>=9) {
        start=a[7];end=a[8];reason="COURSE_C_TERMINAL_CONFIRMED"
    } else return blocked("COURSE_TERMINAL_STAGE_NOT_OBSERVED")
    val children=all.mapNotNull { s -> courseExitTerminal(s,asOf)?.takeIf { c ->
        !errors.has(s.optString("frame")) && (williamsWaveFrames[s.optString("frame")] ?: WILLIAMS_H1_MS+1)<=WILLIAMS_H1_MS &&
            s.optString("direction")==direction && c.first().getLong("extreme_at")==start.getLong("extreme_at") &&
            c.last().getLong("extreme_at")==end.getLong("extreme_at") && c.first().getDouble("price")==start.getDouble("price") &&
            c.last().getDouble("price")==end.getDouble("price")
    }?.let { s to it } }
    if(children.size!=1) return blocked("COURSE_TERMINAL_SUBDIVISION_AMBIGUOUS_OR_MISSING")
    val (child,c)=children.single()
    return observed(reason,maxOf(end.getLong("known_at"),c.last().getLong("known_at")),end,child)
}

internal fun williamsCourseExitProofBlocker(p:JSONObject?,ownerId:String,symbol:String,side:String,reason:String,openedAt:Long,nowMs:Long):String? {
    if(p==null || ownerId.isBlank() || p.optString("owner_id")!=ownerId || p.optString("symbol")!=symbol || p.optString("side")!=side ||
        p.optString("reason")!=reason || reason !in williamsCourseBookExitReasons || p.optString("trigger_price_basis")!="CONTRACT_PRICE")
        return "COURSE_EXIT_DURABLE_OWNER_OR_RULE_UNVERIFIED"
    val observed=p.optLong("observed_at_ms")
    return if(openedAt<=0 || openedAt>observed || observed>nowMs || nowMs-observed>120_000L) "COURSE_EXIT_DURABLE_EVIDENCE_STALE" else null
}

internal fun williamsCourseClosedMotiveHandoff(ownerId:String,side:String,originAt:Long,reason:String,flatConfirmedAt:Long,
    signedExitFilled:Boolean,signedFlat:Boolean,ordersClean:Boolean):JSONObject? {
    if(ownerId.isBlank() || side !in setOf("LONG","SHORT","BUY","SELL") || originAt<=0 || originAt>=flatConfirmedAt ||
        reason!="COURSE_W5_TERMINAL_CONFIRMED" || !signedExitFilled || !signedFlat || !ordersClean) return null
    return JSONObject().put("motive_campaign_id",ownerId).put("motive_side",side).put("motive_origin_at",originAt)
        .put("flat_confirmed_at_ms",flatConfirmedAt).put("source","SIGNED_FLAT_COURSE_MOTIVE")
        .put("side",if(side in setOf("BUY","LONG")) "SHORT" else "LONG").put("can_place_orders",false)
}
