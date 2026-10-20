package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** Observational cross-timeframe context. Never authorizes a Futures order or exit. */
internal data class AdaptiveWaveResolution(
    val frame:String?,val observedBars:Int,val status:String,val wantedFrame:String?
) {
    fun toJson():JSONObject=JSONObject().put("frame",frame?:JSONObject.NULL)
        .put("observed_bars",observedBars).put("status",status)
        .put("wanted_frame",wantedFrame?:JSONObject.NULL)
        .put("can_place_orders",false)
}

/** A missing candle must not silently become a wave: use the actual contiguous tail. */
internal fun consecutiveClosedWaveSuffix(
    input:List<WilliamsWaveBar>,frame:String,asOf:Long
):List<WilliamsWaveBar> {
    val ms=williamsWaveFrames[frame]?:return emptyList()
    if(asOf<=0)return emptyList()
    val rows=input.filter { it.t>=0 && it.t+ms<=asOf &&
        listOf(it.o,it.h,it.l,it.c,it.v).all(Double::isFinite) &&
        it.l>0 && it.h>=maxOf(it.o,it.c,it.l) && it.l<=minOf(it.o,it.c) && it.v>=0
    }.sortedBy { it.t }
    if(rows.isEmpty() || asOf-rows.last().t-ms>=ms) return emptyList()
    var start=rows.lastIndex
    while(start>0 && rows[start].t-rows[start-1].t==ms) start--
    return rows.subList(start,rows.size)
}

/**
 * Validate an actual older wave segment independently of whether newer bars
 * for this short child timeframe have already been downloaded. Exactly
 * 34 genuine preceding bars are required for AO 5/34 warm-up. Reject any
 * duplicates, missing bars, or still-forming candles INSIDE the segment.
 */
internal fun verifiedAdaptiveWaveWindow(
    input:List<WilliamsWaveBar>,frame:String,
    startMs:Long,endMs:Long,asOf:Long,chronological:Boolean=false
):List<WilliamsWaveBar> {
    val ms=williamsWaveFrames[frame]?:return emptyList()
    if(startMs<=0L || endMs<=startMs || asOf<endMs) return emptyList()
    // Archive replay sorts each frame once. Binary selection avoids scanning
    // years of unrelated child history for every candidate on every close.
    val rows=if(chronological) input else input.filter { it.t>=0L && it.t<=asOf-ms }.sortedBy { it.t }
    fun lowerBound(time:Long):Int {
        var lo=0;var hi=rows.size
        while(lo<hi) {
            val mid=(lo+hi)/2
            if(rows[mid].t<time) lo=mid+1 else hi=mid
        }
        return lo
    }
    val first=lowerBound(startMs)
    val last=lowerBound(endMs)-1
    if(first<34 || last<first || rows[first].t>=startMs+ms ||
        rows[last].t+ms<endMs) return emptyList()
    val window=rows.subList(first-34,last+1)
    if(window.any { b->b.t<0L || b.t>asOf-ms ||
        listOf(b.o,b.h,b.l,b.c,b.v).any { !it.isFinite() } ||
        b.l<=0 || b.h<maxOf(b.o,b.c,b.l) || b.l>minOf(b.o,b.c) || b.v<0 }) return emptyList()
    if(window.zipWithNext().any { (prev,next)->next.t-prev.t!=ms })
        return emptyList()
    return window
}

