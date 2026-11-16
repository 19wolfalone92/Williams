package com.williamsbot

import org.json.JSONObject

/** Security-relevant private updates request signed REST reconciliation immediately. */
internal fun privateFuturesStateEventDemandsImmediateReconcile(event: JSONObject): Boolean =
    event.optString("e") in setOf(
        "ORDER_TRADE_UPDATE", "ACCOUNT_UPDATE", "ALGO_UPDATE",
        "ACCOUNT_CONFIG_UPDATE", "CONDITIONAL_ORDER_TRIGGER_REJECT"
    )
