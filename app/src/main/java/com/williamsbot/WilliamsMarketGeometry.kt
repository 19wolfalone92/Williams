package com.williamsbot

import org.json.JSONObject
import kotlin.math.abs

/** TC2 PDF125 EXPECTS old-trend AO colour on a reversal bar; PDF131
 * clarifies that without it this is not a valid countertrend trade.
 * The signal therefore needs the same-side AO context, divergent price bar
 * and mouth-anchored angulation. Unavailable AO fails closed.
 */
internal fun williamsFirstWiseManAoBlocker(side:String,aoNow:Double,aoBefore:Double):String? {
    if(side !in listOf("LONG","SHORT")) return "WM1_AO_SIDE_INVALID"
    if(!aoNow.isFinite() || !aoBefore.isFinite()) return "WM1_AO_UNVERIFIED"
    return when(side) {
        "LONG" -> if(aoNow<aoBefore) null else "WM1_AO_NOT_RED"
        else -> if(aoNow>aoBefore) null else "WM1_AO_NOT_GREEN"
    }
}

/** TC2 PDF122-124: last mouth passage, highs on a downward path and lows
 * on an upward path, compared with Jaw/Teeth over the same interval.
 * Regression is an explicit numerical interpretation of the visual rule. */
internal data class WilliamsAngulation(
    val valid:Boolean=false,val score:Double=0.0,val reason:String="MOUTH_CROSSING_UNOBSERVED",
    val anchor:Int?=null,val examinedBars:Int=0,val priceSlope:Double?=null,
    val jawSlope:Double?=null,val teethSlope:Double?=null,
    val firstOutside:Int?=null,val outsideGapGrowthPct:Double?=null
) {
    fun toJson():JSONObject=JSONObject().put("valid",valid).put("score",score).put("reason",reason)
        .put("method","MOUTH_ANCHORED_EDGE_SLOPES").put("numerical_interpretation",true)
        .put("anchor_index",anchor ?: JSONObject.NULL).put("examined_bars",examinedBars)
        .put("price_slope",priceSlope ?: JSONObject.NULL).put("jaw_slope",jawSlope ?: JSONObject.NULL)
        .put("teeth_slope",teethSlope ?: JSONObject.NULL)
        .put("first_outside_index",firstOutside ?: JSONObject.NULL)
        .put("outside_gap_growth_pct",outsideGapGrowthPct ?: JSONObject.NULL)
        .put("book_reference","TC2_CH9_FIG_9_3_TO_9_11")
}
/** TC2 ch9, PDF 122–130: four visual rules, represented by a causal
 * conservative numerical test. A wide price/Alligator distance is NOT enough:
 * price must depart from the most recent mouth passage and continue opening
 * an angle after the first candle entirely outside the mouth. Regression and
 * positive Jaw/Teeth gap growth are engineering proxies, not Williams formulas.
 */
internal fun williamsAngulation(side:String,bars:List<WilliamsWaveBar>,jaw:List<Double>,
    teeth:List<Double>,lips:List<Double>,expectedStepMs:Long?=null):WilliamsAngulation {
    val n=bars.size
    if(side !in setOf("LONG","SHORT") || n<3 ||
        listOf(jaw,teeth,lips).any { it.size!=n } ||
        (expectedStepMs!=null && expectedStepMs<=0L))
        return WilliamsAngulation(reason="INVALID_GEOMETRY_INPUT")
    fun usable(i:Int)=listOf(bars[i].o,bars[i].h,bars[i].l,bars[i].c,
        jaw[i],teeth[i],lips[i]).all { it.isFinite() && it>0 } &&
        bars[i].h>=maxOf(bars[i].o,bars[i].c,bars[i].l) &&
        bars[i].l<=minOf(bars[i].o,bars[i].c)
    fun crossesMouth(i:Int)=bars[i].h>=minOf(jaw[i],teeth[i],lips[i]) &&
        bars[i].l<=maxOf(jaw[i],teeth[i],lips[i])
    fun outsideMouth(i:Int)=if(side=="LONG") bars[i].h<minOf(jaw[i],teeth[i],lips[i])
        else bars[i].l>maxOf(jaw[i],teeth[i],lips[i])
    // A WM1 divergent candle touching/piercing the mouth is invalid (fig 9.11).
    if(!usable(n-1)) return WilliamsAngulation(reason="INVALID_SIGNAL_CANDLE_OR_LINES")
    if(!outsideMouth(n-1)) return WilliamsAngulation(reason="SIGNAL_BAR_TOUCHES_MOUTH")
    var anchor:Int?=null
    for(i in n-2 downTo 0) {
        if(!usable(i)) break
        if(crossesMouth(i)) { anchor=i;break }
    }
    val start=anchor ?: return WilliamsAngulation()
    if(n-start<3 || !(start until n).all { usable(it) })
        return WilliamsAngulation(reason="INSUFFICIENT_PATH",anchor=start)
    val step=expectedStepMs ?: (bars[n-1].t-bars[n-2].t)
    if(step<=0 || (start+1 until n).any { bars[it].t-bars[it-1].t!=step })
        return WilliamsAngulation(reason="CANDLE_PATH_GAP",anchor=start,examinedBars=n-start)
    // The initial transition out of the mouth is not evidence of continuing
    // angular separation. Two parallel paths after an initial gap are INVALID.
    val firstOutside=((start+1) until n).firstOrNull { outsideMouth(it) }
    if(firstOutside==null || firstOutside==n-1)
        return WilliamsAngulation(reason="INSUFFICIENT_OUTSIDE_PATH",anchor=start,examinedBars=n-start)
    fun slope(values:List<Double>):Double {
        val x=(values.size-1)/2.0;val mean=values.average()
        return values.indices.sumOf { (it-x)*(values[it]-mean) }/
            values.indices.sumOf { (it-x)*(it-x) }
    }
    val sign=if(side=="LONG") -1 else 1
    val edge=bars.drop(start).map { if(side=="LONG") it.h else it.l }
    val price=slope(edge);val j=slope(jaw.drop(start));val t=slope(teeth.drop(start))
    val separation=minOf(sign*(price-j),sign*(price-t))
    val endpoint=listOf(jaw,teeth).minOf { sign*((edge.last()-edge.first())-(it.last()-it[start])) }
    val priceTraverses=sign*price>0 && sign*(edge.last()-edge.first())>0
    val basic=priceTraverses && separation>0 && endpoint>0
    fun gapTo(line:List<Double>,index:Int):Double {
        val edgeAt=if(side=="LONG") bars[index].h else bars[index].l
        return sign*(edgeAt-line[index])
    }
    val postGrowth=minOf(gapTo(jaw,n-1)-gapTo(jaw,firstOutside),
        gapTo(teeth,n-1)-gapTo(teeth,firstOutside))
    val valid=basic && postGrowth>0
    val reason=when {
        !basic->"PRICE_PATH_NOT_STEEPER"
        postGrowth<=0->"PARALLEL_AFTER_MOUTH_EXIT"
        else->"DIVERGING_PRICE_PATH"
    }
    return WilliamsAngulation(valid,if(valid) separation/bars.last().c*100 else 0.0,
        reason,start,n-start,price,j,t,firstOutside,
        postGrowth/bars.last().c*100)
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
                if(teeth[i].isFinite() && sign*(trigger-teeth[i])>0)
                    return proof.put("reason","NTD_FIRST_BREAKOUT_ALREADY_OCCURRED").put("first_breakout_at_ms",bars[i].t)
                break
            }
        }
    }
    return proof.put("eligible",true).put("reason","NTD_SLEEP_AND_UNCONSUMED_FIRST_FRACTAL")
}
