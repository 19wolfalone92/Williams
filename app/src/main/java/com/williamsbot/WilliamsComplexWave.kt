package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject

/** Complex motive/corrective wave PRICE geometry, research-only.
 * A completed price skeleton cannot prove internal 5-3-5, 3-3-5 or 3-3-3-3-3
 * segments. Confirmed pivots only. Never use these labels as order authority.
 * TC1 chapter 7 / Trading Chaos Course Level III; extra Elliott patterns are
 * engineering extensions, not quoted automatic Williams entry instructions.
 */
internal data class WilliamsComplexEvidence(
    val pattern:String, val direction:String, val startAt:Long, val endAt:Long,
    val knownAt:Long, val invalidation:Double, val reasons:List<String>
) {
    fun toJson():JSONObject=JSONObject().put("pattern",pattern).put("status","ALTERNATIVE")
        .put("direction",direction).put("start_at",startAt).put("end_at",endAt)
        .put("known_at",knownAt).put("invalidation",invalidation)
        .put("reasons",JSONArray(reasons)).put("can_place_orders",false)
}

internal fun detectWilliamsComplexWave(
    anchors:List<WilliamsWaveAnchor>, direction:String, asOf:Long
):List<WilliamsComplexEvidence> {
    if (direction !in listOf("UP","DOWN") || asOf <= 0 || anchors.isEmpty()) return emptyList()
    if (anchors.any { it.kind !in listOf("LOW","HIGH") || !it.price.isFinite() || it.price<=0.0 ||
            it.extremeAt<0 || it.knownAt<it.extremeAt || it.knownAt>asOf }) return emptyList()
    if (anchors.zipWithNext().any { (a,b) ->
            a.kind==b.kind || a.extremeAt>=b.extremeAt || a.knownAt>b.knownAt }) return emptyList()
    val sign=if (direction=="UP") 1.0 else -1.0
    val first=if(sign==1.0) "LOW" else "HIGH"
    val result=mutableListOf<WilliamsComplexEvidence>()
    fun add(pattern:String,p:List<WilliamsWaveAnchor>,reasons:List<String>,invalid:Double) {
        result+=WilliamsComplexEvidence(pattern,direction,p.first().extremeAt,p.last().extremeAt,
            p.maxOf { it.knownAt },invalid,reasons)
    }
    for (n in listOf(6,9)) {
        if(anchors.size<n) continue
        val p=anchors.takeLast(n)
        if(p.first().kind!=first) continue
        val x=p.map { it.price*sign }
        val w1=x[1]-x[0];val w3=x[3]-x[2];val w5=x[5]-x[4]
        if(w1<=0||w3<=0||w5<=0||!(x[0]<x[2]&&x[2]<x[1]) ||
           !(x[2]<x[4]&&x[4]<x[3])||x[3]<=x[1]||w3<minOf(w1,w5)) continue
        if(n==6) {
            if(x[4]<=x[1]) add("ENDING_OR_LEADING_DIAGONAL_CANDIDATE",p,
                listOf("W4_OVERLAPS_W1","INTERNAL_3_OR_5_LEGS_UNVERIFIED"),p[2].price)
            else if(x[5]<=x[3]) add("TRUNCATED_FIFTH_CANDIDATE",p,
                listOf("W5_DOES_NOT_EXCEED_W3","INTERNAL_SUBWAVES_UNVERIFIED"),p[4].price)
            else add("ORDINARY_IMPULSE_CANDIDATE",p,
                listOf("FIVE_PRICE_SWINGS_ONLY","INTERNAL_SUBWAVES_UNVERIFIED"),p[2].price)
        } else {
            val a=x[5]-x[6];val b=x[7]-x[6];val c=x[7]-x[8]
            if(a<=0 || b<=0 || c<=0) continue
            val fraction=b/a
            if(fraction<1.0 && x[8]<x[6]) add("ZIGZAG_ABC_CANDIDATE",p,
                listOf("B_BELOW_A_ORIGIN","INTERNAL_5_3_5_UNVERIFIED")+
                    (if(fraction>=0.90) listOf("DEEP_B_RETRACEMENT_ALTERNATIVE") else emptyList()),p[7].price)
            if(fraction>=0.90) {
                val kind=if(x[7]>x[5] && x[8]<x[6]) "EXPANDED_FLAT_ABC_CANDIDATE"
                    else if(x[7]>x[5]) "RUNNING_FLAT_ABC_CANDIDATE"
                    else if(x[8]>x[6]) "FLAT_ABC_INCOMPLETE_OR_TRUNCATED_C_CANDIDATE"
                    else "REGULAR_FLAT_ABC_CANDIDATE"
                add(kind,p,listOf("B_RETRACES_AT_LEAST_90_PERCENT_A","INTERNAL_3_3_5_UNVERIFIED")+
                    (if(x[7]<=x[5] && x[8]>x[6]) listOf("C_DOES_NOT_REACH_A") else emptyList()),p[7].price)
            }
        }
    }
    if(anchors.size>=6) {
        val p=anchors.takeLast(6)
        val highs=p.filter { it.kind=="HIGH" }.map { it.price }
        val lows=p.filter { it.kind=="LOW" }.map { it.price }
        if(highs.size==3 && lows.size==3 && highs.zipWithNext().all { (a,b) -> a>b } &&
           lows.zipWithNext().all { (a,b) -> a<b } && lows.max()<highs.min())
            add("CONTRACTING_TRIANGLE_ABCDE_CANDIDATE",p,
                listOf("HIGHER_LOWS_LOWER_HIGHS","INTERNAL_3_3_3_3_3_UNVERIFIED"),p[p.lastIndex-1].price)
    }
    if(anchors.size>=6) {
        val p=anchors.takeLast(6);val highs=p.filter { it.kind=="HIGH" }.map { it.price };val lows=p.filter { it.kind=="LOW" }.map { it.price }
        fun close(a:Double,b:Double)=kotlin.math.abs(a-b)<=maxOf(kotlin.math.abs(a),kotlin.math.abs(b))*1e-9
        if(highs.size==3 && lows.size==3 && lows.max()<highs.min()) {
            if(highs.zipWithNext().all { (a,b)->a<b } && lows.zipWithNext().all { (a,b)->a>b })
                add("EXPANDING_TRIANGLE_ABCDE_CANDIDATE",p,listOf("DIVERGING_BOUNDARIES","INTERNAL_3_3_3_3_3_UNVERIFIED"),p[p.lastIndex-1].price)
            else if(highs.zipWithNext().all { (a,b)->close(a,b) } && lows.zipWithNext().all { (a,b)->a<b } ||
                lows.zipWithNext().all { (a,b)->close(a,b) } && highs.zipWithNext().all { (a,b)->a>b })
                add("BARRIER_TRIANGLE_ABCDE_CANDIDATE",p,listOf("ONE_FLAT_ONE_CONVERGING_BOUNDARY","INTERNAL_3_3_3_3_3_UNVERIFIED"),p[p.lastIndex-1].price)
        }
    }
    if(anchors.size>=8) {
        val p=anchors.takeLast(8)
        add("W_X_Y_COMBINATION_UNRESOLVED",p,
            listOf("INTERNAL_CORRECTION_COMPONENTS_NOT_PROVEN"),p.first().price)
    }
    if(anchors.size>=12) {
        val p=anchors.takeLast(12)
        add("W_X_Y_X_Z_COMBINATION_UNRESOLVED",p,
            listOf("INTERNAL_CORRECTION_COMPONENTS_NOT_PROVEN"),p.first().price)
    }
    for((count,groups,name) in listOf(Triple(10,3,"W_X_Y_ZIGZAG_COMBINATION_CANDIDATE"),
        Triple(16,5,"W_X_Y_X_Z_ZIGZAG_COMBINATION_CANDIDATE"))) {
        if(anchors.size<count) continue
        val sub=anchors.takeLast(count)
        if(sub.first().kind==first) continue
        val values=sub.map { -sign*it.price };var valid=true
        for(g in 0 until groups) {
            val orientation=if(g%2==0) 1 else -1
            val p=values.subList(g*3,g*3+4).map { orientation*it }
            valid=valid && p[0]<p[2] && p[2]<p[1] && p[1]<p[3]
            if(g%2==1) valid=valid && values[g*3-3]<values[g*3+3] && values[g*3+3]<values[g*3]
            if(g%2==0 && g>0) valid=valid && values[g*3+3]>values[g*3-3]
        }
        if(valid) add(name,sub,listOf("ABC_COMPONENT_BOUNDARIES_OBSERVED","INTERNAL_COMPONENT_DEGREES_UNVERIFIED"),sub.first().price)
    }
    return result
}
