package com.williamsbot

import org.json.JSONObject

/** Security-relevant private updates request signed REST reconciliation immediately. */
internal fun privateFuturesStateEventDemandsImmediateReconcile(event: JSONObject): Boolean =
    event.optString("e") in setOf(
        "ORDER_TRADE_UPDATE", "ACCOUNT_UPDATE", "ALGO_UPDATE",
        "ACCOUNT_CONFIG_UPDATE", "CONDITIONAL_ORDER_TRIGGER_REJECT",
        "TRADE_LITE", "MARGIN_CALL", "STRATEGY_UPDATE", "GRID_UPDATE"
    )

/** New-risk admission is fail-closed until this credential's durable private-event inbox is empty. */
internal fun privateUserEventInboxAllowsExposure(snapshot: JSONObject?): Boolean =
    snapshot != null &&
        snapshot.optString("schema") == "PRIVATE_USER_EVENT_INBOX_V1" &&
        snapshot.optBoolean("account_fingerprint_present", false) &&
        snapshot.optLong("pending_count", -1L) == 0L &&
        snapshot.optLong("processed_count", -1L) >= 0L &&
        snapshot.optLong("other_account_pending_count", -1L) >= 0L

