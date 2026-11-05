package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

private const val TC2_SHADOW_HOUR_MS=3_600_000L
private const val TC2_SHADOW_ENTRY_EXIT_COST_RATE=0.0009
private const val TC2_SHADOW_MAX_EVENTS=64
private val TC2_SHADOW_HORIZONS=listOf(6,24)

/**
 * Forward-only, read-only paper comparison:
 * BOOK: exit at observed live contract on the genuine TC2 opposite signal.
 * CONTEXT: if a pre-recorded D1/H4 continuation hypothesis existed,
 * keep the original exchange STOP as a conservative simulated floor.
 *
 * The current partial H1 candle is NEVER replayed: its high/low may have
 * happened before the decision. Only COMPLETE H1 candles starting at the
 * NEXT hour are scored. This gap is reported as a study limitation.
 *
 * Simulations are NOT authenticated fills, and neither side can mutate an
 * exchange position. They do not represent full trailing TC2 book management.
 */
internal data class Tc2ExitShadowCandidate(
    val ownerId:String, val signalId:String, val direction:String,
    val decisionAtMs:Long, val entryPrice:Double, val observedContract:Double,
    val signedStop:Double, val signedQuantity:Double, val regime:JSONObject
)

private fun tc2ShadowReplayHorizon(
    entry:JSONObject, closed:List<WilliamsWaveBar>, asOf:Long, hours:Int,
    trailingBars:Int?=null
):JSONObject {
    val result=JSONObject().put("status","PENDING_FORWARD_H1")
        .put("horizon_hours",hours).put("replay_method","NEXT_FULL_H1_BARS_NO_PARTIAL_BAR")
        .put("actual_binance_fills",false).put("includes_trailing_stop",trailingBars!=null)
        .put("holding_existing_signed_stop_static",trailingBars==null)
        .put("trailing_bars",trailingBars ?: JSONObject.NULL)
    if(hours<=0 || asOf<=0L || trailingBars!=null && trailingBars !in setOf(3,5))
        return result.put("status","INVALID_HORIZON_OR_TRAIL")
    val at=entry.optLong("decision_at_ms",0L)
    val from=(at/TC2_SHADOW_HOUR_MS+1)*TC2_SHADOW_HOUR_MS
    val until=from+hours*TC2_SHADOW_HOUR_MS
    if(until>asOf) return result.put("decision_boundary_excluded_until_ms",from)
        .put("full_replay_available_at_ms",until)
    val rows=closed.filter { it.t in from until until }
    if(rows.size!=hours || rows.withIndex().any { (i,b) ->
        b.t!=from+i*TC2_SHADOW_HOUR_MS || b.t+TC2_SHADOW_HOUR_MS>asOf ||
            listOf(b.o,b.h,b.l,b.c).any { !it.isFinite() || it<=0.0 } ||
            b.h<maxOf(b.o,b.l,b.c) || b.l>minOf(b.o,b.h,b.c)
    }) return result.put("status","INVALID_OR_MISSING_FORWARD_H1")
        .put("full_replay_available_at_ms",until)
    val dir=entry.optString("direction")
    val sign=if(dir=="LONG") 1.0 else if(dir=="SHORT") -1.0 else return result.put("status","INVALID_SIDE")
    val openPrice=entry.optDouble("entry_price",Double.NaN)
    val book=entry.optDouble("book_exit_contract",Double.NaN)
    val stop=entry.optDouble("original_exchange_stop",Double.NaN)
    val qty=entry.optDouble("signed_quantity_abs",Double.NaN)
    if(listOf(openPrice,book,stop,qty).any { !it.isFinite() || it<=0.0 } ||
        sign*(book-stop)<=0.0) return result.put("status","INVALID_SIGNED_STOP_OR_PRICES")
    var close=rows.last().c
    var reason="HORIZON_CLOSED_BAR_MARK"
    var stoppedAt=0L
    var maxAdverse=0.0
    var activeStop=stop
    var tightened=0
    // The previous 3/5 completed H1 candles are evidence for *this bar*.
    // At no time is this bar's high/low used to tighten its own STOP.
    val historyByOpen=closed.associateBy { it.t }
    for(bar in rows) {
        if(trailingBars!=null) {
            val preceding=(1..trailingBars).map {
                historyByOpen[bar.t-it*TC2_SHADOW_HOUR_MS]
            }
            if(preceding.any { it==null } || preceding.filterNotNull().any { b ->
                listOf(b.o,b.h,b.l,b.c).any { !it.isFinite() || it<=0.0 } ||
                    b.h<maxOf(b.o,b.l,b.c) || b.l>minOf(b.o,b.h,b.c)
            }) return result.put("status","INVALID_OR_MISSING_TRAILING_H1")
            val prior=preceding.filterNotNull()
            val candidateStop=if(sign>0.0) prior.minOf { it.l }
                else prior.maxOf { it.h }
            val stricter=if(sign>0.0) maxOf(activeStop,candidateStop)
                else minOf(activeStop,candidateStop)
            if(stricter!=activeStop) {activeStop=stricter;tightened++}
        }
        val adverse=if(sign>0.0) book-bar.l else bar.h-book
        // This H1 extreme could be AFTER the exchange STOP hit, so it is
        // an adverse OHLC UPPER BOUND, not proven intrabar drawdown.
        maxAdverse=maxOf(maxAdverse,adverse*qty)
        val stopHit=if(sign>0.0) bar.l<=activeStop else bar.h>=activeStop
        if(stopHit) {
            // Gap-through-STOP fills can be worse than the stop trigger.
            close=if(sign>0.0) minOf(activeStop,bar.o) else maxOf(activeStop,bar.o)
            reason=when {
                close!=activeStop -> "GAP_BEYOND_STOP"
                trailingBars!=null -> "HISTORIC_3_5_CLOSED_H1_TRAIL_TOUCHED"
                else -> "STATIC_EXCHANGE_STOP_TOUCHED"
            }
            stoppedAt=bar.t+TC2_SHADOW_HOUR_MS
            break
        }
    }
    fun estimatedClosePnl(price:Double):Double {
        val exit=price-sign*price*TC2_SHADOW_ENTRY_EXIT_COST_RATE
        // Same initial entry price/fee cancels in A/B delta, but we keep both
        // sides' costs in each estimated whole-campaign PnL.
        return sign*(exit-openPrice)*qty-
            openPrice*qty*TC2_SHADOW_ENTRY_EXIT_COST_RATE
    }
    val bookPnl=estimatedClosePnl(book)
    val holdPnl=estimatedClosePnl(close)
    val proposedHold=entry.optBoolean("paper_context_hold",false)
    val proposedStrictHold=entry.optBoolean("paper_strict_h4_d1_hold",false)
    return result.put("status","MATURED")
        .put("from_full_hour_ms",from).put("as_of_closed_ms",until)
        .put("book_exit_price_reference",book)
        .put("hold_exit_price_reference",close)
        .put("hold_outcome_reason",reason)
        .put("static_stop_hit_at_ms",if(stoppedAt>0L && trailingBars==null)
            stoppedAt else JSONObject.NULL)
        .put("trailing_stop_hit_at_ms",if(stoppedAt>0L && trailingBars!=null)
            stoppedAt else JSONObject.NULL)
        .put("original_exchange_stop",stop)
        .put("paper_final_stop",activeStop)
        .put("paper_trailing_tightening_events",tightened)
        .put("book_estimated_net_usdt",bookPnl)
        .put("hold_estimated_net_usdt",holdPnl)
        .put("context_estimated_net_usdt",if(proposedHold) holdPnl else bookPnl)
        .put("context_minus_book_estimated_usdt",if(proposedHold) holdPnl-bookPnl else 0.0)
        .put("strict_h4_d1_context_minus_book_estimated_usdt",
            if(proposedStrictHold) holdPnl-bookPnl else 0.0)
        .put("hold_minus_book_estimated_usdt",holdPnl-bookPnl)
        .put("hold_max_intrabar_adverse_from_decision_usdt",maxAdverse)
        .put("intrabar_adverse_is_unverified_upper_bound",true)
        .put("paper_trailing_does_not_assert_exchange_order_mutation",true)
        .put("return_not_certified",true)
        .put("fee_and_slippage_per_side_assumed",TC2_SHADOW_ENTRY_EXIT_COST_RATE)
        .put("funding_included",false)
        .put("intrabar_decision_hour_unobserved",true)
}

