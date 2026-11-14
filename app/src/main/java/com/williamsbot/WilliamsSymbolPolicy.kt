package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject

/** Admission of NEW risk only. Existing pending/partial/triggering/unknown
 * orders remain liabilities in the fifteen-symbol reconciliation ledger.
 * Never remove a reservation, cancel by symbol alone, or gate STOP/exit here. */
internal const val WILLIAMS_SYMBOL_POLICY = "TEN_NEW_RISK_FIFTEEN_MANAGEMENT_V1"
internal val williamsNewRiskSymbols = listOf(
    "BTCUSDT", "ETHUSDT", "BNBUSDT", "SOLUSDT", "XRPUSDT",
    "DOGEUSDT", "ADAUSDT", "LINKUSDT", "AVAXUSDT", "LTCUSDT"
)
internal fun williamsNewRiskSymbolBlocker(symbol:String):String? =
    if(symbol in williamsNewRiskSymbols) null else "FUTURES_SYMBOL_MANAGEMENT_ONLY"

internal fun williamsSymbolPolicySnapshot():JSONObject = JSONObject()
    .put("policy",WILLIAMS_SYMBOL_POLICY)
    .put("new_risk_symbols",JSONArray(williamsNewRiskSymbols))
    .put("management_symbols",JSONArray(APPROVED_FUTURES_SYMBOLS))
    .put("existing_pending_policy","RECONCILE_OWNED_NO_REARM_NO_SYMBOL_ONLY_CANCEL")
    .put("existing_pending_risk_reserved",true)
