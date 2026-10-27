package com.williamsbot

import org.json.JSONObject
import org.json.JSONArray

/**
 * TC2 chapter 11, figure 11.7 / Russian PDF page 151: Williams explicitly
 * declines late fractals g/h/j when the market has become corrective/bounded.
 * The author does NOT specify a numerical 24-bar congestion formula.
 *
 * This is a labelled PROJECT RISK POLICY, not an Elliott-wave certification.
 * It withholds NEW TC2 exposure only when the chart has a RECENT, causally
 * confirmed, structural COMPLEX-correction price grammar (triangle or
 * constrained corrective combination) and entry remains inside its range.
 * A mere sideways market, uncomplicated A-B-C, or W-X-Y_UNRESOLVED placeholder
 * is NOT sufficient to block. A real breakout outside the structure passes.
 * Every WM1/WM2/WM3 signal is evaluated on the same basis.
 *
 * Existing positions, stops, risk reductions and book exits are NEVER gated.
 * Closed and contiguous H1 only; no M15 confirmation and no future candles.
 */
internal const val WILLIAMS_TC2_CONGESTION_POLICY="TC2_COMPLEX_CORRECTIONS_ONLY_V2"
internal const val WILLIAMS_TC2_CONGESTION_BLOCKER="TC2_COMPLEX_CORRECTION_PRICE_GRAMMAR_INSIDE_H1_RANGE"

/** Only price grammars that actually distinguish a complex correction from
 * ordinary A-B-C; the generic 8/12-anchor WXY_UNRESOLVED labels are excluded.
 * All are provisional Elliott *candidates*, never unique wave counts.
 */
internal val WILLIAMS_TC2_COMPLEX_PATTERNS=setOf(
    "CONTRACTING_TRIANGLE_ABCDE_CANDIDATE",
    "EXPANDING_TRIANGLE_ABCDE_CANDIDATE",
    "BARRIER_TRIANGLE_ABCDE_CANDIDATE",
    "W_X_Y_ZIGZAG_COMBINATION_CANDIDATE",
    "W_X_Y_X_Z_ZIGZAG_COMBINATION_CANDIDATE"
)

/** No signal veto from arbitrary flatness alone or from an old/incomplete
 * W-X-Y label. Look only at confirmed, recent H1 fractal pivot geometry.
 */
internal fun williamsTc2RecentComplexPriceCandidates(
    anchors:List<WilliamsWaveAnchor>,asOf:Long
):List<WilliamsComplexEvidence> {
    if(asOf<=0L || anchors.size<6 || anchors.any { it.knownAt>asOf || it.knownAt<it.extremeAt }) return emptyList()
    val recentCutoff=asOf-12*WILLIAMS_H1_MS
    return listOf("UP","DOWN").flatMap { side ->
        detectWilliamsComplexWave(anchors,side,asOf)
    }.filter { it.pattern in WILLIAMS_TC2_COMPLEX_PATTERNS &&
        it.knownAt in recentCutoff..asOf && it.endAt<=asOf }
        .distinctBy { listOf(it.pattern,it.startAt,it.endAt) }
}


