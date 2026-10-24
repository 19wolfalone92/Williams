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

/** A quarantined opposite-side campaign on one pair cannot freeze verified
 * TC2 protection on another. This capability permits STOP TIGHTENING only.
 * The whole signed audit must have completed before calling this function. */
internal fun verifiedQuarantineStopManagementScope(
    protectionObservations: JSONArray,
    externalOrders: JSONArray,
    durableIntents: JSONArray,
    quarantinedSymbols: Set<String>,
    nowMs: Long
): Set<String> {
    if (quarantinedSymbols.isEmpty() || nowMs <= 0L) return emptySet()
    val conflicts=JSONArray(externalOrders.toString())
    quarantinedSymbols.forEach { conflicts.put(org.json.JSONObject().put("symbol",it)) }
    return verifiedExternalStopManagementScope(protectionObservations,conflicts,durableIntents)
        .filter { symbol ->
            val owner=(0 until durableIntents.length()).mapNotNull { durableIntents.optJSONObject(it) }
                .last { it.optString("symbol")==symbol && it.optString("intent_role","ENTRY")=="ENTRY" }
            val proof=(0 until protectionObservations.length()).mapNotNull { protectionObservations.optJSONObject(it) }
                .singleOrNull { it.optString("symbol")==symbol } ?: return@filter false
            fun fresh(at:Long)=at>0L && nowMs>=at && nowMs-at<=30_000L
            owner.optString("actual_risk_audit_status") in setOf("PASS","PASS_RESERVATION_ADJUSTED") &&
                owner.optString("actual_risk_audit_blocker").isBlank() &&
                owner.optString("liquidation_guard_status")=="PASS" &&
                !owner.has("native_stop_replacement") &&
                fresh(owner.optLong("actual_risk_audited_at_ms")) && fresh(proof.optLong("observed_at_ms")) &&
                (0 until durableIntents.length()).mapNotNull { durableIntents.optJSONObject(it) }.none {
                    it.optString("symbol")==symbol &&
                        (it.optString("intent_role")=="ADD_ON" && exchangeOrderStatus(it) !in ENTRY_ORDER_TERMINAL_STATUSES ||
                            it.optString("intent_role")=="EXIT" && it.optString("exchange_status")!="EXIT_CONFIRMED")
                }
        }.toSet()
}
