package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject

/**
 * Non-trading NTD book-management evidence.
 *
 * Reuses exactly the source's NTD PDF115-120 Teeth/Lips close and five-zone
 * stop planner. This emits a price/time level the hypothetical campaign could
 * have exited or tightened its stop; the paper baseline, real exchange
 * coordinator and Binance orders are intentionally unchanged.
 *
 * A SOURCE EXIT SIGNAL AT THE H1 CLOSE is not a provable exchange fill.
 */
internal fun williamsNtdObserverBookEvidence(
    paper:JSONObject, symbol:String,
    bars:List<WilliamsWaveBar>,
    teeth:List<Double>, lips:List<Double>,
    ao:List<Double>, ac:List<Double>,
    tick:Double, observedAtMs:Long
):JSONObject {
    val result=JSONObject(paper.toString())
    if(result.optString("profile")!=williamsNtdProfile ||
        result.optString("symbol")!=symbol || result.optBoolean("can_place_orders",false)) {
        return result.put("ntd_book_evidence_status","OBSERVER_SOURCE_AUTHORITY_INVALID")
            .put("can_place_orders",false)
    }
    val position=result.optJSONObject("paper_position") ?: return result
        .put("ntd_book_evidence_status","NO_HYPOTHETICAL_POSITION")
    val last=bars.lastOrNull() ?:return result
        .put("ntd_book_evidence_status","NO_CLOSED_H1")
    val end=last.t+WILLIAMS_H1_MS
    if(result.optLong("last_processed_open_ms")!=last.t ||
        observedAtMs<end || observedAtMs-end>=WILLIAMS_H1_MS ||
        result.optLong("ntd_book_last_observed_at_ms")==end)
        return result.put("ntd_book_evidence_status",
            if(result.optLong("ntd_book_last_observed_at_ms")==end)
                "ALREADY_OBSERVED" else "SOURCE_H1_NOT_CURRENT")
    // Never fabricate an exit using a bar at or before the entry.
    val entryAt=position.optLong("entry_bar_open_ms")
    if(entryAt<=0 || last.t<=entryAt)
        return result.put("ntd_book_evidence_status","NO_POST_ENTRY_CLOSED_BAR")
    if(result.optInt("source_signal_intervals_unobserved",0)>0)
        return result.put("ntd_book_evidence_status",
            "MISSED_H1_SOURCE_DECISIONS_NO_RETROSPECTIVE_BOOK_EXIT")
    if(williamsEditionSignalSeriesBlocker(bars,"1h")!=null ||
        bars.size!=teeth.size || bars.size!=lips.size ||
        bars.size!=ao.size || bars.size!=ac.size ||
        !tick.isFinite() || tick<=0)
        return result.put("ntd_book_evidence_status","INDICATORS_OR_PRICE_HISTORY_UNVERIFIED")

    val state=result.optJSONObject("ntd_book_management_state") ?: JSONObject()
    val plan=williamsNtdManagementPlan(position.getString("direction"),bars,
        teeth,lips,ao,ac,tick,entryAt,state)
    val evidence=plan.state.optString("zone_book_evidence")
    // The NTD close planner may return a confirmed rule, a rolling-stop
    // candidate or a history-failure state. Preserve the latter verbatim.
    if(evidence in setOf("NTD_H1_HISTORY_GAP",
        "NTD_ZONE_HISTORY_WINDOW_UNVERIFIED","NTD_BOOK_PRICE_OFF_EXCHANGE_TICK_GRID",
        "HISTORICAL_BOOK_STOP_TOUCHED_RECONCILE_EXCHANGE",
        "NTD_REPLAY_CONFLICTS_WITH_PERSISTED_STOP"))
        return result.put("ntd_book_evidence_status",evidence)

    val events=result.optJSONArray("events") ?: JSONArray()
    val closeReason=plan.exitReason
    val stop=plan.stop
    if(closeReason in setOf("TEETH_CLOSE_EXIT","LIPS_CLOSE_EXIT")) {
        val entry=position.optDouble("entry_price",Double.NaN)
        val direction=position.optString("direction")
        // NTD source specifies an Alligator CLOSE condition. The H1
        // closing price is observable only AFTER the candle has closed;
        // recording a theoretical price level is not proof that a
        // real market-on-close order could have filled at that price.
        val theoreticalPnl=if(entry.isFinite() && entry>0 &&
            last.c.isFinite() && last.c>0) {
            val gross=if(direction=="LONG") last.c-entry else entry-last.c
            // Fixed model parameters only; actual exchange fee/funding
            // requires authenticated fill records.
            gross-(entry+last.c)*0.0004
        } else Double.NaN
        events.put(JSONObject()
            .put("event","NTD_BOOK_CLOSE_EXIT_SIGNAL")
            .put("symbol",symbol).put("profile",williamsNtdProfile)
            .put("signal_id",position.optString("signal_id"))
            .put("direction",direction)
            .put("at_ms",end).put("source_h1_close_ms",end)
            .put("source_h1_close_price",last.c)
            .put("estimated_pnl_per_unit_if_filled_exactly_at_h1_close",
                theoreticalPnl.takeIf { it.isFinite() } ?: JSONObject.NULL)
            .put("assumed_fee_per_side",0.0004)
            .put("hypothetical_fill_at_h1_close_guaranteed",false)
            .put("funding_not_included",true)
            .put("source_book_exit_rule",closeReason)
            .put("not_exchange_fill",true)
            .put("hypothetical_only",true).put("exit_is_complete_book_rule",false)
            .put("can_place_orders",false))
    }
    if(stop!=null && stop.isFinite() && stop>0) {
        // A source stop PROPOSAL is not a paper fill. Never tighten using
        // an H1 candle until that candle has closed.
        events.put(JSONObject()
            .put("event","NTD_BOOK_TRAILING_STOP_PROPOSED")
            .put("symbol",symbol).put("profile",williamsNtdProfile)
            .put("signal_id",position.optString("signal_id"))
            .put("direction",position.optString("direction"))
            .put("at_ms",end).put("source_h1_close_ms",end)
            .put("source_book_stop_price",stop)
            .put("effective_not_before_ms",end)
            .put("source_book_rule",plan.state.optString("zone_book_rule"))
            .put("not_exchange_stop",true).put("hypothetical_only",true)
            .put("can_place_orders",false))
    }
    // A fail-close state is not asserted to be a valid NTD book rule.
    result.put("events",events)
        .put("ntd_book_last_observed_at_ms",end)
        .put("ntd_book_management_state",plan.state)
        .put("ntd_book_evidence_status",when {
            closeReason.isNotBlank()->"SOURCE_BOOK_EXIT_CONDITION_OBSERVED"
            stop!=null->"SOURCE_BOOK_STOP_CANDIDATE_OBSERVED"
            else->"NO_EXIT_CONDITION_ON_THIS_CLOSED_H1"
        })
    return result
}
