package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

/**
 * P0: a lost STOP POST response is never permission to POST again.
 *
 * A new protection identity can be prepared only after Binance's SIGNED GET
 * positively identifies the former STOP as terminal WITHOUT a spawned child,
 * the current live exposure agrees across two signed full-account reads, and
 * both symbol/account order books are empty for that pair. The actual POST is
 * the coordinator's existing write-ahead-journaled and read-back-verified path.
 *
 * This grants neither strategy ownership nor entry admission. It may be used
 * only for a verified durable Williams ENTRY or a separately signed, previously\n * protected RECOVERED_EXCHANGE_POSITION. Recovery never authorizes ownership\n * of the Williams strategy, entries, trailing or market EXIT.
 */
internal fun futuresTerminalStopRearmBlocker(
    owner: JSONObject,
    oldStop: JSONObject?,
    beforePositions: JSONArray,
    afterPositions: JSONArray?,
    symbolAlgos: JSONArray,
    accountAlgos: JSONArray?,
    symbolOrders: JSONArray?,
    accountOrders: JSONArray?,
    symbol: String,
    direction: String,
    mark: Double,
    nowMs: Long
): String? {
    if (symbol !in APPROVED_FUTURES_SYMBOLS || direction !in setOf("LONG", "SHORT"))
        return "TERMINAL_REARM_SYMBOL_OR_DIRECTION_UNKNOWN"
    // A previously exchange-confirmed emergency STOP for a recovered, UNOWNED
    // position may be restored with a fresh STOP identity after definitive
    // signed terminal/no-child proof. This does NOT adopt the old Williams ENTRY
    // or authorize trailing, EXIT MARKET, add-ons, reversal or NEW exposure.
    val verifiedOwned = owner.optBoolean("strategy_ownership_verified", false)
    val initialAttemptAt = owner.optLong("protection_attempt_at_ms", 0L)
    val firstConfirmedAt = owner.optLong("first_signed_stop_observed_at_ms", 0L)
    val verifiedRecoveredStopOnly = !verifiedOwned &&
        initialAttemptAt > 0L && initialAttemptAt <= firstConfirmedAt &&
        firstConfirmedAt <= nowMs &&
        owner.optString("kind") == "RECOVERED_EXCHANGE_POSITION" &&
        owner.optString("recovery_origin") == "OPPOSITE_SIDE_POSITION_QUARANTINE" &&
        owner.optBoolean("protection_post_attempted", false) &&
        owner.optString("recovery_order_book_fingerprint") == "EMPTY"
    if (owner.optString("symbol") != symbol || owner.optString("direction") != direction ||
        owner.optString("intent_role", "ENTRY") != "ENTRY" ||
        !(verifiedOwned || verifiedRecoveredStopOnly) ||
        owner.optString("campaign_status") !in setOf("POSITION_OPEN", "OPEN_PROTECTED") ||
        owner.optString("protection_status") !in setOf("PROTECTED", "SUBMISSION_UNKNOWN") ||
        owner.optString("previous_protection_client_id").isNotBlank() ||
        owner.has("native_stop_replacement"))
        return "TERMINAL_REARM_CAMPAIGN_NOT_ELIGIBLE"

    val id = owner.optString("protection_client_id")
    val stop = owner.optString("protective_stop")
    if (!matchesTerminalUntriggeredFuturesProtectiveStop(oldStop, symbol, direction, id, stop))
        return "TERMINAL_REARM_EXACT_TERMINAL_STOP_NOT_PROVED"
    if (!mark.isFinite() || mark <= 0.0 ||
        !isProtectiveStopOnCorrectSide(direction, mark.toString(), stop))
        return "TERMINAL_REARM_STOP_ALREADY_CROSSED_OR_MARK_UNKNOWN"
    if (afterPositions == null || !futuresSignedExposureStable(beforePositions, afterPositions))
        return "TERMINAL_REARM_SIGNED_EXPOSURE_CHANGED"
    val matched = (0 until afterPositions.length()).mapNotNull { afterPositions.optJSONObject(it) }
        .filter { it.optString("symbol") == symbol &&
            it.optString("positionAmt").toBigDecimalOrNull()?.signum() != 0 }
    if (matched.size != 1) return "TERMINAL_REARM_SIGNED_POSITION_NOT_UNIQUE"
    val live = matched.single()
    val quantity = live.optString("positionAmt").toBigDecimalOrNull()
    val entry = live.optString("entryPrice").toBigDecimalOrNull()
    val storedQuantity = owner.optString("confirmed_position_qty").toBigDecimalOrNull()
    val storedEntry = owner.optString("actual_entry_price").toBigDecimalOrNull()
        ?: owner.optString("entry_price").toBigDecimalOrNull()
    if (live.optString("positionSide") != "BOTH" || quantity == null ||
        (quantity.signum() > 0) != (direction == "LONG") ||
        storedQuantity == null || quantity.abs().compareTo(storedQuantity) != 0 ||
        entry == null || entry.signum() <= 0 ||
        storedEntry == null || storedEntry.signum() <= 0 ||
        (entry - storedEntry).abs() >
            maxOf(BigDecimal("0.00000001"), storedEntry.abs() * BigDecimal("0.00000001")))
        return "TERMINAL_REARM_SIGNED_POSITION_IDENTITY_MISMATCH"

    if (accountAlgos == null || symbolOrders == null || accountOrders == null)
        return "TERMINAL_REARM_SIGNED_ORDER_BOOK_UNAVAILABLE"
    fun empty(rows: JSONArray, scoped: Boolean): Boolean {
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: return false
            val rowSymbol = row.optString("symbol")
            if (rowSymbol.isBlank()) return false
            if (scoped && rowSymbol != symbol) return false
            if (rowSymbol == symbol) return false
        }
        return true
    }
    if (!empty(symbolAlgos, true) || !empty(accountAlgos, false) ||
        !empty(symbolOrders, true) || !empty(accountOrders, false))
        return "TERMINAL_REARM_OPEN_OR_UNKNOWN_ORDER_CONFLICT"

    // Require a bounded quiet period after the last submitted attempt. Do not
    // congest Binance with a series of rejected stops. A transport-unknown
    // order is never eligible, regardless of elapsed time.
    val lastAttempt = owner.optLong("protection_attempt_at_ms", 0L)
    val previousRetries = owner.optInt("terminal_stop_retry_count", 0)
    if (nowMs <= 0L || previousRetries < 0 || previousRetries > 100_000)
        return "TERMINAL_REARM_BAD_DURABLE_CLOCK_OR_RETRY_STATE"
    val backoff = minOf(300_000L, 5_000L * (1L shl minOf(previousRetries, 6)))
    if (lastAttempt > 0L && (nowMs < lastAttempt || nowMs - lastAttempt < backoff))
        return "TERMINAL_REARM_COOLDOWN"
    return null
}

/** A new ID is needed after a terminal Algo: never POST with the old one. */
internal fun terminalStopRearmClientId(symbol: String, entryClientId: String, generation: Int): String {
    require(symbol in APPROVED_FUTURES_SYMBOLS && entryClientId.isNotBlank() && generation in 1..100_000)
    val hash = java.security.MessageDigest.getInstance("SHA-256")
        .digest("$symbol|$entryClientId|terminal-stop-rearm|$generation".toByteArray(Charsets.UTF_8))
        .take(9).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    return "W2R_${symbol.take(10)}_$hash"
}
