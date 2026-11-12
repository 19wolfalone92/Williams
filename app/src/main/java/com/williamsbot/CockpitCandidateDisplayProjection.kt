package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject

/**
 * READ-ONLY short-form candidate data for the every-5-second Cockpit.
 *
 * These fields are the complete input contract of MainActivity.parseCandidates.
 * Full per-candidate source proofs (waves, H1 candles, market readings) remain
 * in the normal /scanner and noncompact /cockpit endpoints and diagnostic ZIP.
 * Never use this display projection for strategy decisions or order admission.
 */
internal val cockpitCandidateDisplayKeys: Set<String> = setOf(
    "symbol", "score", "signal", "setup_score", "signal_strength",
    "breakout_distance_pct", "risk_pct", "risk_reward", "atr_pct", "spread_pct",
    "htf_confirmed", "setup_state", "reason", "wise_man_count", "signal_family",
    "wave_score", "wave_position", "wave_phase", "wave_confidence",
    "wave_exhaustion_risk", "nested_w3", "nested_w3_parent_w5", "wave_path",
    "wave_primary_count", "wave_alternative_count", "wave_abc_phase",
    "quant_score", "regime", "regime_score", "obi", "trade_flow_imbalance",
    "wave3_probability", "wave5_probability", "shadow_intent", "xai_factors",
    "campaign_ready", "entry_signal_type", "entry_trigger_price",
    "entry_protective_price"
)

internal fun cockpitCandidateDisplayProjection(source: JSONArray): JSONArray =
    JSONArray().apply {
        for (index in 0 until source.length()) {
            val original = source.optJSONObject(index)
                ?: throw IllegalStateException("Malformed read-only scan candidate")
            val projected = JSONObject()
            cockpitCandidateDisplayKeys.forEach { field ->
                if (original.has(field)) projected.put(field, original.opt(field))
            }
            put(projected)
        }
    }
