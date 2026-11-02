package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject

/**
 * Course Level III (PDF77-84) independent *research* location diary.
 * Reuses the causal source-rule stage-price predicates already present in
 * WilliamsCourseLocation.kt. Never treats a price zone, degree hypothesis,
 * volume proxy or four terminal proxy flags as a certified executable entry
 * or exchange-confirmed exit.
 *
 * Not called from the Futures order submission path. It cannot change
 * live TC2 positions or the paper stop-only benchmark.
 */
internal fun williamsCourseObserverLocations(
    paper:JSONObject, symbol:String, hierarchy:JSONObject,
    closedH1AtMs:Long
):JSONObject {
    val state=JSONObject(paper.toString())
    if(state.optString("symbol")!=symbol ||
        state.optString("profile")!=williamsCourseProfile ||
        state.optBoolean("can_place_orders",false)) return state
        .put("course_location_status","INVALID_SOURCE_OWNER")
        .put("can_place_orders",false)
    // A valid wave hierarchy is not enough: no hypothetical source
    // decisions may be emitted when the source's OWN H1 paper replay was
    // blocked by a gap, clock rollback, invalid OHLC or SQLite fault.
    // The next valid closed H1 starts a new strictly causal observation.
    if(state.optString("status")!="PAPER_OBSERVING" ||
        state.optLong("last_closed_h1_at_ms")!=closedH1AtMs ||
        state.optLong("last_processed_open_ms")!=closedH1AtMs-WILLIAMS_H1_MS ||
        state.optInt("source_signal_intervals_unobserved",0)!=0)
        return state.put("course_location_status",
            "SOURCE_PAPER_H1_REPLAY_UNVERIFIED")
    val asOf=hierarchy.optLong("as_of",0L)
    val frameErrors=hierarchy.optJSONObject("frame_errors")
    if(closedH1AtMs<=0 || asOf<closedH1AtMs ||
        asOf-closedH1AtMs>=WILLIAMS_H1_MS ||
        frameErrors==null || frameErrors.has("1h")) return state
        .put("course_location_status","H1_WAVE_SOURCE_UNVERIFIED")
    val previous=state.optLong("course_last_location_h1_close_ms",0L)
    if(previous==closedH1AtMs) return state
        .put("course_location_status","ALREADY_OBSERVED")
    if(previous>closedH1AtMs) return state
        .put("course_location_status","HISTORY_ROLLBACK_UNVERIFIED")
    val scenarios=hierarchy.optJSONArray("scenarios") ?:return state
        .put("course_location_status","NO_H1_SCENARIOS")
    val events=state.optJSONArray("events") ?: JSONArray()
    var watched=0
    var matching=0
    for(index in 0 until scenarios.length()) {
        val s=scenarios.optJSONObject(index) ?:continue
        if(s.optString("frame")!="1h" ||
            s.optString("status")!="PROVISIONAL" ||
            s.optLong("observed_at")!=asOf ||
            s.optString("scenario_id").isBlank()) continue
        val places=williamsCourseStageLocations(s,asOf)
        for(j in 0 until places.length()) {
            val location=places.optJSONObject(j) ?:continue
            if(location.optString("status")!="LOCATION_ONLY_REQUIRES_SOURCE_CONFIRMATION" ||
                !location.optBoolean("price_in_zone",false)) continue
            matching++
            val observedPrice=s.optDouble("observed_close_price",Double.NaN)
            if(!observedPrice.isFinite() || observedPrice<=0) continue
            events.put(JSONObject()
                .put("event","COURSE_H1_STAGE_PRICE_ZONE_WATCH")
                .put("at_ms",closedH1AtMs)
                .put("source_book","COURSE_LEVEL_III_PDF77_84")
                .put("source_h1_close_ms",closedH1AtMs)
                .put("symbol",symbol).put("profile",williamsCourseProfile)
                .put("signal_id","COURSE:"+s.getString("scenario_id")+":"+
                    location.optString("stage")+":"+closedH1AtMs)
                .put("wave_hypothesis_id",s.getString("scenario_id"))
                .put("direction",if(s.optString("direction")=="UP") "LONG" else "SHORT")
                .put("stage",location.optString("stage"))
                .put("source_h1_close_price",observedPrice)
                .put("price_zone",location.optJSONArray("price_zone") ?: JSONArray())
                .put("retracement_ratio",location.optDouble("retracement_ratio",Double.NaN)
                    .takeIf { it.isFinite() } ?: JSONObject.NULL)
                .put("illustrative_target_units",
                    location.opt("example_target_units") ?: JSONObject.NULL)
                .put("wave_degree_certified",false)
                .put("book_entry_confirmed",false)
                .put("book_exit_confirmed",false)
                .put("real_binance_fill",false)
                .put("hypothetical_only",true).put("can_place_orders",false))
            watched++
        }
        // Distinct exhaustion watch; four proxies are NOT equivalent to
        // source-validated complete Elliott degree or a market exit.
        if(s.optString("label")=="W5" &&
            s.optJSONArray("anchors")?.length()==6 &&
            // The four flags alone never prove a completed five-wave
            // geometry. Reuse the actual book-source anchor validator
            // (pivot kind, sequential origin, known-after-extreme,
            // no future pivots, price grammar) before journalling.
            williamsCourseCausalAnchors(s,asOf)?.size==6) {
            val signs=williamsCourseFourSignEvidence(s)
            if(signs.optString("status")=="FOUR_PROXY_FLAGS_OBSERVED_NOT_CERTIFIED") {
                val anchor=s.getJSONArray("anchors").getJSONObject(5)
                if(anchor.optLong("known_at")<=asOf &&
                    anchor.optLong("known_at")>0L) {
                    events.put(JSONObject()
                        .put("event","COURSE_H1_W5_TERMINAL_EXIT_WATCH")
                        .put("at_ms",closedH1AtMs)
                        .put("source_book","COURSE_LEVEL_III_PDF77")
                        .put("symbol",symbol).put("profile",williamsCourseProfile)
                        .put("signal_id","COURSE:W5:"+s.getString("scenario_id")+":"+closedH1AtMs)
                        .put("wave_hypothesis_id",s.getString("scenario_id"))
                        .put("direction",if(s.optString("direction")=="UP") "LONG" else "SHORT")
                        .put("source_pivot_price",anchor.optDouble("price"))
                        .put("source_pivot_known_at_ms",anchor.getLong("known_at"))
                        .put("wave_degree_certified",false)
                        .put("book_exit_confirmed",false)
                        .put("mfi_is_volume_proxy",true)
                        .put("real_binance_fill",false)
                        .put("hypothetical_only",true)
                        .put("can_place_orders",false))
                    watched++
                }
            }
        }
    }
    // The in-memory preview is intentionally bounded. The separate
    // append-only observer archive preserves all research events.
    val bounded=JSONArray()
    for(i in maxOf(0,events.length()-240) until events.length())
        bounded.put(events.getJSONObject(i))
    return state.put("events",bounded)
        .put("course_last_location_h1_close_ms",closedH1AtMs)
        .put("course_location_status",if(watched>0)
            "CAUSAL_BOOK_ZONES_OBSERVED_UNCERTIFIED" else "NO_SOURCE_CONFIRMED_PRICE_ZONE")
        .put("course_research_location_count",watched)
        .put("course_matching_unconfirmed_price_zones",matching)
}
