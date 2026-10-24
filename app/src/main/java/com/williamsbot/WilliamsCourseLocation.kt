package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject

/** Course PDF77-84. Example stages are not active Elliott labels. These
 * price predicates do not certify a degree or authorize new exposure. */
private const val courseLocationSource="COURSE_LEVEL_III_PDF77_84"
private fun courseRows(a:JSONArray?)=if(a==null) emptyList() else
    (0 until a.length()).mapNotNull { a.optJSONObject(it) }

internal fun williamsCourseCausalAnchors(s:JSONObject,asOf:Long):List<JSONObject>? {
    if(s.optString("status")!="PROVISIONAL") return null
    val raw=s.optJSONArray("anchors") ?: return null
    val a=courseRows(raw);val observed=s.optLong("observed_at")
    val duration=williamsWaveFrames[s.optString("frame")] ?: return null
    if(a.isEmpty() || a.size!=raw.length() || observed !in 1L..asOf) return null
    val first=when(s.optString("direction")) { "UP"->"LOW";"DOWN"->"HIGH";else->return null }
    if(a.withIndex().any { (i,p) ->
        val price=p.optDouble("price",Double.NaN)
        !price.isFinite() || price<=0 || p.optLong("extreme_at")<=0 ||
            p.optLong("known_at")<=p.optLong("extreme_at") || p.optLong("known_at")>observed ||
            p.optLong("known_at")-p.optLong("extreme_at")<3*duration ||
            p.optString("kind")!=(if(i%2==0) first else if(first=="LOW") "HIGH" else "LOW")
    } || a.zipWithNext().any { (x,y) -> x.optLong("extreme_at")>=y.optLong("extreme_at") ||
        x.optLong("known_at")>=y.optLong("known_at") ||
        (if(x.getString("kind")=="LOW") x.getDouble("price")>=y.getDouble("price") else x.getDouble("price")<=y.getDouble("price")) }) return null
    return a
}

