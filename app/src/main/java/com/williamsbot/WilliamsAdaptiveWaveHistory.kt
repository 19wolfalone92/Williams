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

/** Pick truthful child resolution when a parent wave has too few bars. */
internal fun chooseAdaptiveWaveResolution(
    startMs:Long,endMs:Long,preferredFrame:String,
    history:Map<String,List<WilliamsWaveBar>>,asOf:Long
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
        val series=consecutiveClosedWaveSuffix(history[frame].orEmpty(),frame,asOf)
        val first=series.indexOfFirst { it.t>=startMs }
        val last=series.indexOfLast { it.t<endMs }
        // AO(5,34) needs real preceding candles, never zero padding.
        if(first<34 || last<first || series[first].t>=startMs+ms ||
            series[last].t+ms<endMs) continue
        matches+=Candidate(frame,last-first+1)
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
    historical:Map<String,List<WilliamsWaveBar>>,asOf:Long
):JSONObject {
    val out=JSONObject().put("status","UNVERIFIED")
        .put("can_place_orders",false).put("can_close_positions",false)
        .put("wave4_completion_confirmed",false)
        .put("resolution",resolution.toJson())
    if(scenario.anchors.size<5 || resolution.frame==null ||
        resolution.status!="BOOK_WINDOW_OBSERVED")
        return out.put("reason","FOURTH_WAVE_OR_RESOLUTION_NOT_PROVEN")
    val p=scenario.anchors
    if(p.take(5).any { it.knownAt>asOf })
        return out.put("reason","PIVOTS_NOT_CAUSALLY_KNOWN")
    if(!(p[2].extremeAt<p[3].extremeAt && p[3].extremeAt<p[4].extremeAt))
        return out.put("reason","INVALID_CONFIRMED_SWING_ORDER")
    val frame=resolution.frame
    val rows=consecutiveClosedWaveSuffix(historical[frame].orEmpty(),frame,asOf)
    val mids=rows.map { (it.h+it.l)/2.0 }
    val ao=rows.indices.map { i -> if(i<33) Double.NaN else
        (i-4..i).sumOf { mids[it] }/5.0 -
            (i-33..i).sumOf { mids[it] }/34.0 }
    val sign=if(scenario.direction=="UP") 1.0 else -1.0
    val third=rows.indices.filter { rows[it].t in p[2].extremeAt..p[3].extremeAt &&
        ao[it].isFinite() && sign*ao[it]>0.0 }
        .maxByOrNull { sign*ao[it] }
        ?:return out.put("reason","THIRD_WAVE_MOMENTUM_NOT_PROVEN")
    val fourth=rows.indices.filter { rows[it].t>p[3].extremeAt &&
        rows[it].t<=p[4].extremeAt && ao[it].isFinite() }
    if(fourth.isEmpty()) return out.put("reason","FOURTH_WAVE_AO_NOT_AVAILABLE")
    val crossed=fourth.firstOrNull { sign*ao[it]<0.0 }
    val minimum=fourth.minOf { abs(ao[it]) }
    val near=minimum<=abs(ao[third])*0.05
    val step=williamsWaveFrames.getValue(frame)
    return out.put("status",when {
        crossed!=null->"ZERO_CROSSED_MINIMUM_ONLY"
        near->"TOUCHED_OR_NEAR_ZERO_UNCONFIRMED"
        else->"NO_ZERO_CROSS"
    }).put("crossed_zero",crossed!=null)
        .put("touched_or_near_zero",crossed==null && near)
        .put("w3_ao_peak",ao[third])
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
    history:Map<String,List<WilliamsWaveBar>>,asOf:Long
):JSONObject {
    val verified=linkedMapOf<String,List<WilliamsWaveBar>>()
    for((frame,rows) in history) if(frame in williamsWaveFrames) {
        val closed=consecutiveClosedWaveSuffix(rows,frame,asOf)
        if(closed.size>=40) verified[frame]=closed.takeLast(1800)
    }
    val scenarios=mutableListOf<WilliamsWaveScenario>()
    val records=JSONArray()
    // The execution hierarchy is unchanged. H4 is included explicitly here
    // so W3/H1 within a provisional W5/H4 can be observed next to D1 context.
    for(frame in listOf("1d","6h","4h","1h","30m","15m")) {
        val rows=verified[frame]?:continue
        val pivots=confirmedWilliamsWaveAnchors(rows,williamsWaveFrames.getValue(frame))
        val current=williamsHypothesesFromAnchors(frame,rows,pivots)
            .filter { it.status in setOf("PROVISIONAL","ALTERNATIVE","UNRESOLVED") }
        scenarios+=current
        for(s in current.takeLast(8)) {
            val end=if(s.anchors.size>=5)
                s.anchors[4].extremeAt+williamsWaveFrames.getValue(frame) else asOf
            val choice=chooseAdaptiveWaveResolution(
                s.anchors.first().extremeAt,minOf(asOf,end),frame,verified,asOf)
            val fourth=adaptiveWave4AoEvidence(s,choice,verified,asOf)
            records.put(JSONObject().put("scenario_id",s.id).put("frame",frame)
                .put("label",s.label).put("active_label",s.activeLabel)
                .put("direction",s.direction).put("hypothesis_status",s.status)
                .put("known_at_ms",s.anchors.maxOf { it.knownAt })
                .put("scale",choice.toJson()).put("w4_ao",fourth))
        }
    }
    val counts=JSONObject()
    history.forEach { (frame,rows)->counts.put(frame,rows.size) }
    return JSONObject().put("schema","ADAPTIVE_WILLIAMS_WAVE_HISTORY_V1")
        .put("as_of_closed_ms",asOf)
        .put("source","DEVICE_SQLITE_BINANCE_FUTURES_CLOSED_BARS")
        .put("historical_frame_counts",counts)
        .put("wave_resolution_target","APPROXIMATE_100_TO_140_NOT_STRICT")
        .put("hypotheses",records)
        .put("observed_parent_child_links",
            JSONArray(linkWilliamsWaveHypotheses(scenarios).map { it.toJson() }))
        .put("wave_genealogy_confirmed",false)
        .put("can_place_orders",false).put("can_close_positions",false)
}
