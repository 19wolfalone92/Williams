package com.williamsbot

import org.json.JSONObject
import kotlin.math.abs

/** TC2 PDF122-124: last mouth passage, highs on a downward path and lows
 * on an upward path, compared with Jaw/Teeth over the same interval.
 * Regression is an explicit numerical interpretation of the visual rule. */
internal data class WilliamsAngulation(
    val valid:Boolean=false,val score:Double=0.0,val reason:String="MOUTH_CROSSING_UNOBSERVED",
    val anchor:Int?=null,val examinedBars:Int=0,val priceSlope:Double?=null,
    val jawSlope:Double?=null,val teethSlope:Double?=null
) {
    fun toJson():JSONObject=JSONObject().put("valid",valid).put("score",score).put("reason",reason)
        .put("method","MOUTH_ANCHORED_EDGE_SLOPES").put("numerical_interpretation",true)
        .put("anchor_index",anchor ?: JSONObject.NULL).put("examined_bars",examinedBars)
        .put("price_slope",priceSlope ?: JSONObject.NULL).put("jaw_slope",jawSlope ?: JSONObject.NULL)
        .put("teeth_slope",teethSlope ?: JSONObject.NULL)
}
internal fun williamsAngulation(side:String,bars:List<WilliamsWaveBar>,jaw:List<Double>,
    teeth:List<Double>,lips:List<Double>):WilliamsAngulation {
    val n=bars.size
    if(side !in listOf("LONG","SHORT") || n<3 || listOf(jaw,teeth,lips).any { it.size!=n })
        return WilliamsAngulation(reason="INVALID_GEOMETRY_INPUT")
    fun usable(i:Int)=listOf(bars[i].h,bars[i].l,bars[i].c,jaw[i],teeth[i],lips[i]).all { it.isFinite() && it>0 } &&
        bars[i].c in bars[i].l..bars[i].h
    var anchor:Int?=null
    for(i in n-2 downTo 0) {
        if(!usable(i)) break
        if(bars[i].h>=minOf(jaw[i],teeth[i],lips[i]) && bars[i].l<=maxOf(jaw[i],teeth[i],lips[i])) {
            anchor=i;break
        }
    }
    val start=anchor ?: return WilliamsAngulation()
    if(n-start<3 || !(start until n).all { usable(it) }) return WilliamsAngulation(reason="INSUFFICIENT_PATH",anchor=start)
    fun slope(values:List<Double>):Double {
        val x=(values.size-1)/2.0;val mean=values.average()
        return values.indices.sumOf { (it-x)*(values[it]-mean) }/values.indices.sumOf { (it-x)*(it-x) }
    }
    val sign=if(side=="LONG") -1 else 1
    val edge=bars.drop(start).map { if(side=="LONG") it.h else it.l }
    val price=slope(edge);val j=slope(jaw.drop(start));val t=slope(teeth.drop(start))
    val separation=minOf(sign*(price-j),sign*(price-t))
    val endpoint=listOf(jaw,teeth).minOf { sign*((edge.last()-edge.first())-(it.last()-it[start])) }
    val valid=sign*price>0 && sign*(edge.last()-edge.first())>0 && separation>0 && endpoint>0
    return WilliamsAngulation(valid,if(valid) separation/bars.last().c*100 else 0.0,
        if(valid) "DIVERGING_PRICE_PATH" else "PRICE_PATH_NOT_STEEPER",start,n-start,price,j,t)
}

/** NTD ch3/ch10. A narrowing ordered trend is satiated, not sleeping.
 * Two intertwined observations distinguish sleep from loss of trend order.
 * This persistence is a numerical interpretation, not an author's bar count. */
internal fun williamsAlligatorPhases(bars:List<WilliamsWaveBar>,jaw:List<Double>,
    teeth:List<Double>,lips:List<Double>):List<String> {
    val n=bars.size;val out=MutableList(n) { "UNKNOWN" }
    if(listOf(jaw,teeth,lips).any { it.size!=n }) return out
    fun valid(i:Int)=listOf(jaw[i],teeth[i],lips[i],bars[i].c).all { it.isFinite() && it>0 }
    fun order(i:Int)=if(lips[i]>teeth[i] && teeth[i]>jaw[i]) 1 else if(lips[i]<teeth[i] && teeth[i]<jaw[i]) -1 else 0
    for(i in 1 until n) {
        if(!valid(i) || !valid(i-1)) continue
        val d=order(i);val previous=order(i-1)
        val gaps=listOf(abs(jaw[i]-teeth[i]),abs(teeth[i]-lips[i]))
        val old=listOf(abs(jaw[i-1]-teeth[i-1]),abs(teeth[i-1]-lips[i-1]))
        out[i]=when {
            d==0 -> if(previous==0) "SLEEPING" else "SATIATED"
            d!=previous -> "AWAKENING"
            d*(bars[i].c-lips[i])>0 && listOf(jaw,teeth,lips).all { d*(it[i]-it[i-1])>0 } &&
                gaps.indices.all { gaps[it]>=old[it] } && gaps.indices.any { gaps[it]>old[it] } -> "EATING"
            gaps.indices.any { gaps[it]<old[it] } || d*(bars[i].c-lips[i])<=0 -> "SATIATED"
            else -> "AWAKENING"
        }
    }
    return out
}

internal fun williamsNtdInitialContext(bars:List<WilliamsWaveBar>,jaw:List<Double>,teeth:List<Double>,
    lips:List<Double>,formation:Int,tick:Double):JSONObject {
    val proof=JSONObject().put("eligible",false).put("reason","NTD_SLEEP_UNOBSERVED")
        .put("sleep_at_ms",JSONObject.NULL).put("numerical_interpretation",true)
    if(formation !in bars.indices || !tick.isFinite() || tick<=0) return proof
    val phases=williamsAlligatorPhases(bars,jaw,teeth,lips)
    val sleep=(formation downTo 0).firstOrNull { phases[it]=="SLEEPING" } ?: return proof
    proof.put("sleep_at_ms",bars[sleep].t)
    for(f in confirmedWilliamsEditionFractals(bars)) {
        if(f.confirmation<=sleep) continue
        val sign=if(f.kind=="HIGH") 1 else -1;val trigger=f.price+sign*tick
        for(i in f.confirmation+1 until bars.size) {
            if(sign*((if(sign==1) bars[i].h else bars[i].l)-trigger)>=0) {
                if(teeth[i].isFinite() && sign*(f.price-teeth[i])>0)
                    return proof.put("reason","NTD_FIRST_BREAKOUT_ALREADY_OCCURRED").put("first_breakout_at_ms",bars[i].t)
                break
            }
        }
    }
    return proof.put("eligible",true).put("reason","NTD_SLEEP_AND_UNCONSUMED_FIRST_FRACTAL")
}
