package com.williamsbot

import org.json.JSONObject
import kotlin.math.abs

/**
 * TC2 chapter 11, figure 11.7 / Russian PDF page 151: Williams explicitly
 * declines late fractals g/h/j when the market has become corrective/bounded.
 * The author does NOT specify a numerical 24-bar congestion formula.
 *
 * This is a labelled PROJECT RISK POLICY, not an Elliott-wave certification.
 * It can withhold NEW WM2/WM3 exposure while an H1 stop trigger remains within
 * independently observed congestion. A real breakout beyond that range is
 * NOT vetoed. WM1 countertrend reversal geometry retains its own book gate.
 *
 * Existing positions, stops, risk reductions and book exits are NEVER gated.
 * Closed and contiguous H1 only; no M15 confirmation and no future candles.
 */
internal const val WILLIAMS_TC2_CONGESTION_POLICY="TC2_CORRECTIVE_CONGESTION_NEW_RISK_V1"
internal const val WILLIAMS_TC2_CONGESTION_BLOCKER="TC2_CORRECTIVE_CONGESTION_INSIDE_H1_RANGE"

internal fun williamsTc2CongestionEvidence(
    closedBars:List<WilliamsWaveBar>,nowMs:Long,
    signalType:String,direction:String,trigger:Double
):JSONObject {
    val result=JSONObject().put("schema","WILLIAMS_TC2_CONGESTION_V1")
        .put("policy",WILLIAMS_TC2_CONGESTION_POLICY)
        .put("book_reference","TC2_CH11_FIG_11_7_PDF_151")
        .put("classification_is_provisional",true)
        .put("numerical_thresholds_are_engineering",true)
        .put("source","CLOSED_CONTIGUOUS_H1_CANDLES")
        .put("can_place_orders",false).put("can_close",false)
        .put("can_change_stops",false)
        .put("blocked",false).put("state","HISTORY_UNVERIFIED")
        .put("reason_code","NOT_EVALUATED")
    val family=signalType.removePrefix("SHORT_").removePrefix("LONG_")
    if(direction !in setOf("LONG","SHORT") || family !in setOf("REVERSAL","SUPER_AO","FRACTAL") ||
        !trigger.isFinite() || trigger<=0.0) return result.put("reason_code","INVALID_SIGNAL_EVIDENCE")
    if(closedBars.size<60 || nowMs<=0L ||
        williamsClosedH1HistoryBlocker(closedBars,nowMs)!=null ||
        !validWilliamsEditionBars(closedBars)) return result.put("reason_code","INSUFFICIENT_OR_STALE_H1")
    val window=closedBars.takeLast(24)
    val highs=window.maxOf { it.h }
    val lows=window.minOf { it.l }
    val breaksRange=if(direction=="LONG") trigger>highs else trigger<lows
    val mids=closedBars.map { (it.h+it.l)/2.0 }
    val teethRaw=smmaSeededValues(mids,8)
    val jaw=shiftAlligatorForDisplay(smmaSeededValues(mids,13),8)
    val teeth=shiftAlligatorForDisplay(teethRaw,5)
    val lips=shiftAlligatorForDisplay(smmaSeededValues(mids,5),3)
    val phases=williamsAlligatorPhases(closedBars,
        jaw.map { it?:Double.NaN },teeth.map { it?:Double.NaN },
        lips.map { it?:Double.NaN })
    val start=closedBars.size-24
    if((start until closedBars.size).any { i ->
        listOf(teeth[i],jaw[i],lips[i]).any { it==null || !it.isFinite() || it<=0.0 }
    }) return result.put("reason_code","ALLIGATOR_HISTORY_UNVERIFIED")
    fun crossings(values:List<Double>):Int {
        var prior=0
        var changes=0
        for(value in values) {
            val sign=when { value>0.0 -> 1; value<0.0 -> -1; else -> 0 }
            if(sign==0) continue
            if(prior!=0 && sign!=prior) changes++
            prior=sign
        }
        return changes
    }
    val teethCrosses=crossings((start until closedBars.size).map { i ->
        closedBars[i].c-teeth[i]!!
    })
    val ao=(start until closedBars.size).map { i ->
        fun mean(n:Int)=mids.subList(i-n+1,i+1).average()
        mean(5)-mean(34)
    }
    if(ao.any { !it.isFinite() }) return result.put("reason_code","AO_HISTORY_UNVERIFIED")
    val aoFlips=crossings(ao)
    val lastEight=phases.takeLast(8)
    val nonTrending=lastEight.count { it in setOf("SLEEPING","SATIATED") }
    // More than one full oscillation, plus an intertwined/satiated Alligator,
    // is evidence of two-sided congestion; not a "certain W-X-Y" diagnosis.
    val rangeObserved=teethCrosses>=3 && aoFlips>=2 && nonTrending>=4
    val block=rangeObserved && !breaksRange && family in setOf("SUPER_AO","FRACTAL")
    return result.put("state",if(rangeObserved) "CONGESTION_CANDIDATE" else "NOT_CONFIRMED")
        .put("reason_code",if(block) WILLIAMS_TC2_CONGESTION_BLOCKER else
            if(breaksRange) "STOP_BREAKOUT_OUTSIDE_24_H1_RANGE" else
            if(family=="REVERSAL") "WM1_HAS_INDEPENDENT_ANGULATION_GATE" else "CONGESTION_NOT_PROVEN")
        .put("blocked",block).put("window_bars",window.size)
        .put("range_high",highs).put("range_low",lows)
        .put("signal_type",family).put("direction",direction)
        .put("trigger",trigger).put("breakout_outside_range",breaksRange)
        .put("teeth_close_crossings",teethCrosses).put("ao_zero_crossings",aoFlips)
        .put("satiated_or_sleeping_last_8",nonTrending)
        .put("as_of_close_ms",closedBars.last().t+WILLIAMS_H1_MS)
}

internal fun williamsTc2CongestionEntryBlocker(
    source:String,signalType:String,direction:String,trigger:Double,
    closedBars:List<WilliamsWaveBar>,nowMs:Long
):String? = if(source!=williamsTc2Profile) null else {
    val report=williamsTc2CongestionEvidence(closedBars,nowMs,signalType,direction,trigger)
    if(report.optBoolean("blocked",false)) WILLIAMS_TC2_CONGESTION_BLOCKER else null
}