/** Pick truthful child resolution when a parent wave has too few bars. */
internal fun chooseAdaptiveWaveResolution(
    startMs:Long,endMs:Long,preferredFrame:String,
    history:Map<String,List<WilliamsWaveBar>>,asOf:Long,chronological:Boolean=false
):AdaptiveWaveResolution {
    val baseMs=williamsWaveFrames[preferredFrame]
        ?:return AdaptiveWaveResolution(null,0,"UNSUPPORTED_PARENT_FRAME",null)
    if(startMs<=0 || endMs<=startMs || endMs>asOf)
        return AdaptiveWaveResolution(null,0,"INVALID_OR_FUTURE_TIME_WINDOW",null)
    val duration=endMs-startMs
    val ideal=suggestWilliamsWaveScale(duration,williamsWaveFrames)?.timeframe
    val target=duration/baseMs
    data class Candidate(val name:String,val count:Int)
    val matches=mutableListOf<Candidate>()
    for((frame,ms) in williamsWaveFrames) {
        // For too few bars use equal/finer real candles. For excessive
        // counts use equal/coarser resolution where available.
        if((target<100 && ms>baseMs) || (target>140 && ms<baseMs)) continue
        val window=verifiedAdaptiveWaveWindow(
            history[frame].orEmpty(),frame,startMs,endMs,asOf,chronological)
        if(window.isEmpty()) continue
        // The first 34 bars in this verified slice are AO warm-up.
        matches+=Candidate(frame,window.size-34)
    }
    val best=matches.filter { it.count in 100..140 }
        .minWithOrNull(compareBy<Candidate> { abs(it.count-120) }
            .thenBy { if(it.name==preferredFrame) 0 else 1 })
    if(best!=null) return AdaptiveWaveResolution(best.name,best.count,
        "BOOK_WINDOW_OBSERVED",ideal)
    val nearest=matches.minByOrNull { abs(it.count-120) }
    return if(nearest!=null) AdaptiveWaveResolution(nearest.name,nearest.count,
        "WRONG_SCALE_UNCONFIRMED",ideal)
    else AdaptiveWaveResolution(null,0,"MISSING_CLOSED_CHILD_BARS",ideal)
}

/**
 * Wave 4 AO observation: w3 momentum peak is located in the real third
 * price leg and the correction is checked AFTER the confirmed third price end.
 * Opposite side of zero is evidence, not independent proof of a completed W4.
 * Near/touch at 5% W3 amplitude is an explicitly non-book engineering tolerance.
 */