internal fun williamsCoursePriceLocation(stage:String,side:String,s:JSONObject,price:Double,asOf:Long):JSONObject {
    val out=JSONObject().put("source",courseLocationSource).put("stage",stage)
        .put("status","UNVERIFIED_PRICE_LOCATION").put("reason","COURSE_ANCHORS_OR_PRICE_UNVERIFIED")
        .put("can_place_orders",false).put("can_close",false).put("can_reverse",false).put("wave_degree_confirmed",false)
    val a=williamsCourseCausalAnchors(s,asOf) ?: return out
    val sign=when(side) { "LONG","BUY"->1.0;"SHORT","SELL"->-1.0;else->return out }
    val required=mapOf("W1" to 2,"W2" to 2,"W3" to 3,"W3_EXTENSION" to 3,"W4" to 4,"W5" to 5,"EXIT" to 6)[stage] ?: return out
    if(a.size!=required || s.optString("direction")!=(if(sign>0) "UP" else "DOWN") || !price.isFinite() || price<=0) return out
    val p=a.take(required).map { sign*it.getDouble("price") }
    if(p[1]<=p[0] || (required>=3 && !(p[0]<p[2] && p[2]<p[1])) ||
        (required>=4 && p[3]<=p[1]) || (required>=5 && !(p[1]<p[4] && p[4]<p[3])))
        return out.put("reason","COURSE_MOTIVE_PRICE_GRAMMAR_UNVERIFIED")
    out.put("origin_at",a.first().getLong("extreme_at")).put("scenario_id",s.optString("scenario_id"))
        .put("evidence_known_at",a.take(required).maxOf { it.getLong("known_at") })
        .put("status","LOCATION_ONLY_REQUIRES_SOURCE_CONFIRMATION").put("reason","COURSE_TERMINAL_OR_RETRACEMENT_SIGNS_REQUIRED")
    if(stage in listOf("W1","W3","W5")) {
        val base=if(stage=="W5") p[2] else p[0];val top=if(stage=="W5") p[3] else p[1]
        val low=if(stage=="W5") .38 else .50;val high=if(stage=="W5") .50 else .62
        val ratio=(top-sign*price)/(top-base)
        val levels=listOf(sign*(top-high*(top-base)),sign*(top-low*(top-base))).sorted()
        val inside=ratio>=low-1e-12 && ratio<=high+1e-12
        out.put("retracement_ratio",ratio).put("retracement_range",JSONArray(listOf(low,high)))
            .put("price_zone",JSONArray(levels)).put("price_in_zone",inside)
            .put("reason",if(inside) "COURSE_PRICE_ZONE_OBSERVED" else "COURSE_PRICE_OUTSIDE_RETRACEMENT_ZONE")
        if(stage in listOf("W3","W5")) {
            val pivot=if(stage=="W3") p[2] else p[4];val pivotRatio=(top-pivot)/(top-base)
            out.put("confirmed_retracement_ratio",pivotRatio)
            if(pivotRatio<low-1e-12 || pivotRatio>high+1e-12)
                out.put("price_in_zone",false).put("reason","COURSE_CONFIRMED_RETRACEMENT_OUTSIDE_ZONE")
        }
    } else if(stage=="W3_EXTENSION") {
        val ratio=(sign*price-p[2])/(p[1]-p[0]);val inside=ratio>=1.1-1e-12
        out.put("w3_to_w1_ratio",ratio).put("price_level",sign*(p[2]+1.1*(p[1]-p[0])))
            .put("price_in_zone",inside).put("reason",if(inside) "COURSE_W3_110_PERCENT_OBSERVED" else "COURSE_W3_BELOW_110_PERCENT")
    } else out.put("price_in_zone",false).put("reason","COURSE_MATCHED_TERMINAL_STRUCTURE_REQUIRED")
    if(required>=4) out.put("w3_to_w1_ratio",(p[3]-p[2])/(p[1]-p[0]))
    return out.put("invalidation_level",a[if(stage in listOf("W5","EXIT")) 1 else 0].getDouble("price"))
        .put("invalidation_is_anchor_not_exchange_stop",true)
        .put("example_target_units",williamsCourseTarget(stage,out.optDouble("w3_to_w1_ratio",Double.NaN)) ?: JSONObject.NULL)
        .put("quantity_basis","ILLUSTRATIVE_CONTRACT_UNITS_NOT_RISK_WEIGHTS_OR_BINANCE_LOTS")
        .put("required_source_evidence",JSONArray(when(stage) {
            "W1"->listOf("PRIOR_OPPOSITE_FIVE_WAVE_TERMINAL","RETRACEMENT_TURN")
            "W2"->listOf("MATCHED_W1_TERMINAL_SUBDIVISION")
            "W3"->listOf("W2_CONFIRMED_FRACTAL","SQUAT_AMONG_THREE_W2_EXTREMES")
            "W3_EXTENSION"->listOf("OWNED_W3_CAMPAIGN","NEW_RISK_GATES")
            "W4"->listOf("MATCHED_W3_TERMINAL_SUBDIVISION","W3_AO_PEAK_STRONGER_THAN_W1")
            "W5"->listOf("W4_AO_ZERO","TWO_W4_FRACTALS","SQUAT_AMONG_THREE_W4_EXTREMES")
            else->listOf("MATCHED_W5_TERMINAL_SUBDIVISION","TERMINAL_TARGET")
        }))
}

internal fun williamsCourseStageLocations(s:JSONObject,asOf:Long):JSONArray {
    val stages=when(s.optJSONArray("anchors")?.length()) {
        2->listOf("W1","W2");3->listOf("W3","W3_EXTENSION");4->listOf("W4");5->listOf("W5");6->listOf("EXIT");else->emptyList()
    }
    return JSONArray(stages.map { williamsCoursePriceLocation(it,if(s.optString("direction")=="UP") "LONG" else "SHORT",
        s,s.optDouble("observed_close_price",Double.NaN),asOf) })
}

/** Only matched, causal terminal evidence may request a Course W2/W4 trim.
 * Any genuinely available contained child frame may supply the five legs;
 * M15 is not a mandatory execution timeframe. */
