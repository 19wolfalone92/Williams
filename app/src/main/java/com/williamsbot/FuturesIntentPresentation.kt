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

/** A journalled NEW STOP entry is not proof of a CURRENT Binance order.
 * Display may label it confirmed only while the last exact-ID reconciliation
 * remains fresh. Stale/unknown receipts stay in the immutable audit history
 * and still block unsafe retry; this predicate never cancels an exchange order.
 */
internal fun futuresPendingEntryReceiptFresh(
    intent: JSONObject,
    nowMs: Long,
    ttlMs: Long = 30_000L
): Boolean {
    val status = intent.optString("order_status").uppercase(Locale.US)
    if (futuresIntentRole(intent) != "ENTRY" ||
        intent.optString("campaign_status") != "ENTRY_PENDING" ||
        status !in setOf("NEW", "WORKING", "ACCEPTED") ||
        nowMs <= 0L || ttlMs <= 0L) return false
    val verifiedAt = intent.optLong("last_reconciled_ms", 0L)
    return verifiedAt > 0L && nowMs >= verifiedAt && nowMs - verifiedAt <= ttlMs
}

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
