package com.williamsbot

import org.json.JSONArray
import java.math.BigDecimal

/**
 * Narrow read-only proof for continuing TC2 risk-DECREASING trailing on
 * symbols unrelated to an external open order. This never grants new entries,
 * market exits, orphan adoption or exchange order ownership.
 */
internal fun verifiedExternalStopManagementScope(
    protectionObservations: JSONArray,
    externalOrders: JSONArray,
    durableIntents: JSONArray
): Set<String> {
    if (externalOrders.length()==0) return emptySet()
    val conflictingSymbols=(0 until externalOrders.length()).mapNotNull { i ->
        externalOrders.optJSONObject(i)?.optString("symbol")?.takeIf { it.isNotBlank() }
    }.toSet()
    val supported=mutableSetOf<String>()
    for(i in 0 until protectionObservations.length()) {
        val proof=protectionObservations.optJSONObject(i) ?: continue
        val symbol=proof.optString("symbol")
        if(symbol.isBlank() || symbol in conflictingSymbols ||
            proof.optString("stop_proof")!="OWNED_STOP_SEEN" ||
            !proof.optBoolean("strategy_ownership_verified",false) ||
            !proof.optBoolean("automatic_trailing_authorized",false)) continue
        val matching=(0 until durableIntents.length()).mapNotNull {
            durableIntents.optJSONObject(it)
        }.filter { it.optString("symbol")==symbol &&
            it.optString("intent_role","ENTRY")=="ENTRY" }
        val owner=matching.lastOrNull() ?: continue
        if(owner.optString("campaign_status")!="OPEN_PROTECTED" ||
            owner.optString("direction")!=proof.optString("direction") ||
            owner.optString("strategy_profile",williamsTc2Profile)!=williamsTc2Profile ||
            owner.optString("protection_status")!="PROTECTED" ||
            owner.optString("previous_protection_client_id").isNotBlank() ||
            !owner.optBoolean("strategy_ownership_verified",false) ||
            owner.optString("protection_client_id").isBlank() ||
            owner.optString("protection_client_id")!=proof.optString("protection_client_id")) continue
        val qty=proof.optString("quantity").toBigDecimalOrNull()?.abs()
        val expected=owner.optString("confirmed_position_qty").toBigDecimalOrNull()
        val stop=proof.optString("stop").toBigDecimalOrNull()
        val recorded=owner.optString("protective_stop").toBigDecimalOrNull()
        if(qty==null || expected==null || qty.signum()<=0 || qty.compareTo(expected)!=0 ||
            stop==null || recorded==null || stop.signum()<=0 || stop.compareTo(recorded)!=0) continue
        supported.add(symbol)
    }
    return supported
}
