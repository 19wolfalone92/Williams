package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject

private const val M15_MS = 900_000L

/**
 * Read-only evidence from closed M15 candles. This is NOT a separate trading
 * signal and never overrides the H1 trigger or authorizes an exchange order.
 * A pivot is only visible after its second right-hand candle has CLOSED.
 */
internal fun williamsM15FractalEvidence(input: List<WilliamsWaveBar>, direction: String, now: Long): JSONObject {
    val evidence = JSONObject().put("schema", "WILLIAMS_M15_EVIDENCE_V1")
        .put("frame", "15m").put("observed_at_ms", now)
        .put("direction", direction).put("trade_authority", false)
        .put("role", "OBSERVATION_ONLY")
        .put("fractal_price", JSONObject.NULL)
        .put("fractal_formed_at_ms", JSONObject.NULL)
        .put("fractal_confirmed_at_ms", JSONObject.NULL)
    if (now <= 0 || direction !in setOf("LONG", "SHORT")) {
        return evidence.put("state", "INVALID_REQUEST")
    }
    // Filtering BEFORE fractal calculation prevents look-ahead on forming bars.
    val bars = input.filter { it.t > 0 && it.t <= now - M15_MS }
        .sortedBy { it.t }
    evidence.put("closed_bar_count", bars.size)
    if (bars.size < 5 || !validWilliamsEditionBars(bars)) {
        return evidence.put("state", "CLOSED_HISTORY_UNAVAILABLE")
    }
    if (!bars.zipWithNext().all { (a, b) -> b.t - a.t == M15_MS }) {
        return evidence.put("state", "CLOSED_HISTORY_GAP")
    }
    val lastClose = bars.last().t + M15_MS
    evidence.put("last_closed_at_ms", lastClose)
    if (now < lastClose || now - lastClose > 30L * 60_000L) {
        return evidence.put("state", "CLOSED_HISTORY_STALE")
    }
    val requiredKind = if (direction == "LONG") "HIGH" else "LOW"
    val latest = confirmedWilliamsEditionFractals(bars)
        .filter { it.kind == requiredKind }
        .maxByOrNull { it.confirmation }
        ?: return evidence.put("state", "NO_CONFIRMED_FRACTAL")
    return evidence.put("state", "CONFIRMED_PIVOT_OBSERVED")
        .put("fractal_kind", latest.kind)
        .put("fractal_price", latest.price)
        .put("fractal_formed_at_ms", bars[latest.center].t)
        .put("fractal_confirmed_at_ms", bars[latest.confirmation].t + M15_MS)
}

/** Snapshots exactly the documented H1 plan, optional M15 observation and the
 * matching exchange journal row. Missing proof is explicitly NULL, not zero. */
internal fun williamsEntryPassport(
    symbol: String, execution: JSONObject?, orderChain: JSONArray
): JSONObject {
    val passport = JSONObject().put("schema", "WILLIAMS_ENTRY_PASSPORT_V1")
        .put("symbol", symbol).put("trade_authority", false)
        .put("state", "NO_EXECUTION_DECISION")
        .put("m15_evidence", JSONObject().put("state", "NOT_RECORDED")
            .put("trade_authority", false))
    if (execution == null || execution.optString("client_id").isBlank()) return passport
    val clientId = execution.optString("client_id")
    val direction = execution.optString("direction")
    val matched = (0 until orderChain.length()).mapNotNull { orderChain.optJSONObject(it) }
        .lastOrNull { it.optString("client_id") == clientId &&
            it.optString("direction") == direction && it.optString("symbol", symbol) == symbol }
    val plan = execution.optJSONObject("setup_context")
    val outcome = execution.optJSONObject("outcome") ?: JSONObject()
    passport.put("state", if (!outcome.optBoolean("accepted", false)) "REJECTED_OR_UNCONFIRMED"
        else if (matched != null) "INTENT_JOURNALED" else "ACCEPTED_UNVERIFIED")
        .put("observed_at_ms", execution.optLong("observed_at_ms"))
        .put("client_id", clientId).put("direction", direction)
        .put("signal_id", execution.optString("signal_id"))
        .put("signal_type", execution.optString("signal_type"))
        .put("strategy_profile", execution.optString("strategy_profile"))
        .put("h1_signal_at_ms", execution.optLong("signal_bar_time_ms"))
        .put("h1_signal_reason", execution.optString("signal_reason").ifBlank { "нет данных" })
        .put("entry_price", execution.optString("entry_price").takeIf { it.isNotBlank() } ?: JSONObject.NULL)
        .put("protective_stop", execution.optString("protective_stop").takeIf { it.isNotBlank() } ?: JSONObject.NULL)
        .put("risk_pct", if (execution.has("risk_pct")) execution.opt("risk_pct") else JSONObject.NULL)
        .put("execution_outcome", JSONObject().put("accepted", outcome.optBoolean("accepted", false))
            .put("blocker", outcome.optString("blocker").ifBlank { "нет данных" }))
        .put("order_status", matched?.optString("order_status")?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
        .put("campaign_status", matched?.optString("campaign_status")?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
        .put("protection_status", matched?.optString("protection_status")?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
        .put("exchange_observed_at_ms", matched?.optLong("last_reconciled_ms")?.takeIf { it > 0 } ?: JSONObject.NULL)
        .put("m15_evidence", plan?.optJSONObject("m15_evidence") ?: JSONObject()
            .put("state", "NOT_RECORDED").put("trade_authority", false))
    return passport
}

/** Audit events are facts about the attempted decision, not an order directive. */
internal fun williamsEntryPassportEvent(plan: NativeFuturesCampaignCoordinator.EntryPlan,
    phase: String, now: Long, outcome: JSONObject? = null): JSONObject =
    JSONObject().put("schema", "WILLIAMS_ENTRY_PASSPORT_EVENT_V1")
        .put("symbol", plan.symbol).put("direction", plan.direction.name)
        .put("client_id", plan.clientId).put("signal_id", plan.signalId)
        .put("signal_type", plan.signalType).put("strategy_profile", plan.strategyProfile)
        .put("signal_bar_time_ms", plan.signalBarTimeMs)
        .put("observed_at_ms", now).put("phase", phase)
        .put("entry_price", plan.entryPrice).put("protective_stop", plan.protectiveStop)
        .put("risk_pct", plan.riskPct)
        .put("m15_evidence", runCatching { JSONObject(plan.setupContext).optJSONObject("m15_evidence") }.getOrNull() ?: JSONObject().put("state", "NOT_RECORDED"))
        .put("outcome", outcome ?: JSONObject.NULL)
        .put("trade_authority", false)
