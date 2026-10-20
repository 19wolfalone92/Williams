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
