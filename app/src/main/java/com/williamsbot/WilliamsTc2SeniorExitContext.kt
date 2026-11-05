package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject

/**
 * MARKET STRUCTURE RESEARCH ONLY. All input candles must be strictly CLOSED
 * as of the decision timestamp. No Binance/order/Coordinator dependency.
 *
 * TC2 chapter 12 (PDF pp.153-165) allows Wise-Man exits, but does not prove
 * that every counter-signal has reversed the SENIOR trend. This diagnostic
 * distinguishes higher-frame continuation from a possible genuine reversal;
 * it never denies source-authorized exits or alters exchange STOP protection.
 */
internal fun williamsTc2SeniorExitContext(
    ownerDirection: String,
    asOfMs: Long,
    sources: Map<String,List<WilliamsWaveBar>>,
    oppositeSignalId: String
): JSONObject {
    val result=JSONObject()
        .put("schema","WILLIAMS_TC2_SENIOR_EXIT_CONTEXT_V1")
        .put("mode","SHADOW_ONLY")
        .put("as_of_ms",asOfMs)
        .put("position_direction",ownerDirection)
        .put("opposite_signal_id",oppositeSignalId)
        .put("regime","UNRESOLVED")
        .put("context_hold_hypothesis",false)
        .put("confidence_calibrated",false)
        .put("wave_count_is_certain",false)
        .put("can_authorize_exit",false)
        .put("can_veto_book_exit",false)
        .put("can_modify_stop",false)
        .put("can_place_orders",false)
        .put("can_reverse",false)
    if(ownerDirection !in setOf("LONG","SHORT") || asOfMs<=0L || oppositeSignalId.isBlank())
        return result.put("reason","INVALID_OBSERVATION_REQUEST")
    val ownerBias=if(ownerDirection=="LONG") "UP" else "DOWN"
    val counterBias=if(ownerDirection=="LONG") "DOWN" else "UP"
    val perFrame=JSONArray()
    val states=linkedMapOf<String,String>()
    val verifiedClosed=linkedMapOf<String,List<WilliamsWaveBar>>()
    val breaks=linkedMapOf<String,Boolean>()
    val swingPatterns=linkedMapOf<String,String>()
    val uncertainty=mutableListOf<String>()
    val correctiveWaveLabels=setOf("W2","W4","A","B","C")
    val waveHints=mutableListOf<String>()
    for(frame in listOf("1d","4h","1h")) {
        val raw=sources[frame].orEmpty()
        val checked=runCatching { closedWilliamsWaveBars(raw,frame,asOfMs) }
        if(checked.isFailure) {
            uncertainty+="${frame}_"+(checked.exceptionOrNull()?.message ?: "UNVERIFIED")
            perFrame.put(JSONObject().put("frame",frame).put("state","UNVERIFIED")
                .put("reason",checked.exceptionOrNull()?.message ?: "UNVERIFIED")
                .put("can_place_orders",false))
            continue
        }
        val bars=checked.getOrThrow()
        verifiedClosed[frame]=bars
        val reading=williamsMarketReading(bars,JSONObject(),frame,asOfMs)
        val bias=reading.optString("observed_bias","MIXED")
        if(reading.optString("state")!="OBSERVED" || bias !in setOf("UP","DOWN","MIXED")) {
            uncertainty+="${frame}_READING_NOT_VERIFIED"
            perFrame.put(JSONObject().put("frame",frame).put("state","UNVERIFIED")
                .put("reason",reading.optString("state"))
                .put("can_place_orders",false))
            continue
        }
        states[frame]=bias
        val pivots=confirmedWilliamsWaveAnchors(bars,williamsWaveFrames.getValue(frame))
            .filter { it.knownAt<=asOfMs }
        // A LONG trend's observable invalidation proxy is the last confirmed
        // LOW. For SHORT, it is the last confirmed HIGH. This is a measured
        // structural fact, NOT a proven Elliott W4/W5 boundary.
        val pivot=pivots.lastOrNull { it.kind==if(ownerDirection=="LONG") "LOW" else "HIGH" }
        val broken=pivot?.let { if(ownerDirection=="LONG") bars.last().c<it.price else bars.last().c>it.price }
        if(broken!=null) breaks[frame]=broken
        // Last two independently CONFIRMED highs/lows, each with two right
        // closed candles, are an observable swing grammar. This is more
        // informative than the sign of one AO bar and does not certify W4/ABC.
        val highs=pivots.filter { it.kind=="HIGH" }.takeLast(2)
        val lows=pivots.filter { it.kind=="LOW" }.takeLast(2)
        val ascending=highs.size==2 && lows.size==2 &&
            highs.last().price>highs.first().price &&
            lows.last().price>lows.first().price
        val descending=highs.size==2 && lows.size==2 &&
            highs.last().price<highs.first().price &&
            lows.last().price<lows.first().price
        val swingPattern=when {
            ascending -> "HIGHER_HIGHS_HIGHER_LOWS"
            descending -> "LOWER_HIGHS_LOWER_LOWS"
            highs.size<2 || lows.size<2 -> "INSUFFICIENT_CONFIRMED_SWINGS"
            else -> "MIXED_OR_OVERLAPPING_SWINGS"
        }
        swingPatterns[frame]=swingPattern
        val frameRange=bars.takeLast(14).map { it.h-it.l }.sorted()
        val normalRange=if(frameRange.isNotEmpty())
            (frameRange[6]+frameRange[7])/2.0 else Double.NaN
        val protectivePivotDistance=if(pivot!=null && normalRange.isFinite() && normalRange>0.0)
            (if(ownerDirection=="LONG") bars.last().c-pivot.price
                else pivot.price-bars.last().c)/normalRange else Double.NaN
        // Distance travelled against a previously CONFIRMED swing leg.
        // This may resemble W2/W4 or ABC, but NEVER proves Elliott degree.
        val confirmedTop=highs.lastOrNull()
        val confirmedBase=lows.lastOrNull()
        val retracementProxy=if(confirmedTop!=null && confirmedBase!=null &&
            confirmedTop.price>confirmedBase.price) {
            if(ownerDirection=="LONG" &&
                confirmedTop.extremeAt>confirmedBase.extremeAt)
                (confirmedTop.price-bars.last().c)/
                    (confirmedTop.price-confirmedBase.price)
            else if(ownerDirection=="SHORT" &&
                confirmedBase.extremeAt>confirmedTop.extremeAt)
                (bars.last().c-confirmedBase.price)/
                    (confirmedTop.price-confirmedBase.price)
            else Double.NaN
        } else Double.NaN
        // AO uses only the 5/34 median-price SMAs of already closed
        // candles; compute the last-two slope locally rather than exposing
        // the private wave-lab oscillator implementation.
        val aoMomentumDelta=if(bars.size>=35) {
            val median=bars.takeLast(35).map { (it.h+it.l)/2.0 }
            val current=median.takeLast(5).average()-median.takeLast(34).average()
            val prev=median.dropLast(1).takeLast(5).average()-
                median.dropLast(1).takeLast(34).average()
            current-prev
        } else Double.NaN
        val sourceDirection=if(ownerDirection=="LONG") 1.0 else -1.0
        val lastCloseMove=sourceDirection*(bars.last().c-bars[bars.lastIndex-1].c)
        val priorCloseMove=sourceDirection*(bars[bars.lastIndex-1].c-bars[bars.lastIndex-2].c)
        val adverseBars=bars.takeLast(5).zipWithNext().count { (before,after) ->
            sourceDirection*(after.c-before.c)<0.0 }
        val hypotheses=reading.optJSONArray("independent_wave_hypotheses") ?: JSONArray()
        val signatures=(0 until hypotheses.length()).mapNotNull { idx ->
            val h=hypotheses.optJSONObject(idx) ?: return@mapNotNull null
            val label=h.optString("active_label")
            val direction=h.optString("active_direction")
            if(label in correctiveWaveLabels) waveHints+="${frame}:${label}:${direction}"
            "${label}:${direction}:${h.optDouble("invalidation",Double.NaN)}"
        }.distinct()
        // The detector is capable of showing competing causal hypotheses;
        // agreement of AO/Alligator is NOT independent wave confirmation.
        if(signatures.size>1) uncertainty+="${frame}_MULTIPLE_WAVE_COUNTS"
        val ao=reading.optDouble("ao",Double.NaN)
        val price=bars.last().c
        perFrame.put(JSONObject()
            .put("frame",frame).put("state","CLOSED_OBSERVED")
            .put("last_closed_at_ms",bars.last().t+williamsWaveFrames.getValue(frame))
            .put("closed_bars",bars.size)
            .put("observed_bias",bias)
            .put("alligator_order",reading.optJSONObject("alligator")?.optString("order","MIXED"))
            .put("alligator_phase",reading.optJSONObject("alligator")?.optString("state","UNKNOWN"))
            .put("price_relative_to_mouth",reading.optString("price_vs_mouth","UNKNOWN"))
            .put("ao",if(ao.isFinite()) ao else JSONObject.NULL)
            .put("ao_direction",reading.optString("ao_direction","UNKNOWN"))
            .put("ac_direction",reading.optString("ac_direction","UNKNOWN"))
            .put("price_close",price)
            .put("owner_side_last_confirmed_pivot",pivot?.toJson() ?: JSONObject.NULL)
            .put("confirmed_pivot_broken_by_close",broken ?: JSONObject.NULL)
            .put("last_two_confirmed_high_pivots",JSONArray(highs.map { it.toJson() }))
            .put("last_two_confirmed_low_pivots",JSONArray(lows.map { it.toJson() }))
            .put("confirmed_swing_pattern",swingPattern)
            .put("confirmed_swing_retracement_proxy_ratio",
                if(retracementProxy.isFinite()) retracementProxy else JSONObject.NULL)
            .put("confirmed_swing_retracement_is_elliott_proof",false)
            .put("ao_slope_last_two_completed_bars",
                if(aoMomentumDelta.isFinite()) aoMomentumDelta else JSONObject.NULL)
            .put("price_distance_from_owner_pivot_in_median_14_bar_ranges",
                if(protectivePivotDistance.isFinite()) protectivePivotDistance else JSONObject.NULL)
            .put("median_closed_14_bar_range",if(normalRange.isFinite()) normalRange else JSONObject.NULL)
            .put("recent_closed_bars_against_owner",adverseBars)
            .put("last_closed_bar_owner_direction_move",lastCloseMove)
            .put("previous_closed_bar_owner_direction_move",priorCloseMove)
            .put("price_move_is_not_future_forecast",true)
            .put("independent_wave_alternatives",hypotheses)
            .put("competing_wave_structures_observed",signatures.size>1)
            .put("momentum_and_alligator_are_correlated_evidence",true)
            .put("can_place_orders",false).put("can_close",false))
    }
    val d1=states["1d"];val h4=states["4h"];val h1=states["1h"]
    val complete=states.size==3
    // Price/time containment across actual D1 → H4 → H1 wave hypotheses.
    // Multiple competing parent-child paths stay as alternatives: a
    // geometric containment link is NOT a certified Elliott wave degree.
    val genealogy=if(complete) runCatching {
        buildWilliamsWaveHierarchy(verifiedClosed,asOfMs,
            analysisFrames=listOf("1d","4h","1h"))
    }.getOrNull() else null
    val links=genealogy?.links.orEmpty().take(64)
    val seniorOwner=complete && d1==ownerBias && h4==ownerBias
    val seniorOpponent=complete && d1==counterBias && h4==counterBias
    val h4Broken=breaks["4h"]
    val d1Broken=breaks["1d"]
    val allStructureKnown=h4Broken!=null && d1Broken!=null
    val anyBroken=h4Broken==true || d1Broken==true
    val bothBroken=h4Broken==true && d1Broken==true
    val regime=when {
        !complete -> "INSUFFICIENT_CAUSAL_H1_H4_D1"
        seniorOwner && allStructureKnown && !anyBroken && h1!=ownerBias ->
            "JUNIOR_CORRECTION_INSIDE_SENIOR_TREND_PLAUSIBLE"
        seniorOwner && allStructureKnown && !anyBroken ->
            "SENIOR_TREND_CONTINUATION_PLAUSIBLE"
        seniorOpponent && allStructureKnown && bothBroken && h1==counterBias ->
            "SENIOR_REVERSAL_PLAUSIBLE_NOT_CERTAIN"
        else -> "CONFLICTING_OR_AMBIGUOUS_STRUCTURE"
    }
    // This HOLD outcome is only a paper alternative to the book's exit.
    // Never turn an uncertain wave label into a live-market override.
    val paperHold=regime=="JUNIOR_CORRECTION_INSIDE_SENIOR_TREND_PLAUSIBLE"
    // Variant B2 is a second, stricter PAPER experiment only. 2026
    // historical OOS rejected the naive H4/D1 retention hypothesis.
    // Even two aligned confirmed senior swings are not proof of reversal.
    val expectedSwing=if(ownerDirection=="LONG") "HIGHER_HIGHS_HIGHER_LOWS"
        else "LOWER_HIGHS_LOWER_LOWS"
    val paperStrictHold=paperHold &&
        swingPatterns["4h"]==expectedSwing &&
        swingPatterns["1d"]==expectedSwing
    if(!allStructureKnown) uncertainty+="SENIOR_PIVOT_BOUNDS_UNVERIFIED"
    val uniqueHints=waveHints.distinct().take(24)
    return result.put("regime",regime)
        .put("context_hold_hypothesis",paperHold)
        .put("context_hold_strict_h4_d1_swings_shadow_only",paperStrictHold)
        .put("senior_contradiction_even_when_h4_d1_agree",
            seniorOwner && (swingPatterns["4h"]!=expectedSwing ||
                swingPatterns["1d"]!=expectedSwing))
        .put("contextual_hold_not_approved_by_historical_oos",true)
        .put("h1_bias",h1 ?: "UNVERIFIED").put("h4_bias",h4 ?: "UNVERIFIED")
        .put("d1_bias",d1 ?: "UNVERIFIED")
        .put("senior_trend_aligned_with_position",seniorOwner)
        .put("senior_trend_aligned_with_opposite",seniorOpponent)
        .put("h4_structure_broken",h4Broken ?: JSONObject.NULL)
        .put("d1_structure_broken",d1Broken ?: JSONObject.NULL)
        .put("closed_h1_h4_d1_swing_patterns",JSONObject(swingPatterns))
        .put("senior_swing_structure_matches_position",(
            if(ownerDirection=="LONG")
                swingPatterns["4h"]=="HIGHER_HIGHS_HIGHER_LOWS" &&
                swingPatterns["1d"]=="HIGHER_HIGHS_HIGHER_LOWS"
            else
                swingPatterns["4h"]=="LOWER_HIGHS_LOWER_LOWS" &&
                swingPatterns["1d"]=="LOWER_HIGHS_LOWER_LOWS"))
        .put("senior_swing_condition_is_book_exit_permission",false)
        .put("correction_vs_reversal_is_probabilistic_not_certified",true)
        .put("wave_correction_hypotheses",JSONArray(uniqueHints))
        .put("nested_wave_price_time_links",JSONArray(links.map { it.toJson() }))
        .put("nested_wave_link_count_observed",links.size)
        .put("wave_parent_child_containment_observed",genealogy?.hasRequiredContainedChain() ?: false)
        .put("wave_genealogy_frame_errors",JSONObject(genealogy?.frameErrors ?: emptyMap<String,String>()))
        .put("wave_parent_child_containment_is_elliott_proof",false)
        .put("wave_hypotheses_only",true)
        .put("uncertainties",JSONArray(uncertainty.distinct()))
        .put("frames",perFrame)
        .put("decision_rule","TC2_EXITS_UNCHANGED_SHADOW_A_B_ONLY")
        .put("reason",when(regime) {
            "JUNIOR_CORRECTION_INSIDE_SENIOR_TREND_PLAUSIBLE" ->
                "CLOSED_H4_AND_D1_OWNER_TREND_WITHOUT_CONFIRMED_SWING_BREAK"
            "SENIOR_REVERSAL_PLAUSIBLE_NOT_CERTAIN" ->
                "CLOSED_H1_H4_D1_COUNTERTREND_AND_CONFIRMED_SWING_BREAKS"
            else -> "NO_UNIQUE_SOURCE_CERTIFIED_PARENT_WAVE"
        })
}
