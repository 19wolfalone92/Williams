package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject

/** Admission of NEW risk only. Existing pending/partial/triggering/unknown
 * orders remain liabilities in the fifteen-symbol reconciliation ledger.
 * Never remove a reservation, cancel by symbol alone, or gate STOP/exit here. */
internal const val WILLIAMS_SYMBOL_POLICY = "FIFTEEN_NEW_RISK_FIFTEEN_MANAGEMENT_V2"
// One canonical symbol universe for H1 scanning, NEW-risk admission and
// existing-liability management. Never silently narrow five approved pairs.
internal val williamsNewRiskSymbols = APPROVED_FUTURES_SYMBOLS.toList()
internal fun williamsNewRiskSymbolBlocker(symbol:String):String? =
    if(symbol in williamsNewRiskSymbols) null else "FUTURES_SYMBOL_NOT_APPROVED"

internal fun williamsSymbolPolicySnapshot():JSONObject = JSONObject()
    .put("policy",WILLIAMS_SYMBOL_POLICY)
    .put("new_risk_symbols",JSONArray(williamsNewRiskSymbols))
    .put("management_symbols",JSONArray(APPROVED_FUTURES_SYMBOLS))
    .put("existing_pending_policy","RECONCILE_OWNED_NO_REARM_NO_SYMBOL_ONLY_CANCEL")
    .put("existing_pending_risk_reserved",true)