internal fun adaptiveWave4AoEvidence(
    scenario:WilliamsWaveScenario,
    resolution:AdaptiveWaveResolution,
    historical:Map<String,List<WilliamsWaveBar>>,asOf:Long,chronological:Boolean=false
):JSONObject {
    val out=JSONObject().put("status","UNVERIFIED")
        .put("can_place_orders",false).put("can_close_positions",false)
        .put("wave4_completion_confirmed",false)
        .put("resolution",resolution.toJson())
    if(scenario.anchors.size<5 || resolution.frame==null)
        return out.put("reason","FOURTH_WAVE_OR_RESOLUTION_NOT_PROVEN")
    val scaleVerified=resolution.status=="BOOK_WINDOW_OBSERVED"
    val p=scenario.anchors
    if(p.take(5).any { it.knownAt>asOf })
        return out.put("reason","PIVOTS_NOT_CAUSALLY_KNOWN")
    if(!(p[2].extremeAt<p[3].extremeAt && p[3].extremeAt<p[4].extremeAt))
        return out.put("reason","INVALID_CONFIRMED_SWING_ORDER")
    val frame=resolution.frame
    val ms=williamsWaveFrames.getValue(frame)
    val parentMs=williamsWaveFrames[scenario.frame]
        ?:return out.put("reason","UNKNOWN_PARENT_FRAME")
    if(ms>parentMs) return out.put("reason","COARSER_FRAME_ANCHOR_ORDER_UNVERIFIED")
    val rows=verifiedAdaptiveWaveWindow(
        historical[frame].orEmpty(),frame,p.first().extremeAt,
        p[4].extremeAt+parentMs,asOf,chronological)
    if(rows.isEmpty()) return out.put("reason","W4_REAL_CHILD_WINDOW_HAS_GAPS")
    fun actualExtreme(anchor:WilliamsWaveAnchor):Long?=rows.lastOrNull { b->
        b.t>=anchor.extremeAt && b.t<anchor.extremeAt+parentMs &&
            abs((if(anchor.kind=="HIGH") b.h else b.l)-anchor.price)<=maxOf(1e-9,anchor.price*1e-9)
    }?.t
    val cycleAt=actualExtreme(p[0])
        ?:return out.put("reason","CYCLE_ORIGIN_NOT_PRESENT_ON_SELECTED_FRAME")
    val secondAt=actualExtreme(p[2])
        ?:return out.put("reason","W2_EXTREME_NOT_PRESENT_ON_SELECTED_FRAME")
    val thirdAt=actualExtreme(p[3])
        ?:return out.put("reason","W3_EXTREME_NOT_PRESENT_ON_SELECTED_FRAME")
    val fourthAt=actualExtreme(p[4])
        ?:return out.put("reason","W4_EXTREME_NOT_PRESENT_ON_SELECTED_FRAME")
    val mids=rows.map { (it.h+it.l)/2.0 }
    val ao=rows.indices.map { i -> if(i<33) Double.NaN else
        (i-4..i).sumOf { mids[it] }/5.0 -
            (i-33..i).sumOf { mids[it] }/34.0 }
    val sign=if(scenario.direction=="UP") 1.0 else -1.0
    val thirdPriceIndex=rows.indexOfFirst { it.t==thirdAt }
    val fourthPriceIndex=rows.indexOfFirst { it.t==fourthAt }
    val momentum=evaluateWilliamsThirdMomentum(rows,ao,rows.indexOfFirst { it.t==cycleAt },
        thirdPriceIndex,fourthPriceIndex,scenario.direction,ms)
    val third=if(momentum.observed) momentum.peakIndex!! else rows.indices.filter {
        rows[it].t>=secondAt && it<=minOf(fourthPriceIndex,thirdPriceIndex+5) &&
        ao[it].isFinite() && sign*ao[it]>0.0 }
        .maxByOrNull { sign*ao[it] }
        ?:return out.put("reason","THIRD_WAVE_MOMENTUM_NOT_PROVEN")
    val fourth=rows.indices.filter { it>maxOf(thirdPriceIndex,third) &&
        rows[it].t<=fourthAt && ao[it].isFinite() }
    if(fourth.isEmpty()) return out.put("reason","FOURTH_WAVE_AO_NOT_AVAILABLE")
    val crossed=fourth.firstOrNull { sign*ao[it]<0.0 }
    val touched=fourth.firstOrNull { ao[it]==0.0 }
    val minimum=fourth.minOf { abs(ao[it]) }
    val near=minimum<=abs(ao[third])*0.05
    val step=williamsWaveFrames.getValue(frame)
    return out.put("status",when {
        !scaleVerified && crossed!=null->"WRONG_SCALE_CROSS_UNCONFIRMED"
        !scaleVerified && near->"WRONG_SCALE_TOUCH_NEAR_ZERO_UNCONFIRMED"
        !scaleVerified->"WRONG_SCALE_NO_ZERO_CROSS"
        !momentum.observed && crossed!=null->"W3_DEGREE_UNCONFIRMED_ZERO_CROSS"
        !momentum.observed->"W3_DEGREE_UNCONFIRMED"
        crossed!=null->"ZERO_CROSSED_MINIMUM_ONLY"
        touched!=null->"ZERO_TOUCHED_MINIMUM_UNCONFIRMED"
        near->"TOUCHED_OR_NEAR_ZERO_UNCONFIRMED"
        else->"NO_ZERO_CROSS"
    }).put("crossed_zero",crossed!=null)
        .put("touched_or_near_zero",crossed==null && near)
        .put("touched_zero_exactly",touched!=null)
        .put("near_zero_is_engineering_tolerance",true)
        .put("minimum_condition_observed",scaleVerified && momentum.observed && crossed!=null)
        .put("w3_momentum",momentum.toJson())
        .put("oscillator","SMA_5_MEDIAN_MINUS_SMA_34_MEDIAN_AO")
        .put("book_reference","TC1_PDF_59_63_5_34_COMPONENT_OF_MACD_5_34_5")
        .put("zero_test_uses_signal_line_spread",false)
        .put("w3_ao_peak",ao[third])
        .put("w3_ao_peak_ms",rows[third].t)
        .put("w2_actual_extreme_ms",secondAt).put("w3_actual_extreme_ms",thirdAt)
        .put("w4_actual_extreme_ms",fourthAt)
        .put("anchor_time_policy","LAST_MATCHING_REAL_CHILD_EXTREME_IN_PARENT_CANDLE")
        .put("wave4_min_absolute_ao",minimum)
        .put("w4_ao_bars",fourth.size)
        .put("cross_known_at_ms",crossed?.let { rows[it].t+step }?:JSONObject.NULL)
        .put("near_zero_ratio","0.05_ENGINEERING_UNCONFIRMED")
        .put("reason","AO_ZERO_ALONE_DOES_NOT_CONFIRM_WAVE4_END")
}

