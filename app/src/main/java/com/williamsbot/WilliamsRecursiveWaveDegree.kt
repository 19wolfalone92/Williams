package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * Read-only, causally confirmed multi-degree PRICE grammar.
 * Course PDF59-60 wave checklist, not an independent Elliott certification.
 * No exchange, order, stop, position, account or trading permissions.
 */
internal fun williamsCourseChecklistLeg(leg:String,observedSwings:Int):String = when(leg) {
    "W1" -> when(observedSwings) {3->"RECOUNT_AS_CORRECTION_NOT_TREND_REVERSAL";5->"SMALLER_DEGREE_MOTIVE_CANDIDATE";else->"UNRESOLVED"}
    "W2","W4" -> when(observedSwings) {3->"SIDEWAYS_CORRECTION_CANDIDATE";5->"SHARP_CORRECTION_CANDIDATE";else->"UNRESOLVED"}
    "W3" -> when(observedSwings) {3->"RECOUNT_W3_WRONG_WAVE_NUMBER";5->"W3_PRICE_SUBDIVISIONS_CANDIDATE";else->"UNRESOLVED"}
    "W5" -> when(observedSwings) {3->"DIAGONAL_OR_CONTINUATION_CANDIDATE";5->"MOTIVE_OR_DIAGONAL_CANDIDATE";else->"UNRESOLVED"}
    else -> "UNRESOLVED"
}

internal fun traceWilliamsRecursivePriceGrammar(
    pattern:String,
    parent:List<WilliamsWaveAnchor>,
    pivotsByFrame:Map<String,List<WilliamsWaveAnchor>>,
    frameDurations:Map<String,Long>,
    parentFrame:String,
    asOf:Long,
    depth:Int=3,
    maxNodes:Int=64
):JSONObject {
    fun output(status:String,reason:String,frame:String="",child:String="",
               proof:WilliamsSubdivisionProof?=null,
               checklist:JSONArray=JSONArray(),branches:JSONArray=JSONArray()) =
        JSONObject().put("status",status).put("reason",reason)
            .put("frame",frame).put("child_frame",child)
            .put("internal_proof",proof?.toJson() ?: JSONObject.NULL)
            .put("checklist",checklist).put("branches",branches)
            .put("source","COURSE_PDF59_60_CHECKLIST_AND_CONFIRMED_PRICE_PIVOTS")
            .put("unique_elliott_genealogy_proven",false)
            .put("can_place_orders",false).put("can_close",false).put("can_reverse",false)

    if (asOf<=0 || depth !in 1..3 || maxNodes !in 1..256 || parentFrame !in frameDurations)
        return output("UNRESOLVED","INVALID_RECURSIVE_DEGREE_CONTRACT")

    val rootDuration=frameDurations.getValue(parentFrame)
    val frames=listOf(parentFrame)+pivotsByFrame.keys.filter {
        it in frameDurations && frameDurations.getValue(it)>0L && frameDurations.getValue(it)<rootDuration
    }.sortedByDescending { frameDurations.getValue(it) }
    if (frames.zipWithNext().any { (a,b) -> frameDurations.getValue(a)<=frameDurations.getValue(b) })
        return output("UNRESOLVED","NON_DESCENDING_REAL_TIMEFRAMES")
    var budget=0
    fun matching(p:WilliamsWaveAnchor,c:WilliamsWaveAnchor,duration:Long):Boolean =
        p.kind==c.kind && c.extremeAt>=p.extremeAt && c.extremeAt<p.extremeAt+duration &&
        abs(p.price-c.price)<=maxOf(1e-9,abs(p.price)*1e-9)

    fun walk(points:List<WilliamsWaveAnchor>,expected:List<Int>,index:Int,remaining:Int,root:Boolean=false):JSONObject {
        budget++
        val frame=frames[index]
        if(budget>maxNodes) return output("UNRESOLVED","RECURSIVE_NODE_BUDGET_EXHAUSTED",frame)
        if(index+1>=frames.size) return output("UNRESOLVED","CHILD_TIMEFRAME_UNAVAILABLE",frame)
        val child=frames[index+1]
        val duration=frameDurations.getValue(frame)
        val anchors=pivotsByFrame[child].orEmpty().filter { it.knownAt<=asOf }
        val proof=if(root) enrichWilliamsComplexSubdivisions(pattern,points,anchors,duration,asOf)
            else proveWilliamsWaveSubdivisions(points,anchors,expected,duration,asOf)
        val labels=when(proof.expected.size) {
            5->listOf("W1","W2","W3","W4","W5")
            3->listOf("A","B","C")
            else->proof.expected.indices.map { "L${it+1}" }
        }
        val checklist=JSONArray()
        for ((i,count) in proof.observed.withIndex()) {
            val label=labels.getOrElse(i) { "L${i+1}" }
            checklist.put(JSONObject().put("leg",label).put("observed_swings",count)
                .put("course_branch",williamsCourseChecklistLeg(label,count)))
        }
        if(proof.status!="PRICE_SUBDIVISIONS_CONFIRMED")
            return output("UNRESOLVED",proof.reason,frame,child,proof,checklist)
        if(remaining==1)
            return output("OBSERVED_DEPTH_PRICE_GRAMMAR_CONFIRMED",
                "ALL_REQUESTED_PRICE_DEPTHS_OBSERVED",frame,child,proof,checklist)

        val indices=mutableListOf<Int>()
        for(p in points) {
            val matches=anchors.indices.filter { matching(p,anchors[it],duration) }
            if(matches.size!=1)
                return output("PARTIAL_PRICE_GRAMMAR","DESCENDANT_ENDPOINT_AMBIGUOUS",frame,child,proof,checklist)
            indices+=matches.single()
        }
        if(indices.zipWithNext().any { (a,b)->a>=b })
            return output("PARTIAL_PRICE_GRAMMAR","DESCENDANT_ENDPOINT_ORDER_INVALID",frame,child,proof,checklist)
        fun shapeGrammar(shape:String):List<Int>?=when(shape) {
            "IMPULSE_PRICE_SKELETON","TRUNCATED_IMPULSE_PRICE_SKELETON"->listOf(5,3,5,3,5)
            "ZIGZAG_PRICE_SKELETON"->listOf(5,3,5)
            "REGULAR_FLAT_PRICE_SKELETON","EXPANDED_FLAT_PRICE_SKELETON",
            "RUNNING_FLAT_PRICE_SKELETON"->listOf(3,3,5)
            else->null
        }
        val branches=JSONArray()
        for((i,shape) in proof.legPatterns.withIndex()) {
            val nested=shapeGrammar(shape)
            val result=if(nested==null) output("UNRESOLVED","UNKNOWN_CHILD_GRAMMAR",child)
                else walk(anchors.subList(indices[i],indices[i+1]+1),nested,index+1,remaining-1)
            branches.put(result)
        }
        val all=(0 until branches.length()).all {
            branches.getJSONObject(it).getString("status")=="OBSERVED_DEPTH_PRICE_GRAMMAR_CONFIRMED"
        }
        return output(if(all) "OBSERVED_DEPTH_PRICE_GRAMMAR_CONFIRMED" else "PARTIAL_PRICE_GRAMMAR",
            if(all) "ALL_REQUESTED_PRICE_DEPTHS_OBSERVED" else "SOME_RECURSIVE_LEGS_UNRESOLVED",
            frame,child,proof,checklist,branches)
    }
    return walk(parent,emptyList(),0,depth,true)
        .put("requested_depth",depth)
        .put("frame_path",JSONArray(frames.take(depth+1)))
        .put("observed_node_count",budget)
}
