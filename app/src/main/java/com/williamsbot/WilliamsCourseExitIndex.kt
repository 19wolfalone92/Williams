package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject

/** Last matching signed Course W5 exit, in durable order.
 * Indexing removes O(closed campaigns * all intents) journal scans.
 * This is CANDIDATE SELECTION ONLY; every candidate still requires the
 * existing exact signed Binance order/fill/flat/STOP verification.
 */
internal fun indexWilliamsCourseTerminalExits(intents: JSONArray): Map<Pair<String,String>,JSONObject> {
    val byCampaign = HashMap<Pair<String,String>,JSONObject>()
    for (index in 0 until intents.length()) {
        val row = intents.optJSONObject(index) ?: continue
        if (row.optString("intent_role") != "EXIT" ||
            row.optString("reason") != "COURSE_W5_TERMINAL_CONFIRMED" ||
            row.optBoolean("partial_reduction", false) ||
            row.optString("exchange_status") != "EXIT_CONFIRMED" ||
            row.optString("order_status") != "FILLED"
        ) continue
        // A later matching EXIT replaces an earlier candidate, preserving
        // original lastOrNull semantics, even for repeated closed campaigns.
        byCampaign[row.optString("entry_client_id") to row.optString("symbol")] = row
    }
    return byCampaign
}