/**
 * Reconstruct independently H1, H4 and D1 competing counts from the phone's
 * persistent history. Parent-child links prove only observable time/price
 * containment, NOT an infallible nested Elliott count.
 */
internal fun buildAdaptiveHistoricalWaveReport(
    history:Map<String,List<WilliamsWaveBar>>,asOf:Long,
    analysisLimit:Int=1800
):JSONObject {
    require(analysisLimit>=40) { "WAVE_ANALYSIS_WINDOW_TOO_SMALL" }
    // Slice the causal prefix BEFORE imposing the working-memory window.
    // Otherwise appending future bars can evict candles already known at asOf.
    val closed=linkedMapOf<String,List<WilliamsWaveBar>>()
    val verified=linkedMapOf<String,List<WilliamsWaveBar>>()
    for((frame,ms) in williamsWaveFrames) {
        val rows=history[frame].orEmpty().filter { it.t>=0L && it.t<=asOf-ms }
            .sortedBy { it.t }
        if(rows.isNotEmpty()) closed[frame]=rows
        // Keep older targeted child windows even when the remaining recent
        // child candles are not yet present. Each chosen window independently
        // verifies 34 warm-up bars, continuity and actual candle closure.
        if(rows.size>=40) verified[frame]=rows.takeLast(analysisLimit)
    }
    val scenarios=mutableListOf<WilliamsWaveScenario>()
    val records=JSONArray()
    val anchorsByFrame=verified.mapValues { (frame,stored)->
        confirmedWilliamsWaveAnchors(consecutiveClosedWaveSuffix(stored,frame,asOf),
            williamsWaveFrames.getValue(frame))
    }
    // The execution hierarchy is unchanged. H4 is included explicitly here
    // so W3/H1 within a provisional W5/H4 can be observed next to D1 context.
    for(frame in listOf("1w","1d","12h","8h","6h","4h","2h","1h","30m","15m")) {
        val stored=verified[frame]?:continue
        val rows=consecutiveClosedWaveSuffix(stored,frame,asOf)
        if(rows.size<40) continue
        val pivots=anchorsByFrame.getValue(frame)
        val current=williamsHypothesesFromAnchors(frame,rows,pivots)
            .filter { it.status in setOf("PROVISIONAL","ALTERNATIVE","UNRESOLVED") }
        scenarios+=current
        for(s in current.takeLast(8)) {
            val end=if(s.anchors.size>=5)
                s.anchors[4].extremeAt+williamsWaveFrames.getValue(frame) else asOf
            val choice=chooseAdaptiveWaveResolution(
                s.anchors.first().extremeAt,minOf(asOf,end),frame,closed,asOf,true)
            val fourth=adaptiveWave4AoEvidence(s,choice,closed,asOf,true)
            records.put(JSONObject().put("scenario_id",s.id).put("frame",frame)
                .put("label",s.label).put("active_label",s.activeLabel)
                .put("direction",s.direction).put("hypothesis_status",s.status)
                .put("anchors",JSONArray(s.anchors.map { it.toJson() }))
                .put("known_at_ms",s.anchors.maxOf { it.knownAt })
                .put("wave_start_ms",s.anchors.first().extremeAt)
                .put("wave_end_ms",minOf(asOf,end))
                .put("scale",choice.toJson()).put("w4_ao",fourth))
        }
    }
    // Reuse the existing complex grammar on every observed degree, including
    // H4. Internal endpoints must be real, already-confirmed child pivots.
    val structures=JSONArray()
    for((frame,points) in anchorsByFrame) {
        val children=anchorsByFrame.filterKeys {
            williamsWaveFrames.getValue(it)<williamsWaveFrames.getValue(frame)
        }
        for(direction in listOf("UP","DOWN")) {
            for(evidence in detectWilliamsComplexWave(points,direction,asOf)) {
                val parent=points.filter { it.extremeAt in evidence.startAt..evidence.endAt }
                structures.put(adaptiveComplexSubdivisionEvidence(evidence,parent,children,
                    frame,asOf))
            }
        }
    }
    val links=linkWilliamsWaveHypotheses(scenarios)
    val counts=JSONObject()
    closed.forEach { (frame,rows)->counts.put(frame,rows.size) }
    return JSONObject().put("schema","ADAPTIVE_WILLIAMS_WAVE_HISTORY_V1")
        .put("as_of_closed_ms",asOf)
        .put("source","DEVICE_SQLITE_BINANCE_FUTURES_CLOSED_BARS")
        .put("historical_frame_counts",counts)
        .put("analysis_window_limit",analysisLimit)
        .put("history_scope","CAUSAL_SNAPSHOT_WITH_BOUNDED_PIVOT_WINDOW")
        .put("wave_resolution_target","APPROXIMATE_100_TO_140_NOT_STRICT")
        .put("hypotheses",records)
        .put("observed_parent_child_links",
            JSONArray(links.map { it.toJson() }))
        .put("senior_fifth_contexts",adaptiveSeniorFifthContexts(scenarios,links,asOf))
        .put("complex_wave_patterns",structures)
        .put("wave_genealogy_confirmed",false)
        .put("can_place_orders",false).put("can_close_positions",false)
}

