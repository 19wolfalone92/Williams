package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject

/** TC1 PDF 59-60: global 5/34 peak, price extreme 1-5 bars earlier.
 * This is evidence conditional on a proposed degree, never a wave identity
 * theorem or an order/exit instruction. Equal peaks remain explicit alternatives.
 */
internal data class WilliamsThirdMomentumEvidence(
    val status:String,val peakIndex:Int?,val globalPeak:Double?,
    val globalPeakIndexes:List<Int>,val lagBars:Int?,val knownAt:Long?,
    val signalAtPeak:Double?,val signalCrossKnownAt:Long?
) {
    val observed:Boolean get()=status=="GLOBAL_PEAK_AND_PRICE_LAG_OBSERVED"
    fun toJson():JSONObject=JSONObject().put("status",status)
        .put("global_peak",globalPeak?:JSONObject.NULL)
        .put("global_peak_indexes",JSONArray(globalPeakIndexes))
        .put("ao_peak_index",peakIndex?:JSONObject.NULL)
        .put("price_to_ao_lag_bars",lagBars?:JSONObject.NULL)
        .put("peak_turn_known_at_ms",knownAt?:JSONObject.NULL)
        .put("signal_sma5_at_peak",signalAtPeak?:JSONObject.NULL)
        .put("signal_cross_known_at_ms",signalCrossKnownAt?:JSONObject.NULL)
        .put("book_lag_range","1_TO_5_REAL_BARS")
        .put("wave_degree_confirmed",false).put("can_place_orders",false)
        .put("can_close_positions",false)
}

internal fun evaluateWilliamsThirdMomentum(
    rows:List<WilliamsWaveBar>,ao:List<Double>,cycleStart:Int,
    thirdPriceIndex:Int,fourthPriceIndex:Int,direction:String,frameMs:Long
):WilliamsThirdMomentumEvidence {
    fun absent(reason:String)=WilliamsThirdMomentumEvidence(reason,null,null,emptyList(),null,null,null,null)
    if(direction !in listOf("UP","DOWN") || frameMs<=0 || rows.size!=ao.size ||
        cycleStart<0 || thirdPriceIndex<cycleStart || fourthPriceIndex<=thirdPriceIndex ||
        fourthPriceIndex>=rows.size || !ao.subList(cycleStart,fourthPriceIndex+1).all(Double::isFinite))
        return absent("INVALID_OR_UNWARMED_MOMENTUM_WINDOW")
    val sign=if(direction=="UP") 1.0 else -1.0
    val maximum=(cycleStart..fourthPriceIndex).maxOf { sign*ao[it] }
    if(maximum<=0) return absent("NO_SAME_DIRECTION_GLOBAL_MOMENTUM")
    val global=(cycleStart..fourthPriceIndex).filter { sign*ao[it]==maximum }
    val candidates=global.filter { peak->peak-thirdPriceIndex in 1..5 &&
        // The proposed third price extreme must really be the appropriate
        // high/low among the five bars preceding this oscillator maximum.
        (maxOf(cycleStart,peak-5) until peak).all { i->
            if(direction=="UP") rows[i].h<=rows[thirdPriceIndex].h
            else rows[i].l>=rows[thirdPriceIndex].l
        }
    }
    val chosen=candidates.lastOrNull()?:global.last()
    val turn=(chosen+1..fourthPriceIndex).firstOrNull { sign*ao[it]<maximum }
    val afterTurn=turn!=null && (chosen+1 until turn).all { sign*ao[it]==maximum }
    val status=when {
        candidates.isEmpty()->"GLOBAL_PEAK_OUTSIDE_W3_PRICE_LAG_WINDOW"
        global.any { it !in candidates }->"MULTIPLE_GLOBAL_PEAKS_DEGREE_UNCONFIRMED"
        !afterTurn->"GLOBAL_PEAK_TURN_NOT_YET_OBSERVED"
        else->"GLOBAL_PEAK_AND_PRICE_LAG_OBSERVED"
    }
    fun signal(i:Int):Double?=if(i>=37 && (i-4..i).all { ao[it].isFinite() })
        (i-4..i).sumOf { ao[it] }/5 else null
    val signalCross=(chosen+1..fourthPriceIndex).firstOrNull { i->
        signal(i)?.let { sign*ao[i]<sign*it }==true
    }
    return WilliamsThirdMomentumEvidence(status,chosen,sign*maximum,global,
        chosen-thirdPriceIndex,if(afterTurn) rows[turn].t+frameMs else null,
        signal(chosen),signalCross?.let { rows[it].t+frameMs })
}
