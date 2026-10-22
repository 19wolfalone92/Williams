package com.williamsbot

import org.json.JSONObject

/** Diagnostic synthesis only. Profitunity MACD shares AO/AC components;
 * agreement is not three independent votes and never assigns a wave number. */
internal fun williamsMarketReading(bars:List<WilliamsWaveBar>,phaseContext:JSONObject,
    frame:String="1h",asOf:Long?=null):JSONObject {
    val result=JSONObject().put("schema","WILLIAMS_CHART_READING_V1").put("state","INSUFFICIENT_HISTORY")
        .put("wave_phase","UNRESOLVED").put("can_place_orders",false).put("can_close",false)
        .put("numerical_interpretation",true).put("frame",frame).put("closed_bar_count",bars.size)
        .put("minimum_bars",39)
    val duration=williamsWaveFrames[frame] ?: return result.put("state","UNSUPPORTED_FRAME")
    if(bars.size<39) return result
    if(!validWilliamsEditionBars(bars) || bars.zipWithNext().any { (a,b) -> b.t-a.t!=duration } ||
        asOf!=null && bars.any { it.t+duration>asOf })
        return result.put("state","HISTORY_UNVERIFIED")
    result.put("as_of_close_ms",bars.last().t+duration)
    if(asOf!=null && asOf-(bars.last().t+duration)>=duration) return result.put("state","STALE_HISTORY")
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
    val jawRaw=smmaSeededValues(mids,13);val teethRaw=smmaSeededValues(mids,8);val lipsRaw=smmaSeededValues(mids,5)
    val jaw=shiftAlligatorForDisplay(jawRaw,8).map { it ?: Double.NaN }
    val teeth=shiftAlligatorForDisplay(teethRaw,5).map { it ?: Double.NaN }
    val lips=shiftAlligatorForDisplay(lipsRaw,3).map { it ?: Double.NaN }
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
    val order=if(lips.last()>teeth.last() && teeth.last()>jaw.last()) "UP"
        else if(lips.last()<teeth.last() && teeth.last()<jaw.last()) "DOWN" else "MIXED"
    val mouth=listOf(jaw.last(),teeth.last(),lips.last())
    // TC2 original pp105-106/PDF134-135: display the already computed
    // forward-shifted tail. All three lines are known together for only 3
    // future plot slots. Never extrapolate a missing Lips/Teeth value.
    val tail=org.json.JSONArray((1..8).map { step -> JSONObject()
        .put("plot_at_ms",bars.last().t+step*duration)
        .put("jaw",jawRaw.getOrNull(i+step-8) ?: JSONObject.NULL)
        .put("teeth",teethRaw.getOrNull(i+step-5) ?: JSONObject.NULL)
        .put("lips",lipsRaw.getOrNull(i+step-3) ?: JSONObject.NULL) })
    val common=listOf(jawRaw[i+3-8]!!,teethRaw[i+3-5]!!,lipsRaw[i]!!)
    val tailOrder=if(common[2]>common[1] && common[1]>common[0]) "UP"
        else if(common[2]<common[1] && common[1]<common[0]) "DOWN" else "MIXED"
    val widthChange=(common.max()-common.min())-(mouth.max()-mouth.min())
    val widthTolerance=bars.last().c*1e-10
    val price=if(bars.last().c>mouth.max()) "ABOVE" else if(bars.last().c<mouth.min()) "BELOW" else "INSIDE"
    val bias=if(order=="UP" && price=="ABOVE" && current>0) "UP"
        else if(order=="DOWN" && price=="BELOW" && current<0) "DOWN" else "MIXED"
    val range=bars.takeLast(140);val low=range.minOf { it.l };val high=range.maxOf { it.h }
    return result.put("state","OBSERVED")
        .put("alligator",JSONObject().put("state",phases.last()).put("previous_state",phases[i-1])
            .put("sleep_observed","SLEEPING" in phases).put("order",order)
            .put("jaw",jaw.last()).put("teeth",teeth.last()).put("lips",lips.last()))
        .put("alligator_known_tail",JSONObject().put("source","TC2_PRINT_105_106_KNOWN_SMMA_PLOT_OFFSETS")
            .put("source_through_close_ms",bars.last().t+duration).put("common_known_plot_steps",3)
            .put("order",tailOrder).put("width_change",when {
                widthChange>widthTolerance -> "WIDENING"; widthChange < -widthTolerance -> "NARROWING"; else -> "UNCHANGED"
            }).put("points",tail).put("uses_future_prices",false).put("extrapolated",false)
            .put("price_forecast",false).put("can_place_orders",false))
        .put("last_close",bars.last().c).put("price_vs_mouth",price).put("observed_bias",bias)
        .put("bias_is_forecast",false)
        .put("range_location",JSONObject().put("bars",range.size).put("low",low).put("high",high)
            .put("position",if(high>low) ((bars.last().c-low)/(high-low)).coerceIn(0.0,1.0) else JSONObject.NULL)
            .put("is_wave_number",false))
        .put("ao",current).put("ac",ac).put("ao_direction",momentum).put("ac_direction",acceleration)
        .put("ao_sign",if(current>0) "POSITIVE" else if(current<0) "NEGATIVE" else "ZERO")
        .put("ac_sign",if(ac>0) "POSITIVE" else if(ac<0) "NEGATIVE" else "ZERO")
        .put("zone",if(momentum=="UP" && acceleration=="UP") "GREEN"
            else if(momentum=="DOWN" && acceleration=="DOWN") "RED" else "GRAY")
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
