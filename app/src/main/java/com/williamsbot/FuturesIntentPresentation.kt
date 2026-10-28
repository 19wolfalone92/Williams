package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Presentation only. A terminal EXIT order does not prove its campaign flat. */
internal fun futuresIntentRole(intent: JSONObject): String =
    intent.optString("role").ifBlank { intent.optString("intent_role", "ENTRY") }
        .uppercase(Locale.US)

internal fun isTerminalFuturesExitIntent(intent: JSONObject): Boolean =
    futuresIntentRole(intent) == "EXIT" && intent.optString("order_status")
        .ifBlank { intent.optString("exchange_status") }.uppercase(Locale.US) in setOf(
            "EXIT_TERMINAL_POSITION_OPEN", "EXIT_CONFIRMED", "FILLED", "FINISHED",
            "CANCELED", "CANCELLED", "REJECTED", "EXPIRED", "EXPIRED_IN_MATCH"
        )

internal data class FuturesIntentPresentation(
    val active: JSONArray, val exitReconciliation: JSONArray
)

/** Keep every unresolved receipt, but never count a completed close order as
 * a live entry/position. This does not modify the execution ledger or gates. */
internal fun separateFuturesIntentPresentation(details: JSONArray): FuturesIntentPresentation {
    val active = JSONArray()
    val history = JSONArray()
    for (i in 0 until details.length()) {
        val row = details.getJSONObject(i)
        if (isTerminalFuturesExitIntent(row)) history.put(row) else active.put(row)
    }
    return FuturesIntentPresentation(active, history)
}

internal fun futuresExitPresentationText(intent: JSONObject): String = when {
    isTerminalFuturesExitIntent(intent) ->
        "Заявка на выход завершена. Это запись сверки закрытия, а не действующий ордер или новая позиция."
    else -> "Заявка на выход: подтверждение Binance уточняется. Это сокращение позиции, а не новый вход."
}
