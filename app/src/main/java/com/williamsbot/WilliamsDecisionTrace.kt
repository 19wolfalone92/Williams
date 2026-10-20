package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject

/** Evidence only: no mutation API and no calibrated confidence/profit claim. */
internal fun buildWilliamsDecisionTrace(symbol:String,profile:String,input:List<WilliamsWaveBar>,now:Long,
    indicators:JSONObject,signals:JSONArray,hierarchy:JSONObject):JSONObject {
    val bars=input.filter { it.t>0L && it.t<=now-3_600_000L }
    val contiguous=bars.zipWithNext().all { (a,b) -> b.t-a.t==3_600_000L }
    val reasons=mutableListOf<String>()
    if(bars.size<45 || !validWilliamsEditionBars(bars) || !contiguous) reasons+="CLOSED_H1_HISTORY_NOT_PROVEN"
    if(bars.isEmpty() || now-(bars.lastOrNull()?.t ?: 0L)>7_200_000L) reasons+="H1_SNAPSHOT_STALE"
    val rows=(0 until signals.length()).mapNotNull { signals.optJSONObject(it) }
    val entries=rows.filter { it.optString("role")=="ENTRY" && (profile==williamsUnifiedProfile || it.optString("strategy_profile")==profile) }
    val latest=entries.maxOfOrNull { it.optLong("signal_bar_time_ms") }
    if(profile==williamsUnifiedProfile && entries.filter { it.optLong("signal_bar_time_ms")==latest }
        .map { it.optString("direction") }.distinct().size>1) reasons+="OPPOSING_ENTRIES_BEFORE_CONTEXT"
    if(entries.isEmpty()) reasons+="NO_VALID_INITIAL_FORMATION"
    fun oscillator(i:Int):Double? {
        if(i<33) return null
        fun mean(n:Int)=(i-n+1..i).map { (bars[it].h+bars[it].l)/2 }.average()
        return (mean(5)-mean(34)).takeIf { it.isFinite() }
    }
    val divergences=JSONArray()
    for(kind in listOf("HIGH","LOW")) {
        val pivots=confirmedWilliamsEditionFractals(bars).filter { it.kind==kind && oscillator(it.center)!=null }.takeLast(2)
        if(pivots.size==2) {
            val a=pivots[0];val b=pivots[1];val first=oscillator(a.center)!!;val second=oscillator(b.center)!!
            divergences.put(JSONObject().put("direction",if(kind=="HIGH") "BEARISH" else "BULLISH")
                .put("observed",if(kind=="HIGH") b.price>a.price && second<first else b.price<a.price && second>first)
                .put("first",JSONObject().put("bar_time_ms",bars[a.center].t).put("price",a.price).put("ao",first)
                    .put("confirmed_at_ms",bars[a.confirmation].t+3_600_000L))
                .put("second",JSONObject().put("bar_time_ms",bars[b.center].t).put("price",b.price).put("ao",second)
                    .put("confirmed_at_ms",bars[b.confirmation].t+3_600_000L)))
        }
    }
    val books=JSONArray(listOf(williamsTc2Profile,williamsNtdProfile,williamsCourseProfile).map { source ->
        val evidence=rows.flatMap { row ->
            val context=row.optJSONObject("setup_context") ?: JSONObject()
            val all=context.optJSONArray("book_evidence")
            if(all!=null) (0 until all.length()).mapNotNull { all.optJSONObject(it) }.filter { it.optString("profile")==source }
                .map { e -> JSONObject(e.toString()).put("signal_bar_time_ms",row.optLong("signal_bar_time_ms"))
                    .put("direction",row.optString("direction")).put("selected",context.optString("source_signal_id")==e.optString("signal_id")) }
            else if(row.optString("strategy_profile")==source) listOf(JSONObject(row.toString())) else emptyList()
        }
        JSONObject().put("profile",source).put("state",if(evidence.isEmpty()) "NO_VALID_FORMATION" else "FORMATION_OBSERVED")
            .put("execution_role",if(source==williamsExecutionProfile) "LIVE" else "OBSERVE").put("can_place_orders",false)
            .put("evidence",JSONArray(evidence))
    })
    val scenarios=hierarchy.optJSONArray("scenarios") ?: JSONArray()
    val active=(0 until scenarios.length()).mapNotNull { scenarios.optJSONObject(it) }.filter {
        it.optString("frame")=="1h" && it.optString("status")=="PROVISIONAL" }
    return JSONObject().put("schema","WILLIAMS_DECISION_TRACE_V1").put("engine","ANDROID").put("symbol",symbol)
        .put("execution_policy",williamsExecutionPolicySnapshot())
        .put("strategy_profile",profile).put("frame","1h").put("observed_at_ms",now)
        .put("as_of_close_ms",bars.lastOrNull()?.t?.plus(3_600_000L) ?: 0L).put("closed_bar_count",bars.size)
        .put("history_contiguous",contiguous).put("indicators",indicators).put("divergences",divergences)
        .put("wave_state",if(active.size==1) "PROVISIONAL" else if(active.isEmpty()) "UNRESOLVED" else "ALTERNATIVES")
        .put("wave_hypotheses",JSONArray(active)).put("books",books).put("signals",signals)
        .put("market_phase",williamsMarketPhaseContext(hierarchy))
        .put("market_reading",williamsMarketReading(bars,williamsMarketPhaseContext(hierarchy)))
        .put("candles",JSONArray(bars.takeLast(150).map { JSONArray(listOf(it.t,it.o,it.h,it.l,it.c,it.v)) }))
        .put("decision",JSONObject().put("stage","CLOSED_BAR_ANALYSIS")
            .put("state",if(reasons.any { it!="OPPOSING_ENTRIES_BEFORE_CONTEXT" }) "BLOCKED" else if(reasons.isNotEmpty()) "CONTEXT_REQUIRED" else "CANDIDATE_REQUIRES_PREFLIGHT")
            .put("reasons",JSONArray(reasons)).put("can_place_orders",false))
}
internal fun williamsExecutionChain(intents:JSONArray,symbol:String):JSONArray = JSONArray(
    (0 until intents.length()).mapNotNull { intents.optJSONObject(it) }.filter { it.optString("symbol")==symbol }.takeLast(12).map { row ->
        JSONObject().apply { listOf("symbol","client_id","entry_client_id","intent_role","direction","strategy_profile","signal_type","signal_id",
            "signal_bar_time_ms","exchange_status","order_status","campaign_status","protection_status","protection_client_id",
            "previous_protection_client_id","protective_stop","initial_protective_stop","quantity","confirmed_position_qty",
            "risk_pct","strategy_ownership_verified","last_reconciled_ms","created_at_ms","actual_entry_price",
            "executed_qty","exchange_order_id","fill_observed_at_ms")
            .forEach { key -> if(row.has(key)) put(key,row.get(key)) } }
    })
