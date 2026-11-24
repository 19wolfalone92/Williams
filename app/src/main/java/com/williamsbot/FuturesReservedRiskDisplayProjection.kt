package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject

/**
 * O(N) read-only cockpit reserved-risk projection.
 *
 * Previous dashboard status called futuresCampaignReservedRisk(owner, journal)
 * for every non-terminal owner. That re-scanned the ENTIRE 400+ row durable
 * journal (O(N²)) every five seconds, even with no open position.
 *
 * The execution/risk admission path remains unchanged. This helper is a
 * DISPLAY projection only; never authorize a trade or retire an intent here.
 * Unknown risk retains conservativeRiskReservation(..., 0.006). Malformed
 * child fill status raises exactly as the original per-owner computation.
 */
internal fun futuresCockpitTotalReservedRisk(intents: JSONArray): Double {
    val ownersByClientId = mutableMapOf<String, Int>()
    var total = 0.0
    var owners = 0
    for (i in 0 until intents.length()) {
        val row = intents.optJSONObject(i) ?: continue
        if (row.optString("intent_role", "ENTRY") != "ENTRY" ||
            !isEntryCampaignReserved(row)) continue
        owners++
        val clientId = row.optString("client_id")
        ownersByClientId[clientId] = (ownersByClientId[clientId] ?: 0) + 1
        total += conservativeRiskReservation(row.optDouble("risk_pct", Double.NaN), 0.006)
    }
    if (owners == 0) return 0.0
    for (i in 0 until intents.length()) {
        val child = intents.optJSONObject(i) ?: error("Malformed Futures risk journal")
        if (child.optString("intent_role") != "ADD_ON") continue
        val count = ownersByClientId[child.optString("entry_client_id")] ?: continue
        val status = exchangeOrderStatus(child)
        val executed = child.optString("executed_qty", "0").toBigDecimalOrNull()
            ?: error("Malformed child fill reservation")
        if (status !in ENTRY_ORDER_TERMINAL_STATUSES ||
            executed.signum() > 0 || status == "FILLED") {
            val risk = conservativeRiskReservation(
                child.optDouble("risk_pct", Double.NaN), 0.006)
            repeat(count) { total += risk }
        }
    }
    return total
}
