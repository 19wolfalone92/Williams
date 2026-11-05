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
    if(!allStructureKnown) uncertainty+="SENIOR_PIVOT_BOUNDS_UNVERIFIED"
    val uniqueHints=waveHints.distinct().take(24)
    return result.put("regime",regime)
        .put("context_hold_hypothesis",paperHold)
        .put("h1_bias",h1 ?: "UNVERIFIED").put("h4_bias",h4 ?: "UNVERIFIED")
        .put("d1_bias",d1 ?: "UNVERIFIED")
        .put("senior_trend_aligned_with_position",seniorOwner)
        .put("senior_trend_aligned_with_opposite",seniorOpponent)
        .put("h4_structure_broken",h4Broken ?: JSONObject.NULL)
        .put("d1_structure_broken",d1Broken ?: JSONObject.NULL)
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
