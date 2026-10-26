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
            .put("execution_role",if(williamsVerifiedNewRiskSource(source)) "LIVE" else "OBSERVE").put("can_place_orders",false)
            .put("evidence",JSONArray(evidence))
    })
    val marketPhase=williamsMarketPhaseContext(hierarchy)
    val h1Frames=marketPhase.optJSONArray("frames") ?: JSONArray()
    val h1=(0 until h1Frames.length()).mapNotNull { h1Frames.optJSONObject(it) }
        .singleOrNull { it.optString("frame")=="1h" } ?: JSONObject()
    val reading=williamsMarketReading(bars,marketPhase,"1h",now)
    val scenarios=hierarchy.optJSONArray("scenarios") ?: JSONArray()
    val phaseAsOf=hierarchy.optLong("as_of",0L)
    val active=(0 until scenarios.length()).mapNotNull { scenarios.optJSONObject(it) }.filter {
        it.optString("frame")=="1h" && it.optString("status")=="PROVISIONAL" &&
            phaseAsOf>0L && it.optLong("observed_at")==phaseAsOf }
    val waveState=when(h1.optString("state")) {
        "PHASE_CANDIDATE" -> "PROVISIONAL"
        "ALTERNATIVES" -> "ALTERNATIVES"
        else -> "UNRESOLVED"
    }
    // Context explains why a valid book setup appeared in a range or trend.
    // It deliberately does not authorize a trade or impose invented gates.
    val chartFresh=reading.optString("state")=="OBSERVED"
    val alligator=if(chartFresh) reading.optJSONObject("alligator")?.optString("state","UNKNOWN") ?: "UNKNOWN" else "UNKNOWN"
    val bias=if(chartFresh) reading.optString("observed_bias","UNKNOWN") else "UNKNOWN"
    val aoDirection=if(chartFresh) reading.optString("ao_direction","UNKNOWN") else "UNKNOWN"
    val contexts=JSONArray(rows.map { s ->
        val side=s.optString("direction")
        val flags=mutableListOf<String>()
        if(!chartFresh) flags+="H1_READING_UNVERIFIED"
        if(h1.optString("state")=="ALTERNATIVES") flags+="COMPETING_WAVE_COUNTS"
        if(h1.optString("state") !in setOf("PHASE_CANDIDATE","ALTERNATIVES"))
            flags+="WAVE_STRUCTURE_UNRESOLVED"
        if(alligator=="SLEEPING") flags+="SLEEPING_RANGE_POTENTIAL_BREAKOUT"
        if(alligator=="SATIATED") flags+="SATIATED_TREND_POTENTIAL_EXHAUSTION"
        if((side=="LONG" && bias=="DOWN") || (side=="SHORT" && bias=="UP"))
            flags+="OBSERVED_H1_BIAS_OPPOSES_ENTRY"
        if((side=="LONG" && aoDirection=="DOWN") || (side=="SHORT" && aoDirection=="UP"))
            flags+="AO_MOMENTUM_OPPOSES_ENTRY"
        if(s.optLong("signal_bar_time_ms",0L)<=0L ||
            s.optLong("signal_bar_time_ms") >= now)
            flags+="SIGNAL_BAR_NOT_CAUSALLY_KNOWN"
        JSONObject().put("signal_id",s.optString("signal_id"))
            .put("source",s.optString("strategy_profile")).put("family",s.optString("family",s.optString("signal_type")))
            .put("direction",side).put("signal_bar_time_ms",s.optLong("signal_bar_time_ms"))
            .put("h1_reading_state",reading.optString("state"))
            .put("observed_h1_close_ms",reading.optLong("as_of_close_ms",0L))
            .put("alligator_phase",alligator).put("observed_bias",bias).put("ao_direction",aoDirection)
            .put("wave_state",h1.optString("state","UNRESOLVED"))
            .put("distinct_wave_structures",h1.optInt("distinct_structural_hypotheses"))
            .put("flags",JSONArray(flags)).put("context_is_trade_veto",false)
            .put("wave_degree_confirmed",false).put("probability_calibrated",false)
            .put("can_place_orders",false).put("can_close",false)
    })
    return JSONObject().put("schema","WILLIAMS_DECISION_TRACE_V1").put("engine","ANDROID").put("symbol",symbol)
        .put("execution_policy",williamsExecutionPolicySnapshot())
        .put("strategy_profile",profile).put("frame","1h").put("observed_at_ms",now)
        .put("as_of_close_ms",bars.lastOrNull()?.t?.plus(3_600_000L) ?: 0L).put("closed_bar_count",bars.size)
        .put("history_contiguous",contiguous).put("indicators",indicators).put("divergences",divergences)
        .put("wave_state",waveState)
        .put("wave_hypotheses",JSONArray(active)).put("books",books).put("signals",signals)
        .put("entry_market_contexts",contexts)
        .put("market_phase",marketPhase)
        // TC1 is a separate historical book profile. Its Zero Point five-sign
        // observations are NEVER an entry/exit gate for the pinned TC2 campaign.
        .put("tc1_zero_point_observation",williamsTc1ZeroPointObservations(bars,now))
        .put("market_reading",reading)
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
