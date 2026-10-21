package com.williamsbot

import org.json.JSONObject

/** Diagnostic synthesis only. Profitunity MACD shares AO/AC components;
 * agreement is not three independent votes and never assigns a wave number. */
internal fun williamsMarketReading(bars:List<WilliamsWaveBar>,phaseContext:JSONObject,frame:String="1h"):JSONObject {
    val duration = williamsWaveFrames[frame]
        ?: return JSONObject().put("schema","WILLIAMS_CHART_READING_V1")
            .put("frame",frame).put("state","UNSUPPORTED_FRAME")
            .put("can_place_orders",false).put("can_close",false)
    val result=JSONObject().put("schema","WILLIAMS_CHART_READING_V1").put("state","INSUFFICIENT_HISTORY")
        .put("wave_phase","UNRESOLVED").put("frame",frame).put("can_place_orders",false).put("can_close",false)
        .put("numerical_interpretation",true)
    if(bars.size<39) return result
    if(!validWilliamsEditionBars(bars) || bars.zipWithNext().any { (a,b) -> b.t-a.t!=duration })
        return result.put("state","HISTORY_UNVERIFIED")
    val mids=bars.map { (it.h+it.l)/2 }
    fun mean(n:Int,i:Int)=mids.subList(i-n+1,i+1).average()
    val ao=bars.indices.map { if(it>=33) mean(5,it)-mean(34,it) else Double.NaN }
    fun signal(i:Int)=ao.subList(i-4,i+1).average()
    val i=bars.lastIndex;val current=ao[i];val previous=ao[i-1]
    val ac=current-signal(i);val priorAc=previous-signal(i-1)
    val fast=mean(5,i);val medium=mean(13,i);val slow=mean(34,i)
    val direction=if(fast>maxOf(medium,slow)) "UP" else if(fast<minOf(medium,slow)) "DOWN" else "NEUTRAL"
    val momentum=if(current>previous) "UP" else if(current<previous) "DOWN" else "NEUTRAL"
    val acceleration=if(ac>priorAc) "UP" else if(ac<priorAc) "DOWN" else "NEUTRAL"
    val rhythm=if(direction==momentum && direction==acceleration && direction!="NEUTRAL") "ALIGNED_ACCELERATION" else "MIXED_OR_DECELERATING"
    fun line(period:Int,shift:Int)=shiftAlligatorForDisplay(smmaSeededValues(mids,period),shift).map { it ?: Double.NaN }
    val jaw=line(13,8);val teeth=line(8,5);val lips=line(5,3)
    val phases=williamsAlligatorPhases(bars,jaw,teeth,lips)
    val frames=phaseContext.optJSONArray("frames")
    val h1=frames?.let { (0 until it.length()).mapNotNull { n -> it.optJSONObject(n) }.firstOrNull { f -> f.optString("frame")==frame } } ?: JSONObject()
    val hypotheses=h1.optJSONArray("hypotheses")
    val labels=hypotheses?.let { (0 until it.length()).mapNotNull { n -> it.optJSONObject(n) }
        .map { h -> h.optString("active_label") to h.optString("active_direction") }.distinct() }.orEmpty()
    // Each actual timeframe receives an INDEPENDENT observation of pivots
    // formed on that timeframe. This never alters TC2 H1 entry authorization
    // and cannot elevate containment to a certified Elliott wave degree.
    val local = if (bars.size>=40) {
        val pivots=confirmedWilliamsWaveAnchors(bars,duration)
        williamsHypothesesFromAnchors(frame,bars,pivots)
            .filter { it.status=="PROVISIONAL" || it.status=="ALTERNATIVE" }
            .takeLast(12)
    } else emptyList()
    val localWaveEvidence=org.json.JSONArray(local.map { candidate ->
        JSONObject().put("scenario_id",candidate.id)
            .put("last_confirmed_leg",candidate.label)
            .put("active_label",candidate.activeLabel)
            .put("active_direction",candidate.activeDirection)
            .put("status",candidate.status)
            .put("invalidation",candidate.invalidation)
            .put("last_pivot_known_at",candidate.anchors.last().knownAt)
            .put("wave_degree_confirmed",false)
            .put("can_place_orders",false)
    })
    val wave=if(h1.optString("state")=="ALTERNATIVES") "ALTERNATIVES" else if(labels.size==1) when(labels[0].first) {
        "W1" -> "START_CANDIDATE"
        "W3" -> "CONTINUATION_CANDIDATE"
        "W5" -> "LATE_IMPULSE_CANDIDATE"
        "W2","W4","A","B","C" -> "CORRECTION_CANDIDATE"
        else -> "UNRESOLVED"
    } else "UNRESOLVED"
    return result.put("state","OBSERVED").put("as_of_close_ms",bars.last().t+duration)
        .put("alligator",JSONObject().put("state",phases.last()).put("previous_state",phases[i-1])
            .put("sleep_observed","SLEEPING" in phases).put("jaw",jaw.last()).put("teeth",teeth.last()).put("lips",lips.last()))
        .put("ao",current).put("ac",ac).put("ao_direction",momentum).put("ac_direction",acceleration)
        .put("profitunity_macd",JSONObject().put("fast",5).put("slow",34).put("signal_period",5)
            .put("oscillator",current).put("signal",signal(i)).put("histogram",ac).put("shares_ao_and_ac",true)
            .put("momentum_direction",if(ac>0) "UP" else if(ac<0) "DOWN" else "NEUTRAL"))
        .put("twr",JSONObject().put("fast_5",fast).put("medium_13",medium).put("slow_34",slow).put("direction",direction))
        .put("rhythm_condition",rhythm).put("wave_phase",wave).put("wave_state",h1.optString("state","UNRESOLVED"))
        .put("exhaustion_warning",h1.optBoolean("exhaustion_warning",false))
        .put("independent_wave_hypotheses",localWaveEvidence)
        .put("independent_wave_hypothesis_count",local.size)
        .put("wave_degree_confirmed",false).put("probability_calibrated",false)
        .put("angulation",JSONObject().put("LONG",williamsAngulation("LONG",bars,jaw,teeth,lips).toJson())
            .put("SHORT",williamsAngulation("SHORT",bars,jaw,teeth,lips).toJson()))
}
