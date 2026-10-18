package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

internal data class WilliamsSubdivisionProof(val status:String,val expected:List<Int>,val observed:List<Int>,
    val childKnownAt:Long,val reason:String) {
    fun toJson()=JSONObject().put("status",status).put("expected",JSONArray(expected)).put("observed",JSONArray(observed))
        .put("child_known_at",childKnownAt).put("reason",reason)
}
internal fun proveWilliamsWaveSubdivisions(parent:List<WilliamsWaveAnchor>,children:List<WilliamsWaveAnchor>,
    expected:List<Int>,parentDuration:Long,asOf:Long):WilliamsSubdivisionProof {
    fun unresolved(reason:String,observed:List<Int> = emptyList(),known:Long=0L)=WilliamsSubdivisionProof("UNRESOLVED",expected,observed,known,reason)
    if(parent.size!=expected.size+1 || parentDuration<=0 || expected.any { it !in listOf(3,5) }) return unresolved("INVALID_SUBDIVISION_CONTRACT")
    if(parent.isEmpty() || parent.any { it.knownAt>asOf }) return unresolved("PARENT_NOT_CONFIRMED")
    val known=children.filter { it.knownAt<=asOf }
    if((parent+known).any { !it.price.isFinite() || it.price<=0 || it.knownAt<it.extremeAt || it.kind !in listOf("LOW","HIGH") })
        return unresolved("INVALID_PIVOT")
    if(known.zipWithNext().any { (a,b) -> a.extremeAt>=b.extremeAt || a.kind==b.kind }) return unresolved("CHILD_ORDER_AMBIGUOUS")
    val endpoints=mutableListOf<Int>()
    for(p in parent) {
        val found=known.indices.filter { i -> val c=known[i]
            p.kind==c.kind && p.extremeAt<=c.extremeAt && c.extremeAt<p.extremeAt+parentDuration &&
                abs(p.price-c.price)<=maxOf(1e-9,p.price*1e-9) }
        if(found.size!=1) return unresolved("CHILD_ENDPOINT_MISSING_OR_AMBIGUOUS")
        endpoints+=found.single()
    }
    if(endpoints.zipWithNext().any { (a,b)->a>=b }) return unresolved("CHILD_ENDPOINT_ORDER_INVALID")
    val observed=endpoints.zipWithNext().map { (a,b)->b-a };var lastKnown=0L
    for(i in expected.indices) {
        val sub=known.subList(endpoints[i],endpoints[i+1]+1);val sign=if(sub.first().kind=="LOW") 1 else -1
        val p=sub.map { sign*it.price };val lo=minOf(sub.first().price,sub.last().price);val hi=maxOf(sub.first().price,sub.last().price)
        lastKnown=maxOf(lastKnown,sub.maxOf { it.knownAt })
        if(observed[i]!=expected[i] || sub.any { it.price<lo || it.price>hi }) return unresolved("SUBDIVISION_COUNT_OR_PRICE_CONTAINMENT_FAILED",observed,lastKnown)
        if(expected[i]==5) {
            val lengths=listOf(p[1]-p[0],p[3]-p[2],p[5]-p[4])
            if(!(p[0]<p[2] && p[2]<p[1] && p[1]<p[4] && p[4]<p[3] && p[3]<p[5]) || lengths[1]<minOf(lengths[0],lengths[2]))
                return unresolved("INTERNAL_IMPULSE_GEOMETRY_FAILED",observed,lastKnown)
        } else if(!(p[0]<p[2] && p[2]<p[1] && p[1]<p[3])) return unresolved("INTERNAL_ABC_GEOMETRY_FAILED",observed,lastKnown)
    }
    return WilliamsSubdivisionProof("PRICE_SUBDIVISIONS_CONFIRMED",expected,observed,lastKnown,"REAL_CONFIRMED_CHILD_PIVOTS")
}
internal fun enrichWilliamsComplexSubdivisions(pattern:String,parent:List<WilliamsWaveAnchor>,children:List<WilliamsWaveAnchor>,
    parentDuration:Long,asOf:Long):WilliamsSubdivisionProof {
    if(pattern.startsWith("FLAT_ABC_INCOMPLETE_OR_TRUNCATED_C"))
        return WilliamsSubdivisionProof("UNRESOLVED",listOf(3,3,5),emptyList(),0L,"C_END_OR_TRUNCATION_UNCONFIRMED")
    val expected=when {
        pattern.startsWith("ZIGZAG_ABC") -> listOf(5,3,5)
        pattern.contains("FLAT_ABC") -> listOf(3,3,5)
        pattern.contains("TRIANGLE") -> listOf(3,3,3,3,3)
        pattern.contains("ORDINARY_IMPULSE") || pattern.contains("TRUNCATED_FIFTH") -> listOf(5,3,5,3,5)
        pattern=="W_X_Y_ZIGZAG_COMBINATION_CANDIDATE" -> List(3) { listOf(5,3,5) }.flatten()
        pattern=="W_X_Y_X_Z_ZIGZAG_COMBINATION_CANDIDATE" -> List(5) { listOf(5,3,5) }.flatten()
        pattern.contains("ENDING_OR_LEADING_DIAGONAL") -> {
            val corrections=proveWilliamsWaveSubdivisions(parent.takeLast(6),children,listOf(3,3,3,3,3),parentDuration,asOf)
            if(corrections.status=="PRICE_SUBDIVISIONS_CONFIRMED") return corrections
            listOf(5,3,5,3,5)
        }
        else -> return WilliamsSubdivisionProof("UNRESOLVED",emptyList(),emptyList(),0L,"PATTERN_HAS_COMPETING_INTERNAL_GRAMMARS")
    }
    return proveWilliamsWaveSubdivisions(parent.takeLast(expected.size+1),children,expected,parentDuration,asOf)
}
