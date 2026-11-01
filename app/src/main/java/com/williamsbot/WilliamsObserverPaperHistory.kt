package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/**
 * Durable, independently source-owned NTD / Course WHAT-IF ledger.
 *
 * This module never calls Binance, the Futures coordinator, order managers,
 * risk gates or real TradeJournal. Values are hypothetical closed-H1 OHLC
 * replay estimates, not exchange fills or certified author-book exits.
 *
 * Entries are STOP_ENTRY-only. ADD_ON and MARKET_ON_CLOSE are observed
 * but cannot silently become initial paper entries. Protective stops are
 * structural-source estimates, without trailing/additions/NTD-Course
 * discretionary exit authority. History gaps are quarantined.
 */
internal const val WILLIAMS_OBSERVER_PAPER_PREFIX = "williams_observer_paper_v1_"
private const val OBSERVER_HOUR_MS = 3_600_000L
private const val OBSERVER_MAX_EVENTS = 240
private const val OBSERVER_FEE_SIDE = 0.0004
private const val OBSERVER_SLIPPAGE_SIDE = 0.0005

internal fun williamsObserverPaperKey(symbol:String,profile:String):String {
    require(symbol.matches(Regex("[A-Z0-9]{4,20}")))
    require(profile in setOf(williamsNtdProfile,williamsCourseProfile))
    return WILLIAMS_OBSERVER_PAPER_PREFIX + symbol + "_" + profile
}