internal fun williamsTc2CongestionEvidence(
    closedBars:List<WilliamsWaveBar>,nowMs:Long,
    signalType:String,direction:String,trigger:Double
):JSONObject {
    val result=JSONObject().put("schema","WILLIAMS_TC2_CONGESTION_V1")
        .put("policy",WILLIAMS_TC2_CONGESTION_POLICY)
        .put("book_reference","TC2_CH11_FIG_11_7_PDF_151")
        .put("complex_correction_grammar_reference","RESEARCH_ELLIOTT_COMPLEX_PRICE_GRAMMAR")
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
    // These numerical range observations are only CONTEXT, not a veto.
    // A triangle/combination needs its own confirmed multi-swing geometry.
    // Unlike generic congestion, normal ABC zigzags/flats remain tradable.
    val rangeObserved=teethCrosses>=3 && aoFlips>=2 && nonTrending>=4
    val confirmedPivots=confirmedWilliamsWaveAnchors(closedBars,WILLIAMS_H1_MS)
    val structural=williamsTc2RecentComplexPriceCandidates(confirmedPivots,nowMs)
    val structures=structural.mapNotNull { pattern ->
        val involved=confirmedPivots.filter { it.extremeAt in pattern.startAt..pattern.endAt }
        if(involved.size<6) null else {
            val patternHigh=involved.maxOf { it.price }
            val patternLow=involved.minOf { it.price }
            JSONObject().put("pattern",pattern.pattern).put("known_at_ms",pattern.knownAt)
                .put("start_at_ms",pattern.startAt).put("end_at_ms",pattern.endAt)
                .put("range_high",patternHigh).put("range_low",patternLow)
                .put("crosses_entry",trigger in patternLow..patternHigh)
        }
    }
    val insideComplex=structures.any { it.optBoolean("crosses_entry") }
    // H1 entry outside the actually observed complex structure is a distinct
    // possible breakout. The separate standard Wise-Man gates still apply.
    val block=insideComplex && !breaksRange
    val state=when {
        structures.isNotEmpty() -> "COMPLEX_CORRECTION_CANDIDATE"
        rangeObserved -> "ORDINARY_CONGESTION_OR_CORRECTION_NOT_COMPLEX"
        else -> "NO_COMPLEX_CORRECTION_EVIDENCE"
    }
    return result.put("state",state)
        .put("reason_code",when {
            block -> WILLIAMS_TC2_CONGESTION_BLOCKER
            structures.isNotEmpty() && !insideComplex -> "ENTRY_OUTSIDE_COMPLEX_STRUCTURE"
            structures.isNotEmpty() && breaksRange -> "STOP_BREAKOUT_OUTSIDE_24_H1_RANGE"
            rangeObserved -> "ONLY_CONGESTION_NOT_COMPLEX_NO_VETO"
            else -> "NO_COMPLEX_CORRECTION_PROOF"
        }).put("blocked",block).put("window_bars",window.size)
        .put("range_high",highs).put("range_low",lows)
        .put("signal_type",family).put("direction",direction)
        .put("trigger",trigger).put("breakout_outside_range",breaksRange)
        .put("teeth_close_crossings",teethCrosses).put("ao_zero_crossings",aoFlips)
        .put("satiated_or_sleeping_last_8",nonTrending)
        .put("complex_structures",JSONArray(structures))
        .put("complex_structure_count",structures.size)
        .put("simple_abc_is_allowed",true)
        .put("generic_wxy_unresolved_is_not_a_veto",true)
        .put("as_of_close_ms",closedBars.last().t+WILLIAMS_H1_MS)
}

internal fun williamsTc2CongestionEntryBlocker(
    source:String,signalType:String,direction:String,trigger:Double,
    closedBars:List<WilliamsWaveBar>,nowMs:Long
):String? = if(source!=williamsTc2Profile) null else {
    val report=williamsTc2CongestionEvidence(closedBars,nowMs,signalType,direction,trigger)
    if(report.optBoolean("blocked",false)) WILLIAMS_TC2_CONGESTION_BLOCKER else null
}

/**
 * An H1 range reclassification is never proof that a Binance algo order has
 * not already started filling. Request revocation only for exchange-known
 * untriggered conditional entry states. The coordinator still must cancel,
 * verify the terminal exchange response, and reconcile positions before any
 * further new order. A position or its protective STOP is never a candidate.
 */
internal fun williamsTc2PendingCongestionRecheckEligible(
    role:String,exchangeStatus:String,clientId:String,
    knownFill:Boolean=false
):Boolean = !knownFill && role in setOf("ENTRY","ADD_ON") &&
    (clientId.startsWith("W2H_") || clientId.startsWith("W2A_")) &&
    exchangeStatus.uppercase(java.util.Locale.US) in
        setOf("NEW","PENDING","WORKING","ACCEPTED")
