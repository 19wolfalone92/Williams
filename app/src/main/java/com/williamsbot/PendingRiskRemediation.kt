package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

internal data class PendingRiskRemediation(
    val symbol: String,
    val clientId: String,
    val measuredOpenRiskPct: Double,
    val totalWithPendingRiskPct: Double
)

/**
 * Conservative, read-only response to an already confirmed portfolio-risk breach.
 * A verified pending (unfilled) entry may be canceled; owned live positions and
 * their protective stops are NEVER eligible. The exchange remains authoritative:
 * a separate exact-id cancel/read-back must still confirm terminal order state.
 */
internal fun proposePendingRiskRemediation(
    blocker: String,
    positions: JSONArray,
    intents: JSONArray,
    maxPortfolioRisk: Double = FUTURES_MAX_PORTFOLIO_RISK_PCT
): PendingRiskRemediation? {
    if (!blocker.startsWith("ACTUAL_FUTURES_RISK_EXCEEDS_PORTFOLIO_CAP:") ||
        !maxPortfolioRisk.isFinite() || maxPortfolioRisk <= 0.0) return null

    val open = mutableSetOf<String>()
    for (i in 0 until positions.length()) {
        val row = positions.optJSONObject(i) ?: return null
        val symbol = row.optString("symbol").trim().uppercase()
        val qty = row.optString("positionAmt").toDoubleOrNull() ?: return null
        if (symbol.isBlank() || !qty.isFinite()) return null
        if (abs(qty) > 0.0) open += symbol
    }
    val rows = (0 until intents.length()).map { intents.optJSONObject(it) ?: return null }
    val live = rows.filter { it.optString("intent_role","ENTRY")=="ENTRY" &&
        campaignLifecycleStatus(it)=="OPEN_PROTECTED" }
    if (live.map { it.optString("symbol").uppercase() }.toSet()!=open ||
        live.size!=open.size) return null
    var measured = 0.0
    for (row in live) {
        if (!row.optBoolean("strategy_ownership_verified") ||
            row.optString("protection_status")!="PROTECTED") return null
        val risk=row.optDouble("actual_all_in_risk_pct",Double.NaN)
        if (!risk.isFinite() || risk<=0.0 || risk>FUTURES_MAX_ENTRY_RISK_PCT) return null
        measured+=risk
    }
    if (measured>maxPortfolioRisk+1e-9) return null // even removing pending cannot repair

    val pending=rows.filter {
        it.optString("intent_role","ENTRY")=="ENTRY" &&
            campaignLifecycleStatus(it)=="ENTRY_PENDING"
    }
    if (pending.isEmpty()) return null
    var total = measured
    val eligible=mutableListOf<JSONObject>()
    for (row in pending) {
        val symbol=row.optString("symbol").trim().uppercase()
        val risk=row.optDouble("risk_pct",Double.NaN)
        // Ambiguous / partly filled / owned exposure must not be "fixed" by
        // deleting an order without a reconciliation of its open position.
        if (symbol.isBlank() || symbol in open || !row.optBoolean("strategy_ownership_verified") ||
            !risk.isFinite() || risk<=0.0 || risk>FUTURES_MAX_ENTRY_RISK_PCT ||
            exchangeOrderStatus(row) !in setOf("NEW","PENDING","WORKING","ACCEPTED") ||
            row.optString("client_id").isBlank() ||
            (row.optString("executed_qty","0").toDoubleOrNull() ?: Double.NaN)!=0.0) return null
        total+=risk
        eligible+=row
    }
    if (total<=maxPortfolioRisk+1e-9) return null
    val newest = eligible.maxByOrNull { it.optLong("created_at_ms") } ?: return null
    return PendingRiskRemediation(newest.optString("symbol"),newest.optString("client_id"),measured,total)
}
