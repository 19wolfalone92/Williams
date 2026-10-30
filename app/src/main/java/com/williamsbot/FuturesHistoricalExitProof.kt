package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject

/** A previously signed FLAT belongs to its original campaign/time. Opening a
 * NEW campaign on that symbol cannot undo the old FILLED exit. This predicate
 * neither proves the current account flat nor reconstructs missing fill/PnL.
 */
internal fun priorCampaignExitHasSignedFlatProof(
    exit: JSONObject, intents: JSONArray, nowMs: Long
): Boolean {
    if (exit.optString("intent_role") != "EXIT" ||
        exit.optString("exchange_status") !in setOf("EXIT_CONFIRMED", "EXIT_TERMINAL_POSITION_OPEN") ||
        exit.optString("order_status") != "FILLED") return false
    val flatAt = exit.optLong("confirmed_flat_at_ms")
    val createdAt = exit.optLong("created_at_ms")
    val quantity = exit.optString("quantity").toBigDecimalOrNull() ?: return false
    val executed = exit.optString("executed_qty").toBigDecimalOrNull() ?: return false
    if (flatAt <= 0 || flatAt > nowMs || createdAt <= 0 || createdAt > flatAt ||
        quantity.signum() <= 0 || executed.compareTo(quantity) != 0) return false
    val parentId = exit.optString("entry_client_id")
    if (parentId.isBlank() || exit.optString("client_id").isBlank()) return false
    val entries = CockpitDiagnostics.rows(intents).filter {
        it.optString("intent_role", "ENTRY") == "ENTRY" &&
            it.optString("symbol") == exit.optString("symbol")
    }
    val parent = entries.singleOrNull { it.optString("client_id") == parentId } ?: return false
    if (campaignLifecycleStatus(parent) != "CLOSED_OR_STOPPED") return false
    val latest = entries.lastOrNull() ?: return false
    return latest.optString("client_id") != parentId &&
        latest.optLong("created_at_ms") > flatAt && latest.optLong("created_at_ms") <= nowMs
}
