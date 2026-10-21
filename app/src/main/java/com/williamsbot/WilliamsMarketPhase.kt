package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject

/** Keep evidence at its observed degree and with its own competing count.
 * Five booleans are not a calibrated score or a new entry/exit rule. */
internal fun williamsMarketPhaseContext(hierarchy:JSONObject):JSONObject {
    fun rows(a:JSONArray?)=if(a==null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    val asOf=hierarchy.optLong("as_of")
    val errors=hierarchy.optJSONObject("frame_errors") ?: JSONObject()
    val counts=hierarchy.optJSONObject("frame_candle_counts") ?: JSONObject()
    val all=rows(hierarchy.optJSONArray("scenarios"))
    val signs=linkedMapOf("divergence" to "AO_DIVERGENCE","momentum_shift" to "MOMENTUM_SHIFT",
        "terminal_fractal" to "TERMINAL_FRACTAL","target_zone" to "TARGET_ZONE","squat" to "SQUAT_VOLUME_PROXY")
    val frames=listOf("1d","6h","4h","1h","15m").map { frame ->
        val usable=all.filter { h ->
            val anchors=rows(h.optJSONArray("anchors"))
            h.optString("frame")==frame && h.optString("status")=="PROVISIONAL" &&
                h.optLong("observed_at") in 1L..asOf && anchors.size>=2 &&
                anchors.all { it.optLong("known_at") in 1L..asOf } &&
                h.optString("active_direction") in listOf("UP","DOWN") &&
                h.optString("active_label") in listOf("W1","W2","W3","W4","W5","A","B","C")
        }.takeIf { !errors.has(frame) }.orEmpty()
        val phases=usable.map { it.optString("active_label")+":"+it.optString("active_direction") }.distinct()
        val hypotheses=usable.map { h -> JSONObject().put("scenario_id",h.optString("scenario_id"))
            .put("last_confirmed_leg",h.optString("last_confirmed_leg",h.optString("label")))
            .put("active_label",h.optString("active_label")).put("active_direction",h.optString("active_direction"))
            .put("impulse_direction",h.optString("direction")).put("invalidation",h.opt("invalidation") ?: JSONObject.NULL)
            .put("w4_zero",h.optString("w4_zero")).put("scale_frame",h.optString("scale_frame"))
            .put("scale_bars",h.optInt("scale_bars"))
            .put("signs",JSONArray(signs.filterKeys { h.optBoolean(it) }.values.toList())) }
        JSONObject().put("frame",frame).put("state",when {
            errors.has(frame) -> "UNAVAILABLE"
            // H4 is a requested independent frame. Do not invent an H4
            // reading when history/scenarios were not provided by the engine.
            frame=="4h" && !counts.has(frame) && usable.isEmpty() -> "UNAVAILABLE"
            phases.isEmpty() -> "UNRESOLVED"
            phases.size>1 -> "ALTERNATIVES"
            else -> "PHASE_CANDIDATE"
        }).put("history_error",errors.optString(frame).takeIf { it.isNotEmpty() } ?: JSONObject.NULL)
            .put("hypotheses",JSONArray(hypotheses))
            .put("history_candle_count",if(counts.has(frame)) counts.optInt(frame) else JSONObject.NULL)
            .put("hypothesis_count",hypotheses.size)
            .put("wave_degree_confirmed",false)
            .put("probability_calibrated",false)
            .put("can_place_orders",false).put("can_close",false)
            .put("exhaustion_warning",usable.any { it.optBoolean("divergence") || it.optBoolean("momentum_shift") })
    }
    fun warns(frame:String)=frames.any { it.optString("frame")==frame && it.optBoolean("exhaustion_warning") }
    val scope=when {
        warns("1d") || warns("6h") || warns("4h") -> "SENIOR_WARNING"
        warns("1h") -> "H1_WARNING"
        warns("15m") -> "M15_ONLY_WARNING"
        else -> "NONE"
    }
    return JSONObject().put("schema","WILLIAMS_MARKET_PHASE_V1").put("as_of",asOf)
        .put("frames",JSONArray(frames)).put("warning_scope",scope)
        .put("wave_genealogy_confirmed",false).put("probability_calibrated",false)
        .put("action_policy","SOURCE_SIGNAL_AND_PRICE_TRIGGER_REQUIRED")
        .put("can_place_orders",false).put("can_close",false).put("can_reverse",false)
}