internal fun williamsTc2ExitShadowAdvance(
    old:JSONObject?, symbol:String, atMs:Long,
    h1Input:List<WilliamsWaveBar>,
    candidate:Tc2ExitShadowCandidate?=null
):JSONObject {
    require(symbol.matches(Regex("[A-Z0-9]{4,20}"))) { "INVALID_SHADOW_SYMBOL" }
    val state=old?.let { JSONObject(it.toString()) } ?: JSONObject()
    if(state.has("symbol") && state.optString("symbol")!=symbol)
        return state.put("status","SHADOW_IDENTITY_MISMATCH")
    val events=state.optJSONArray("events") ?: JSONArray()
    val unique=mutableSetOf<String>()
    val retained=mutableListOf<JSONObject>()
    for(i in 0 until events.length()) {
        val e=events.optJSONObject(i) ?: continue
        val key=e.optString("key")
        if(key.isNotEmpty() && unique.add(key)) retained+=JSONObject(e.toString())
    }
    if(candidate!=null) {
        val c=candidate
        val key=c.ownerId+":"+c.signalId
        val valid=c.ownerId.isNotBlank() && c.signalId.isNotBlank() && c.direction in setOf("LONG","SHORT") &&
            c.decisionAtMs>0L && c.decisionAtMs<=atMs &&
            listOf(c.entryPrice,c.observedContract,c.signedStop,c.signedQuantity)
                .all { it.isFinite() && it>0.0 } &&
            (if(c.direction=="LONG") c.signedStop<c.observedContract
                else c.signedStop>c.observedContract) &&
            c.regime.optString("schema")=="WILLIAMS_TC2_SENIOR_EXIT_CONTEXT_V1" &&
            c.regime.optString("mode")=="SHADOW_ONLY" &&
            c.regime.optLong("as_of_ms")<=c.decisionAtMs
        if(valid && unique.add(key)) {
            retained+=JSONObject().put("key",key).put("owner_id",c.ownerId)
                .put("signal_id",c.signalId).put("direction",c.direction)
                .put("decision_at_ms",c.decisionAtMs)
                .put("entry_price",c.entryPrice).put("book_exit_contract",c.observedContract)
                .put("original_exchange_stop",c.signedStop)
                .put("signed_quantity_abs",c.signedQuantity)
                .put("regime_at_decision",JSONObject(c.regime.toString()))
                .put("paper_context_hold",c.regime.optBoolean("context_hold_hypothesis",false))
                .put("paper_strict_h4_d1_hold",
                    c.regime.optBoolean("context_hold_strict_h4_d1_swings_shadow_only",false))
                .put("book_exit_is_paper_reference",true)
                .put("may_authorize_orders",false)
        }
    }
    // Causal prefix first. Any later/full-but-future bar must never affect
    // a counterfactual that is not yet mature at this observation time.
    val bars=h1Input.filter { it.t>=0 && it.t<=atMs-TC2_SHADOW_HOUR_MS }
        .sortedBy { it.t }
    val replay=retained.takeLast(TC2_SHADOW_MAX_EVENTS)
    for(e in replay) for(h in TC2_SHADOW_HORIZONS) {
        for((key,trailing) in listOf(
            "outcome_${h}h" to null,
            "outcome_${h}h_trail3" to 3,
            "outcome_${h}h_trail5" to 5
        )) {
            if(e.optJSONObject(key)?.optString("status")=="MATURED") continue
            val outcome=tc2ShadowReplayHorizon(e,bars,atMs,h,trailing)
            if(outcome.optString("status")!="PENDING_FORWARD_H1")
                e.put(key,outcome)
        }
    }
    val summary=JSONObject()
    for(h in TC2_SHADOW_HORIZONS) {
        val outcomes=replay.mapNotNull { it.optJSONObject("outcome_${h}h") }
            .filter { it.optString("status")=="MATURED" }
        val proposed=replay.filter { it.optBoolean("paper_context_hold") }
            .mapNotNull { it.optJSONObject("outcome_${h}h") }
            .filter { it.optString("status")=="MATURED" }
        val strictProposed=replay.filter { it.optBoolean("paper_strict_h4_d1_hold") }
            .mapNotNull { it.optJSONObject("outcome_${h}h") }
            .filter { it.optString("status")=="MATURED" }
        summary.put("h${h}",JSONObject()
            .put("matured",outcomes.size)
            .put("context_hold_matured",proposed.size)
            .put("context_hold_better_than_book",proposed.count {
                it.optDouble("context_minus_book_estimated_usdt")>0.0
            })
            .put("context_hold_worse_than_book",proposed.count {
                it.optDouble("context_minus_book_estimated_usdt")<0.0
            })
            .put("context_minus_book_estimated_total_usdt",outcomes.sumOf {
                it.optDouble("context_minus_book_estimated_usdt",0.0)
            })
            .put("strict_h4_d1_swing_hold_matured",strictProposed.size)
            .put("strict_h4_d1_swing_hold_better_than_book",strictProposed.count {
                it.optDouble("strict_h4_d1_context_minus_book_estimated_usdt")>0.0
            })
            .put("strict_h4_d1_swing_hold_worse_than_book",strictProposed.count {
                it.optDouble("strict_h4_d1_context_minus_book_estimated_usdt")<0.0
            })
            .put("strict_h4_d1_swing_hold_minus_book_estimated_total_usdt",outcomes.sumOf {
                it.optDouble("strict_h4_d1_context_minus_book_estimated_usdt",0.0)
            })
            .put("is_statistical_proof",false))
    }
    for(h in TC2_SHADOW_HORIZONS) for(trail in listOf(3,5)) {
        val key="outcome_${h}h_trail${trail}"
        val matured=replay.mapNotNull { it.optJSONObject(key) }
            .filter { it.optString("status")=="MATURED" }
        val holds=replay.filter { it.optBoolean("paper_context_hold") }
            .mapNotNull { it.optJSONObject(key) }
            .filter { it.optString("status")=="MATURED" }
        val strict=replay.filter { it.optBoolean("paper_strict_h4_d1_hold") }
            .mapNotNull { it.optJSONObject(key) }
            .filter { it.optString("status")=="MATURED" }
        summary.put("h${h}_trail${trail}",JSONObject()
            .put("matured",matured.size)
            .put("context_hold_matured",holds.size)
            .put("context_hold_better_than_book",holds.count {
                it.optDouble("context_minus_book_estimated_usdt")>0.0
            })
            .put("context_hold_worse_than_book",holds.count {
                it.optDouble("context_minus_book_estimated_usdt")<0.0
            })
            .put("context_hold_estimated_total_delta_usdt",holds.sumOf {
                it.optDouble("context_minus_book_estimated_usdt",0.0)
            })
            .put("strict_hold_matured",strict.size)
            .put("strict_hold_worse_than_book",strict.count {
                it.optDouble("strict_h4_d1_context_minus_book_estimated_usdt")<0.0
            })
            .put("trailing_stop_hit_count",matured.count {
                it.opt("trailing_stop_hit_at_ms")!=JSONObject.NULL
            })
            .put("is_statistical_proof",false))
    }
    return JSONObject().put("schema","WILLIAMS_TC2_EXIT_SHADOW_AB_V1")
        .put("symbol",symbol)
        .put("status","OBSERVE_ONLY")
        .put("mode","NO_REAL_ORDERS")
        .put("as_of_ms",atMs)
        .put("max_events",TC2_SHADOW_MAX_EVENTS)
        .put("events",JSONArray(replay))
        .put("summary",summary)
        .put("can_place_orders",false).put("can_close",false)
        .put("can_modify_stop",false).put("can_reverse",false)
        .put("comparison_limits",JSONArray(listOf(
            "FIRST_DECISION_H1_PARTIAL_BAR_EXCLUDED",
            "STATIC_ORIGINAL_STOP_AND_CAUSAL_3_5_CLOSED_H1_TRAIL_SEPARATELY",
            "PAPER_TRAILING_NEVER_WRITES_BINANCE_STOP",
            "ACTUAL_H1_TRAILING_EXECUTION_FILLS_UNVERIFIED",
            "MARKET_CLOSE_AT_OBSERVED_CONTRACT_NOT_REAL_FILL",
            "NO_FUNDING_BORROW_OR_LIQUIDATION",
            "PER_SIDE_COST_ESTIMATES",
            "NO_CALIBRATED_PROBABILITY",
            "OBSERVATIONAL_SELECTION_BIAS")))
}
