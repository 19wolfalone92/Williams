package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject

/**
 * Correlate a BOUNDED historical snippet of private WS FILLED events with
 * app-observed new algo-order and protected state events.
 *
 * This is an observational timing diagnostic, NOT proof of when a protective
 * stop actually entered Binance's matching engine. Without authenticated
 * order IDs on every emitted event, correlation is only symbol+sequence.
 * Never authorize, cancel or create an order from these estimates.
 */
internal object WilliamsFillStopTimingForensics {
    private const val WINDOW_MS=24L * 60 * 60 * 1000
    internal const val SLOW_PROTECTION_WARNING_MS=5_000L

    fun analyze(events:JSONArray, observedAtMs:Long):JSONObject {
        val valid=mutableListOf<JSONObject>()
        for(i in 0 until events.length()) {
            val e=events.optJSONObject(i) ?: continue
            val at=e.optLong("E",0L)
            if(at<=0L || at>observedAtMs ||
                observedAtMs-at>WINDOW_MS) continue
            valid.add(e)
        }
        valid.sortBy { it.optLong("E") }
        val observations=JSONArray()
        var slowCount=0
        var missingCount=0
        var seenFills=0
        var maxFillToAlgoMs=0L
        var maxFillToProtectedMs=0L
        for(pos in valid.indices) {
            val event=valid[pos]
            val detail=event.optString("detail")
            if(event.optString("e")!="COCKPIT_USER_WS" ||
                event.optString("state")!="ORDER_TRADE_UPDATE" ||
                !detail.contains("status=FILLED")) continue
            val symbol=event.optString("symbol")
            if(symbol.isBlank()) continue
            seenFills++
            if(observations.length()>=12) continue
            val filledAt=event.optLong("E")
            var algoNewAt=0L
            var protectedAt=0L
            var competingFill=false
            for(nextIndex in pos+1 until valid.size) {
                val next=valid[nextIndex]
                val at=next.optLong("E")
                if(at-filledAt>5L*60L*1000L) break
                if(next.optString("symbol")!=symbol) continue
                val eventType=next.optString("e")
                val state=next.optString("state")
                val msg=next.optString("detail")
                if(eventType=="COCKPIT_USER_WS" && state=="ORDER_TRADE_UPDATE" &&
                    msg.contains("status=FILLED")) {
                    // More than one FILLED, but no order/client id in the
                    // compact cockpit event: attribution is ambiguous.
                    competingFill=true
                    break
                }
                if(algoNewAt==0L && eventType=="COCKPIT_USER_WS" &&
                    state=="ALGO_UPDATE" && msg.contains("status=NEW"))
                    algoNewAt=at
                if(eventType=="COCKPIT_EXECUTION" && state=="OPEN_PROTECTED" &&
                    msg.contains("protection_status=PROTECTED") &&
                    msg.contains("Post-change audit=PASS")) {
                    protectedAt=at
                    break
                }
            }
            val gapToAlgo=if(algoNewAt>0L) algoNewAt-filledAt else -1L
            val gapToProtected=if(protectedAt>0L) protectedAt-filledAt else -1L
            val observedSlow=!competingFill && gapToProtected>SLOW_PROTECTION_WARNING_MS
            if(observedSlow) slowCount++
            if(competingFill || gapToProtected<0L) missingCount++
            if(gapToAlgo>maxFillToAlgoMs) maxFillToAlgoMs=gapToAlgo
            if(gapToProtected>maxFillToProtectedMs) maxFillToProtectedMs=gapToProtected
            observations.put(JSONObject()
                .put("symbol",symbol).put("fill_event_at_ms",filledAt)
                .put("first_new_algo_ws_at_ms",if(algoNewAt>0L) algoNewAt else JSONObject.NULL)
                .put("post_change_protected_audit_at_ms",
                    if(protectedAt>0L) protectedAt else JSONObject.NULL)
                .put("fill_to_algo_ws_ms",if(gapToAlgo>=0L) gapToAlgo else JSONObject.NULL)
                .put("fill_to_protected_audit_ms",
                    if(gapToProtected>=0L) gapToProtected else JSONObject.NULL)
                .put("status",when {
                    competingFill -> "AMBIGUOUS_MULTIPLE_FILLS"
                    observedSlow -> "WARNING_OBSERVED_SLOW_PROTECTION_CONFIRMATION"
                    gapToProtected<0L -> "NOT_VERIFIED_NO_PROTECTION_AUDIT_IN_RETAINED_EVENTS"
                    else -> "OBSERVED_BELOW_WARNING_THRESHOLD_NOT_E2E_CERTIFIED"
                })
                .put("correlation","SYMBOL_AND_EVENT_SEQUENCE_ONLY")
                .put("authenticated_exchange_timing_verified",false))
        }
        return JSONObject().put("schema","WILLIAMS_FILL_STOP_TIMING_FORENSICS_V1")
            .put("observed_at_ms",observedAtMs)
            .put("window_ms",WINDOW_MS)
            .put("retained_timeline_event_count",events.length())
            .put("window_valid_event_count",valid.size)
            .put("retained_event_limit_may_truncate_history",events.length()>=150)
            .put("fill_event_count",seenFills)
            .put("audited_fill_limit",12)
            .put("observed_slow_confirmation_count",slowCount)
            .put("unlinked_or_ambiguous_fill_count",missingCount)
            .put("warn_threshold_ms",SLOW_PROTECTION_WARNING_MS)
            .put("max_fill_to_algo_ws_ms",
                if(maxFillToAlgoMs>0L) maxFillToAlgoMs else JSONObject.NULL)
            .put("max_fill_to_protected_audit_ms",
                if(maxFillToProtectedMs>0L) maxFillToProtectedMs else JSONObject.NULL)
            .put("observations",observations)
            .put("severity",when {
                slowCount>0 -> "WARNING"
                else -> "NOT_VERIFIED"
            })
            .put("read_only",true)
            .put("independent_signed_protective_stop_placement_timing_verified",false)
            .put("note","The first app-observed algo NEW and OPEN_PROTECTED are " +
                "not the authenticated exchange creation timestamps. " +
                "Partial fills, absent events and same-symbol overlapping orders " +
                "require signed client-order-id chain receipts. Never infer an unprotected " +
                "interval or submit a duplicate STOP from this report.")
    }
}
