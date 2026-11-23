package com.williamsbot

import org.json.JSONObject
import kotlin.math.abs

/**
 * Executable TC2 book-choice policy, not a claim that Williams prescribed
 * this automatic window selector. TC2 permits 3/4/5 CLOSED H1 bars; whether
 * the trader chooses a tighter or wider exit is discretionary in the book.
 *
 * Engineering AUTO policy:
 *  - 5 bars for mixed, weak, correcting or unverifiable directional context;
 *  - 4 bars for a distinctly advancing/declining H1 run with aligned H4;
 *  - 3 bars only for a strong and orderly H1 impulse with aligned H4.
 *
 * Never project unclosed candles, make a market order, widen an exchange STOP
 * or infer strategy ownership. The caller must independently verify fresh
 * closed Binance H1/H4; this helper rejects discontinuous/nonpositive inputs.
 */
internal data class Tc2AdaptiveBarChoice(
    val window:Int,
    val reason:String,
    val medianH1Range:Double,
    val favorableH4:Boolean,
    val consecutiveH1Bars:Int,
    val h1MoveInRanges:Double,
    val postEntryClosedH1Bars:Int
) {
    fun evidence():JSONObject=JSONObject()
        .put("schema","TC2_ADAPTIVE_3_4_5_H1_BOOK_CHOICE_V1")
        .put("chosen_window",window)
        .put("choice_reason",reason)
        .put("median_h1_closed_range",medianH1Range)
        .put("h4_price_context_favorable",favorableH4)
        .put("h1_consecutive_directional_bars",consecutiveH1Bars)
        .put("h1_impulse_move_in_median_ranges",h1MoveInRanges)
        .put("post_entry_closed_h1_bars",postEntryClosedH1Bars)
        .put("automatic_window_selection_is_engineering_not_book_quote",true)
        .put("window_3_4_5_options_are_tc2_book_rules",true)
        .put("uses_only_verified_closed_h1_h4",true)
        .put("can_authorize_exchange_order_by_itself",false)
}

internal fun chooseTc2AdaptiveBarWindow(
    direction:String,
    openedAtMs:Long,
    h1:List<WilliamsWaveBar>,
    h4:List<WilliamsWaveBar>
):Tc2AdaptiveBarChoice {
    fun fallback(why:String,median:Double=0.0,h4Aligned:Boolean=false,
                 directional:Int=0,move:Double=0.0,postEntry:Int=0)=
        Tc2AdaptiveBarChoice(5,why,median,h4Aligned,directional,move,postEntry)
    if(direction !in setOf("LONG","SHORT") || openedAtMs<=0L ||
       h1.size<12 || h4.size<2) return fallback("CONTEXT_NOT_VERIFIED_KEEP_FIVE")
    fun validBar(b:WilliamsWaveBar):Boolean =
        b.t>0L && b.o.isFinite() && b.h.isFinite() && b.l.isFinite() &&
        b.c.isFinite() && b.o>0.0 && b.l>0.0 &&
        b.h>=maxOf(b.o,b.c,b.l) && b.l<=minOf(b.o,b.c)
    val h1Last=h1.takeLast(12)
    val h4Last=h4.takeLast(2)
    if(h1Last.any { !validBar(it) } || h4Last.any { !validBar(it) } ||
       h1Last.zipWithNext().any { (a,b)->b.t-a.t!=WILLIAMS_H1_MS } ||
       h4Last[1].t-h4Last[0].t!=4*WILLIAMS_H1_MS)
        return fallback("H1_H4_CONTINUITY_NOT_VERIFIED_KEEP_FIVE")
    // The H4 candle must be the latest H4 frame compatible with the most
    // recent CLOSED H1. Reusing an older yet contiguous pair of H4 bars is
    // not evidence to tighten from FIVE to THREE/FOUR.
    val h1ClosedAt=h1Last.last().t+WILLIAMS_H1_MS
    val h4ClosedAt=h4Last.last().t+4*WILLIAMS_H1_MS
    if(h1ClosedAt-h4ClosedAt !in 0 until 4*WILLIAMS_H1_MS)
        return fallback("LATEST_CLOSED_H4_NOT_ALIGNED_WITH_H1_KEEP_FIVE")
    val ranges=h1Last.map { it.h-it.l }.sorted()
    val median=(ranges[5]+ranges[6])/2.0
    if(!median.isFinite() || median<=0.0) return fallback("H1_MEDIAN_RANGE_UNKNOWN_KEEP_FIVE")
    val h4a=h4Last[0]; val h4b=h4Last[1]
    val h4Aligned=if(direction=="LONG")
        h4b.l>h4a.l && h4b.h>=h4a.h && h4b.c>h4a.c
    else h4b.h<h4a.h && h4b.l<=h4a.l && h4b.c<h4a.c
    val recent=h1Last.takeLast(5)
    fun directional(a:WilliamsWaveBar,b:WilliamsWaveBar):Boolean =
        if(direction=="LONG") b.c>a.c && b.l>a.l && b.h>a.h
        else b.c<a.c && b.h<a.h && b.l<a.l
    var run=1
    for(i in recent.lastIndex downTo 1) {
        if(!directional(recent[i-1],recent[i])) break
        run++
    }
    // Last THREE closed-bar closes from oldest to newest, measured in units
    // of ordinary H1 noise; not a future high/low or hindsight maximum.
    val movement=if(direction=="LONG")
        recent.last().c-recent[recent.size-3].c
    else recent[recent.size-3].c-recent.last().c
    val normalized=movement/median
    val postEntry=h1Last.takeLast(5).count { it.t>=openedAtMs }
    val selected=when {
        !h4Aligned -> fallback("H4_NOT_ALIGNED_PREFER_WIDER_FIVE",
            median,false,run,normalized,postEntry)
        run>=3 && normalized>=1.25 && postEntry>=3 ->
            Tc2AdaptiveBarChoice(3,"STRONG_ORDERLY_H1_H4_IMPULSE_USE_THREE",
                median,true,run,normalized,postEntry)
        run>=2 && normalized>=0.60 && postEntry>=4 ->
            Tc2AdaptiveBarChoice(4,"MODERATE_ORDERLY_H1_H4_IMPULSE_USE_FOUR",
                median,true,run,normalized,postEntry)
        else -> fallback("UNCONFIRMED_OR_CORRECTING_MOMENTUM_KEEP_FIVE",
            median,true,run,normalized,postEntry)
    }
    return selected
}
