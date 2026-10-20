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
