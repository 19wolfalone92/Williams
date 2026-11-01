package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject

/** TC1 (first edition) has its OWN research diary, not a disguised TC2 entry.
 *
 * Existing TC1 library evidence is about five-bullet terminal W5 indications
 * and author-specific MFI. It does not currently implement an independent,
 * validated exchange-style first-edition entry/exit engine. Therefore we
 * persist exactly the observed wave/price evidence with no hypothetical fills.
 */
internal const val WILLIAMS_TC1_OBSERVER = "TC1_FIRST_EDITION_RESEARCH"

internal fun williamsTc1ObserverKey(symbol:String):String {
    require(symbol.matches(Regex("[A-Z0-9]{4,20}")))
    return "williams_tc1_research_v1_"+symbol
}

internal fun williamsTc1ResearchAdvance(
    prior:JSONObject?, symbol:String, hierarchy:JSONObject, asOfMs:Long
):JSONObject {
    val result=prior?.let { JSONObject(it.toString()) } ?: JSONObject()
    if(prior!=null && (prior.optString("symbol")!=symbol ||
        prior.optString("profile")!=WILLIAMS_TC1_OBSERVER))
        return result.put("status","SOURCE_IDENTITY_MISMATCH").put("can_place_orders",false)
    result.put("schema","WILLIAMS_TC1_RESEARCH_V1").put("symbol",symbol)
        .put("profile",WILLIAMS_TC1_OBSERVER)
        .put("mode","TC1_WAVE_RESEARCH_ONLY_NO_TRADE_MODEL")
        .put("execution_role","OBSERVE").put("hypothetical_only",true)
        .put("can_place_orders",false).put("can_close",false).put("can_reverse",false)
        .put("full_book_entry_exit_reconstructed",false)
    if(asOfMs<=0L || hierarchy.optLong("as_of")!=asOfMs ||
        hierarchy.optJSONObject("frame_errors")?.has("1h")!=false) {
        return result.put("status","TC1_H1_WAVE_HISTORY_UNVERIFIED")
    }
    if(result.optLong("last_observed_at_ms")==asOfMs) return result
    val old=result.optJSONArray("events") ?: JSONArray()
    val events=JSONArray()
    for(i in maxOf(0,old.length()-239) until old.length()) events.put(old.getJSONObject(i))
    val seen=HashSet<String>()
    for(i in 0 until events.length())
        events.optJSONObject(i)?.optString("signal_id")?.let { seen.add(it) }
    val scenarios=hierarchy.optJSONArray("scenarios") ?: JSONArray()
    var observedCount=0
    for(i in 0 until scenarios.length()) {
        val scenario=scenarios.optJSONObject(i) ?: continue
        if(scenario.optString("frame")!="1h" ||
            scenario.optString("status")!="PROVISIONAL") continue
        val anchors=scenario.optJSONArray("anchors") ?: continue
        val scenarioId=scenario.optString("scenario_id")
        if(scenarioId.isBlank() || scenario.optString("direction") !in listOf("UP","DOWN"))
            continue
        // TC1 PDF61-65: a fourth-wave zero cross is a *minimum*
        // oscillator degree clue, not proof of a unique Elliott wave,
        // a source entry or any trade. A near-zero proxy is not enough.
        // Pin this evidence to the fifth CONFIRMED pivot and the author's
        // 100-140 bar scale. Do not relabel developing W3 as finished W4.
        if(anchors.length()>=5 && scenario.optString("w4_zero")=="MINIMUM_ONLY" &&
            scenario.optInt("scale_bars") in 100..140) {
            val pivot=anchors.optJSONObject(4)
            val id="TC1:W4_ZERO:"+scenarioId
            val known=pivot?.optLong("known_at") ?: 0L
            val price=pivot?.optDouble("price",Double.NaN) ?: Double.NaN
            if(id !in seen && known>0 && known<=asOfMs &&
                price.isFinite() && price>0) {
                events.put(JSONObject().put("event","TC1_H1_W4_AO_ZERO_CROSS_WATCH")
                    .put("at_ms",asOfMs).put("symbol",symbol)
                    .put("profile",WILLIAMS_TC1_OBSERVER).put("signal_id",id)
                    .put("wave_hypothesis",scenarioId)
                    .put("direction",scenario.optString("direction"))
                    .put("source_pivot_price",price)
                    .put("source_pivot_known_at_ms",known)
                    .put("source_book","TC1_PDF61_65")
                    .put("source_ao_zero_status","MINIMUM_ONLY")
                    .put("source_degree_scale_bars",scenario.optInt("scale_bars"))
                    .put("wave_degree_certified",false)
                    .put("actual_book_entry_price",JSONObject.NULL)
                    .put("actual_book_exit_price",JSONObject.NULL)
                    .put("hypothetical_only",true).put("can_place_orders",false))
                observedCount++
                seen.add(id)
            }
        }
        if(scenario.optString("label")!="W5") continue
        val proof=williamsTc1FiveBulletEvidence(scenario)
        val id="TC1:"+scenarioId
        if(id in seen) continue
        if(anchors.length()!=6) continue
        val terminal=anchors.optJSONObject(5) ?: continue
        val known=terminal.optLong("known_at")
        if(known<=0L || known>asOfMs) continue
        val price=terminal.optDouble("price",Double.NaN)
        if(!price.isFinite() || price<=0) continue
        events.put(JSONObject().put("event","TC1_H1_W5_TERMINAL_WATCH")
            .put("at_ms",asOfMs).put("symbol",symbol)
            .put("profile",WILLIAMS_TC1_OBSERVER).put("signal_id",id)
            .put("wave_hypothesis",scenario.optString("scenario_id"))
            .put("direction",scenario.optString("direction"))
            .put("source_pivot_price",price).put("source_pivot_known_at_ms",known)
            .put("five_bullets_status",proof.optString("status"))
            .put("mfi_canonical_verified",false)
            .put("actual_book_entry_price",JSONObject.NULL)
            .put("actual_book_exit_price",JSONObject.NULL)
            .put("hypothetical_only",true).put("can_place_orders",false)
            .put("no_unverified_first_edition_trade_fabrication",true))
        observedCount++
    }
    val trimmed=JSONArray()
    for(i in maxOf(0,events.length()-240) until events.length())
        trimmed.put(events.getJSONObject(i))
    return result.put("events",trimmed)
        .put("last_observed_at_ms",asOfMs)
        .put("new_source_wave_observations",observedCount)
        .put("status","TC1_WAVE_EVIDENCE_ONLY_NOT_A_PAPER_EXECUTOR")
}