internal fun williamsCourseTrimProof(h:JSONObject,side:String,originAt:Long,previousStage:String,
    openedAt:Long,nowMs:Long,bars:List<WilliamsWaveBar>):JSONObject {
    val out=JSONObject().put("status","BLOCKED").put("reason","COURSE_STAGE_ORIGIN_OR_HISTORY_UNVERIFIED")
        .put("source",courseLocationSource).put("can_place_orders",false).put("can_close",false)
        .put("can_reverse",false).put("wave_degree_confirmed",false)
    val asOf=h.optLong("as_of");val errors=h.optJSONObject("frame_errors") ?: JSONObject()
    val expected=when(previousStage) { "W1"->"W2";"W3","W3_EXTENSION"->"W4";else->return out }
    if(originAt<=0 || originAt>=openedAt || openedAt>nowMs || asOf<=0 || asOf>nowMs ||
        nowMs-asOf>=3_600_000L || errors.has("1h")) return out
    val sign=when(side) { "LONG","BUY"->1.0;"SHORT","SELL"->-1.0;else->return out }
    val all=courseRows(h.optJSONArray("scenarios"))
    val parents=all.filter { s ->
        val a=williamsCourseCausalAnchors(s,asOf)
        a!=null && s.optString("frame")=="1h" && s.optString("direction")== (if(sign>0) "UP" else "DOWN")
    }.filter { s -> val a=s.getJSONArray("anchors")
        s.optString("active_label")==expected && a.length()==(if(expected=="W2") 2 else 4) && a.getJSONObject(0).getLong("extreme_at")==originAt }
    if(parents.size!=1) return out.put("reason","COURSE_MATCHED_PARENT_AMBIGUOUS_OR_MISSING")
    val parent=parents.single();val a=courseRows(parent.getJSONArray("anchors"))
    val start=a[a.size-2];val end=a.last()
    val children=all.filter { s ->
        val c=williamsCourseCausalAnchors(s,asOf)
        c!=null && !errors.has(s.optString("frame")) && (williamsWaveFrames[s.optString("frame")] ?: 3_600_001L)<=3_600_000L &&
            s.optString("direction")==parent.getString("direction") && c.size==6 &&
            c.first().getLong("extreme_at")==start.getLong("extreme_at") && c.last().getLong("extreme_at")==end.getLong("extreme_at") &&
            c.first().getDouble("price")==start.getDouble("price") && c.last().getDouble("price")==end.getDouble("price") &&
            williamsCourseFourSignEvidence(s).getString("status")=="FOUR_PROXY_FLAGS_OBSERVED_NOT_CERTIFIED"
    }
    if(children.size!=1) return out.put("reason","COURSE_TERMINAL_DEGREE_AMBIGUOUS_OR_INCOMPLETE")
    val child=children.single();val known=maxOf(end.getLong("known_at"),child.getJSONArray("anchors").getJSONObject(5).getLong("known_at"))
    if(end.getLong("extreme_at")<openedAt || known<=openedAt || known>asOf || nowMs-known>18_000_000L)
        return out.put("reason","COURSE_TERMINAL_PREENTRY_OR_STALE")
    if(expected=="W4") {
        val rows=bars.filter { it.t+3_600_000L<=asOf }
        if(rows.size<40 || rows.zipWithNext().any { (x,y) -> y.t-x.t!=3_600_000L } ||
            rows.first().t+33*3_600_000L>a[0].getLong("extreme_at") || rows.last().t<end.getLong("extreme_at"))
            return out.put("reason","COURSE_W3_W1_MOMENTUM_HISTORY_UNVERIFIED")
        val med=rows.map { (it.h+it.l)/2 }
        val osc=rows.indices.map { i -> if(i<33) Double.NaN else med.subList(i-4,i+1).sum()/5-med.subList(i-33,i+1).sum()/34 }
        val peaks=listOf(a[0] to a[1],a[2] to a[3]).map { (left,right) -> rows.indices.filter { i ->
            rows[i].t in left.getLong("extreme_at")..right.getLong("extreme_at") && osc[i].isFinite()
        }.maxOfOrNull { sign*osc[it] } ?: Double.NaN }
        if(peaks.any { !it.isFinite() || it<=0 } || peaks[1]<=peaks[0])
            return out.put("reason","COURSE_W3_OSCILLATOR_NOT_STRONGER_THAN_W1")
    }
    return out.put("status","OBSERVED_SOURCE_PINNED_REDUCTION").put("reason","COURSE_MATCHED_TERMINAL_PROXY_CRITERIA")
        .put("stage",expected).put("target_units",if(expected=="W2") 1 else 3).put("origin_at",originAt)
        .put("parent_id",parent.optString("scenario_id")).put("terminal_id",child.optString("scenario_id"))
        .put("signal_close_ms",known)
}