/** A nearby TF is not necessarily the degree that resolves a 5/3 subdivision. */
internal fun adaptiveComplexSubdivisionEvidence(
    evidence:WilliamsComplexEvidence,parent:List<WilliamsWaveAnchor>,
    children:Map<String,List<WilliamsWaveAnchor>>,frame:String,asOf:Long
):JSONObject {
    val proofs=children.keys.sortedByDescending { williamsWaveFrames.getValue(it) }.map { child->
        child to enrichWilliamsComplexSubdivisions(evidence.pattern,parent,children.getValue(child),
            williamsWaveFrames.getValue(frame),asOf)
    }
    val matches=proofs.filter { it.second.status=="PRICE_SUBDIVISIONS_CONFIRMED" }
    val selected=matches.firstOrNull()?:proofs.firstOrNull()
    val proof=selected?.second?:enrichWilliamsComplexSubdivisions(evidence.pattern,parent,
        emptyList(),williamsWaveFrames.getValue(frame),asOf)
    return evidence.toJson().put("frame",frame).put("child_frame",selected?.first?:JSONObject.NULL)
        .put("internal_proof",proof.toJson())
        .put("child_frame_evidence",JSONArray(proofs.map { (f,p)->
            JSONObject().put("frame",f).put("proof",p.toJson())
        }))
        .put("multiple_child_resolutions",matches.size>1)
        .put("recursive_genealogy_proven",false)
}

/** Explicitly distinguish a child fifth from a fifth of the senior degree. */
internal fun adaptiveSeniorFifthContexts(
    scenarios:List<WilliamsWaveScenario>,links:List<WilliamsWaveLink>,asOf:Long
):JSONArray {
    val byId=scenarios.associateBy { it.id }
    return JSONArray(links.mapNotNull { link->
        val parent=byId[link.parent]?:return@mapNotNull null
        val child=byId[link.child]?:return@mapNotNull null
        if(parent.activeLabel!="W5" || (parent.anchors+child.anchors).any { it.knownAt>asOf })
            return@mapNotNull null
        JSONObject().put("parent",parent.id).put("parent_frame",parent.frame)
            .put("parent_active_label",parent.activeLabel)
            .put("child",child.id).put("child_frame",child.frame)
            .put("child_active_label",child.activeLabel).put("relationship",link.relationship)
            .put("known_at_ms",(parent.anchors+child.anchors).maxOf { it.knownAt })
            .put("proof","TIME_AND_PRICE_CONTAINMENT_ONLY")
            .put("senior_fifth_end_confirmed",false).put("can_close_positions",false)
    })
}