internal fun williamsObserverPaperAdvance(
    prior:JSONObject?,symbol:String,profile:String,
    candles:List<WilliamsWaveBar>,signalRows:JSONArray,observedAtMs:Long
):JSONObject {
    require(profile in setOf(williamsNtdProfile,williamsCourseProfile))
    require(symbol.matches(Regex("[A-Z0-9]{4,20}")))
    val state=prior?.let { JSONObject(it.toString()) } ?: JSONObject()
    if(prior!=null && (prior.optString("profile")!=profile || prior.optString("symbol")!=symbol)) {
        // Never silently transfer positions or paper history between books or pairs.
        return state.put("status","SOURCE_IDENTITY_MISMATCH").put("can_place_orders",false)
    }
    state.put("schema","WILLIAMS_OBSERVER_PAPER_V1")
        .put("symbol",symbol).put("profile",profile)
        .put("mode","RESEARCH_ONLY_NO_EXCHANGE_ORDERS")
        .put("execution_role","OBSERVE")
        .put("can_place_orders",false).put("can_close",false).put("can_reverse",false)
        .put("hypothetical_only",true)
        .put("fill_basis","H1_OHLC_CONSERVATIVE_INTRABAR_UNCERTAINTY")
        .put("entry_model","H1_STOP_ENTRY_NEXT_BAR_ONLY")
        .put("exit_model","INITIAL_BOOK_STOP_ONLY_NOT_FULL_NTD_COURSE")
        .put("fee_rate_per_side_assumed",OBSERVER_FEE_SIDE)
        .put("slippage_rate_per_side_assumed",OBSERVER_SLIPPAGE_SIDE)
    val events=state.optJSONArray("events") ?: JSONArray()
    fun event(kind:String,at:Long,detail:JSONObject=JSONObject()) {
        events.put(JSONObject(detail.toString())
            .put("event",kind).put("at_ms",at).put("symbol",symbol)
            .put("profile",profile).put("hypothetical_only",true))
    }
    fun result(status:String):JSONObject {
        val trimmed=JSONArray()
        for(i in max(0,events.length()-OBSERVER_MAX_EVENTS) until events.length())
            trimmed.put(events.getJSONObject(i))
        state.put("events",trimmed).put("status",status)
        return state
    }
    val seriesBlocker=williamsEditionSignalSeriesBlocker(candles,"1h")
    if(seriesBlocker!=null || observedAtMs<=0L ||
        candles.isEmpty() || candles.last().t+OBSERVER_HOUR_MS>observedAtMs ||
        observedAtMs-candles.last().t>=2*OBSERVER_HOUR_MS)
        return result("CLOSED_H1_HISTORY_UNVERIFIED").put("blocker",
            seriesBlocker ?: "MISSING_FRESH_EXCHANGE_CLOSED_H1")
    val previousAt=state.optLong("last_processed_open_ms",0L)
    val newest=candles.last().t
    if(previousAt>0L && previousAt< candles.first().t-OBSERVER_HOUR_MS)
        return result("UNRECOVERABLE_REPLAY_GAP").put("blocker","OLD_CLOSED_CANDLES_NO_LONGER_AVAILABLE")
    if(previousAt>newest)
        return result("HISTORY_CLOCK_ROLLBACK").put("blocker","OLDER_THAN_PERSISTED_SNAPSHOT")
    // No duplicate signals/fills on repeated scanner calls or APK restarts.
    if(previousAt==newest) return result("PAPER_OBSERVING")
    val incoming=if(previousAt>0L) candles.filter { it.t>previousAt } else listOf(candles.last())
    if(previousAt>0L && incoming.firstOrNull()?.t!=previousAt+OBSERVER_HOUR_MS)
        return result("H1_REPLAY_GAP").put("blocker","NONCONSECUTIVE_NEW_BARS")
    if(previousAt>0L && incoming.size>1)
        event("MISSED_INTRABAR_SOURCE_SIGNAL_SCANS",incoming.first().t,
            JSONObject().put("closed_bars_without_source_discovery",incoming.size-1))

    var pending=state.optJSONObject("pending")
    var position=state.optJSONObject("paper_position")
    var exitWatch=state.optJSONObject("paper_opposite_exit_watch")
    for(bar in incoming) {
        if(pending!=null && bar.t>=pending.getLong("known_at_ms")) {
            val expire=pending.optLong("expires_at_ms",0)
            if(expire>0 && bar.t>=expire) {
                event("PAPER_PENDING_EXPIRED",bar.t,JSONObject()
                    .put("signal_id",pending.getString("signal_id")))
                pending=null
            } else {
                val dir=pending.getString("direction")
                val trigger=pending.getDouble("trigger")
                val stop=pending.getDouble("stop")
                val hit=if(dir=="LONG") bar.h>=trigger else bar.l<=trigger
                val invalidated=if(dir=="LONG") bar.l<=stop else bar.h>=stop
                if(invalidated && !hit) {
                    // A source setup invalidated BEFORE any breakout cannot
                    // remain armed for a later H1 reversal. This remains a
                    // paper-only event; no Binance cancellation is issued.
                    event("PAPER_SETUP_INVALIDATED_BEFORE_ENTRY",bar.t,JSONObject()
                        .put("signal_id",pending.getString("signal_id"))
                        .put("invalidating_stop",stop))
                    pending=null
                } else if(hit) {
                    val raw=if(dir=="LONG") max(bar.o,trigger) else min(bar.o,trigger)
                    val fill=raw*(if(dir=="LONG") 1+OBSERVER_SLIPPAGE_SIDE else 1-OBSERVER_SLIPPAGE_SIDE)
                    val stop=pending.getDouble("stop")
                    // A skipped price past the original stop means the source
                    // setup is structurally incoherent, not an executable entry.
                    if((dir=="LONG" && fill<=stop) || (dir=="SHORT" && fill>=stop)) {
                        event("PAPER_ENTRY_INVALID_GAP",bar.t,JSONObject()
                            .put("signal_id",pending.getString("signal_id")))
                        pending=null
                    } else {
                        position=JSONObject().put("signal_id",pending.getString("signal_id"))
                            .put("signal_type",pending.getString("signal_type"))
                            .put("direction",dir).put("signal_bar_time_ms",pending.getLong("signal_bar_time_ms"))
                            .put("entry_bar_open_ms",bar.t).put("entry_price",fill)
                            .put("trigger",trigger).put("initial_stop",stop)
                        event("PAPER_ENTRY_FILLED_ESTIMATE",bar.t,JSONObject()
                            .put("signal_id",pending.getString("signal_id"))
                            .put("direction",dir).put("trigger_price",trigger)
                            .put("estimated_fill_price",fill).put("initial_stop",stop)
                            .put("assumed_slippage",OBSERVER_SLIPPAGE_SIDE)
                            .put("real_binance_fill",false))
                        pending=null
                    }
                }
            }
        }
        if(position!=null) {
            val dir=position.getString("direction")
            val stop=position.getDouble("initial_stop")
            val hit=if(dir=="LONG") bar.l<=stop else bar.h>=stop
            if(hit) {
                // Gap opens and unknown intrabar order are never scored
                // optimistically. If entry and stop both hit, assume stop.
                val raw=if(dir=="LONG") min(bar.o,stop) else max(bar.o,stop)
                val exit=raw*(if(dir=="LONG") 1-OBSERVER_SLIPPAGE_SIDE else 1+OBSERVER_SLIPPAGE_SIDE)
                val entry=position.getDouble("entry_price")
                val signed=if(dir=="LONG") exit-entry else entry-exit
                val fee=(entry+exit)*OBSERVER_FEE_SIDE
                event("PAPER_EXIT_INITIAL_STOP_ESTIMATE",bar.t,JSONObject()
                    .put("signal_id",position.getString("signal_id"))
                    .put("direction",dir).put("estimated_entry_price",entry)
                    .put("estimated_exit_price",exit).put("initial_stop",stop)
                    .put("pnl_per_unit_before_funding",signed-fee)
                    .put("entry_and_stop_same_h1_bar",bar.t==position.getLong("entry_bar_open_ms"))
                    .put("exit_is_complete_book_rule",false)
                    .put("exchange_fees_or_funding_verified",false))
                position=null
                exitWatch=null
            }
        }
        // Opposite source-level observation is a candidate to EXIT at a
        // particular price. NTD/Course have additional different book
        // exit rules; record this counterfactual price/time, but preserve
        // the independent initial-STOP baseline and never call it a
        // certified author-book exit or a Binance fill.
        if(position!=null && exitWatch!=null &&
            bar.t>=exitWatch.getLong("known_at_ms")) {
            val direction=position.getString("direction")
            val trigger=exitWatch.getDouble("trigger")
            val crossed=if(direction=="LONG") bar.l<=trigger else bar.h>=trigger
            if(crossed) {
                val raw=if(direction=="LONG") min(bar.o,trigger) else max(bar.o,trigger)
                val estimated=raw*(if(direction=="LONG") 1-OBSERVER_SLIPPAGE_SIDE else 1+OBSERVER_SLIPPAGE_SIDE)
                val entry=position.getDouble("entry_price")
                val signed=if(direction=="LONG") estimated-entry else entry-estimated
                event("PAPER_OPPOSITE_EXIT_TRIGGER_OBSERVED",bar.t,JSONObject()
                    .put("signal_id",exitWatch.getString("signal_id"))
                    .put("owned_entry_signal_id",position.getString("signal_id"))
                    .put("source_opposing_trigger",trigger)
                    .put("estimated_exit_price",estimated)
                    .put("pnl_per_unit_if_exited_before_funding",signed-(entry+estimated)*OBSERVER_FEE_SIDE)
                    .put("exit_is_complete_book_rule",false)
                    .put("does_not_close_stop_only_paper_baseline",true))
                exitWatch=null
            }
        }
    }
    // Only the CURRENTLY observed fully closed H1 can generate a fresh
    // hypothetical proposal; never backdate an undetected signal over a scan gap.
    val lastClose=newest+OBSERVER_HOUR_MS
    val proposals=(0 until signalRows.length()).mapNotNull { signalRows.optJSONObject(it) }
        .filter { it.optString("strategy_profile")==profile }
        .sortedWith(compareBy<JSONObject> { it.optLong("signal_bar_time_ms") }
            .thenBy { it.optString("signal_id") })
    val seen=(0 until events.length()).mapNotNull { events.optJSONObject(it)?.optString("signal_id") }
        .filter { it.isNotBlank() }.toSet()
    val unique=HashSet<String>()
    for(signal in proposals) {
        val sid=signal.optString("signal_id")
        if(sid.isBlank() || !unique.add(sid) || sid in seen ||
            sid==pending?.optString("signal_id") ||
            sid==position?.optString("signal_id")) continue
        val at=signal.optLong("signal_bar_time_ms")
        if(at<=0L || at+OBSERVER_HOUR_MS!=lastClose) continue
        val dir=signal.optString("direction")
        val role=signal.optString("role")
        val semantics=signal.optString("execution_semantics","STOP_ENTRY")
        val entry=signal.optDouble("trigger",Double.NaN)
        val stop=signal.optDouble("protective_stop",Double.NaN)
        val raw=JSONObject().put("signal_id",sid)
            .put("signal_type",signal.optString("type"))
            .put("role",role).put("direction",dir)
            .put("signal_bar_time_ms",at).put("known_at_ms",lastClose)
            .put("source_trigger_price",if(entry.isFinite()) entry else JSONObject.NULL)
            .put("source_stop_price",if(stop.isFinite()) stop else JSONObject.NULL)
            .put("execution_semantics",semantics)
        if(role!="ENTRY" || semantics!="STOP_ENTRY") {
            event("SOURCE_SIGNAL_OBSERVED_NOT_INITIAL_PAPER_ENTRY",lastClose,
                raw.put("reason",if(role!="ENTRY") "ADD_ON_NEEDS_SOURCE_OWNED_CAMPAIGN" else "CLOSE_EVENT_NOT_STOP_ENTRY"))
            continue
        }
        if(dir !in listOf("LONG","SHORT") || !entry.isFinite() || !stop.isFinite() ||
            entry<=0 || stop<=0 || (dir=="LONG" && stop>=entry) ||
            (dir=="SHORT" && stop<=entry) ||
            (dir=="LONG" && candles.last().c>=entry) ||
            (dir=="SHORT" && candles.last().c<=entry)) {
            event("PAPER_SIGNAL_REJECTED",lastClose,raw.put("reason","INVALID_OR_CONSUMED_ENTRY"))
            continue
        }
        if(position!=null) {
            if(dir!=position.optString("direction") && exitWatch==null) {
                exitWatch=JSONObject().put("signal_id",sid)
                    .put("known_at_ms",lastClose).put("trigger",entry)
                    .put("direction",dir)
                event("PAPER_OPPOSITE_EXIT_CANDIDATE_ARMED",lastClose,
                    raw.put("reason","BOOK_EXIT_SEMANTICS_NOT_YET_CERTIFIED"))
            } else event("PAPER_EXIT_HYPOTHESIS_ONLY",lastClose,
                raw.put("reason","ALTERNATE_SOURCE_TRIGGER_NOT_VERIFIED_EXIT"))
            continue
        }
        if(pending!=null) {
            event("PAPER_ALTERNATE_SIGNAL_NOT_ARMED",lastClose,
                raw.put("reason","ONE_SOURCE_ONE_PENDING_PAPER_POSITION"))
            continue
        }
        val expires=signal.optLong("expires_at_ms",0L)
        pending=JSONObject().put("signal_id",sid)
            .put("signal_type",signal.optString("type"))
            .put("direction",dir).put("signal_bar_time_ms",at)
            .put("known_at_ms",lastClose).put("trigger",entry)
            .put("stop",stop).put("expires_at_ms",expires)
        event("PAPER_PENDING_ARMED",lastClose,raw.put("first_possible_fill_open_ms",lastClose))
    }
    state.put("last_processed_open_ms",newest)
        .put("last_closed_h1_at_ms",lastClose)
        .put("observation_at_ms",observedAtMs)
        .put("pending",pending ?: JSONObject.NULL)
        .put("paper_position",position ?: JSONObject.NULL)
        .put("paper_opposite_exit_watch",exitWatch ?: JSONObject.NULL)
        .remove("blocker")
    return result("PAPER_OBSERVING")
}
