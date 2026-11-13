package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

/**
 * Read-only attribution of an ALREADY signed and confirmed flat USD-M campaign.
 *
 * A close in /fapi/v1/userTrades is proof of exchange fills, but NOT by itself
 * proof that the owned protective stop caused them. Attribute a STOP only
 * when private Binance ALGO_UPDATE exposes the exact owned clientAlgoId AND
 * the child matching-engine order id (ai) is the same as the orderId of ALL
 * closing signed userTrades. Never use proximity, symbol or price alone.
 * This object cannot submit/cancel/revise any Futures order.
 */
internal fun verifiedNativeStopExitAttribution(
    owner: JSONObject, signedUserTrades: JSONArray, privateEvents: JSONArray,
    openedAtMs: Long, closedAtMs: Long
): JSONObject {
    fun unknown(reason: String) = JSONObject()
        .put("schema", "WILLIAMS_SIGNED_EXIT_ATTRIBUTION_V1")
        .put("status", "NOT_VERIFIED")
        .put("reason", reason)
        .put("can_place_orders", false)
        .put("trading_authority", false)
    val symbol = owner.optString("symbol")
    val direction = owner.optString("direction")
    val stopId = owner.optString("protection_client_id")
    if (symbol.isBlank() || direction !in setOf("LONG", "SHORT") ||
        stopId.isBlank() || stopId == "null" ||
        owner.has("native_stop_replacement") ||
        owner.optString("previous_protection_client_id").isNotBlank()
    ) return unknown("STOP_OWNER_ID_OR_REPLACEMENT_NOT_UNAMBIGUOUS")
    if (openedAtMs <= 0L || closedAtMs < openedAtMs)
        return unknown("CAMPAIGN_WINDOW_UNVERIFIED")
    val closingIsBuyer = direction == "SHORT"
    val closing = (0 until signedUserTrades.length()).mapNotNull {
        signedUserTrades.optJSONObject(it)
    }.filter {
        it.optString("symbol") == symbol &&
            it.optString("buyer").equals(closingIsBuyer.toString(), true) &&
            it.optLong("time") in openedAtMs..closedAtMs
    }
    if (closing.isEmpty()) return unknown("SIGNED_CLOSING_USER_TRADES_MISSING")
    val orders = closing.map { it.optString("orderId").takeIf { id ->
        id.isNotBlank() && id != "0" && id != "-1"
    } ?: return unknown("SIGNED_CLOSING_ORDER_ID_MISSING") }.distinct()
    if (orders.size != 1) return unknown("MULTIPLE_SIGNED_EXIT_ORDERS_CANNOT_ATTRIBUTE_SINGLE_STOP")
    val exitOrderId = orders.single()
    val qty = closing.sumOf { it.optString("qty").toBigDecimalOrNull()
        ?: return unknown("SIGNED_CLOSING_QUANTITY_UNVERIFIED") }
    if (qty <= BigDecimal.ZERO) return unknown("SIGNED_CLOSING_QUANTITY_ZERO")
    val expectedSide = if (direction == "LONG") "SELL" else "BUY"
    val stopMatches = (0 until privateEvents.length()).mapNotNull {
        privateEvents.optJSONObject(it)
    }.filter { event ->
        if (event.optString("e") != "ALGO_UPDATE" ||
            event.optLong("E") !in openedAtMs..(closedAtMs + 30_000L)) return@filter false
        val algo = event.optJSONObject("o") ?: return@filter false
        algo.optString("s") == symbol &&
            algo.optString("caid") == stopId &&
            algo.optString("ai") == exitOrderId &&
            algo.optString("o") == "STOP_MARKET" &&
            algo.optString("S") == expectedSide &&
            algo.optString("X") in setOf("TRIGGERED", "FINISHED")
    }
    if (stopMatches.isEmpty()) return unknown("OWNED_STOP_CHILD_ORDER_ID_NOT_PROVEN_BY_PRIVATE_WS")
    val algoIds = stopMatches.map {
        it.getJSONObject("o").optString("aid")
    }.filter { it.isNotBlank() }.distinct()
    if (algoIds.size != 1) return unknown("ALGO_ORDER_ID_AMBIGUOUS")
    // All exits must belong to this one signed child order; partial executions
    // of the same order are fine, another contemporaneous exit is not.
    return JSONObject()
        .put("schema", "WILLIAMS_SIGNED_EXIT_ATTRIBUTION_V1")
        .put("status", "VERIFIED_OWNED_PROTECTIVE_STOP_FILLED")
        .put("reason", "VERIFIED_PROTECTIVE_STOP_FILLED")
        .put("source", "BINANCE_SIGNED_USER_TRADES_AND_PRIVATE_ALGO_UPDATE_EXACT_ORDER_ID")
        .put("symbol", symbol)
        .put("direction", direction)
        .put("client_algo_id", stopId)
        .put("algo_id", algoIds.single())
        .put("child_exchange_order_id", exitOrderId)
        .put("signed_closing_fill_count", closing.size)
        .put("signed_closing_quantity", qty.toPlainString())
        .put("observed_at_ms", System.currentTimeMillis())
        .put("can_place_orders", false)
        .put("trading_authority", false)
}
