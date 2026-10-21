package com.williamsbot

import org.json.JSONObject
import java.math.BigDecimal

/** Exchange proof of full-position protection; this never proves strategy ownership. */
internal fun isFullPositionFuturesStop(
    order: JSONObject,
    symbol: String,
    direction: String,
    positionQuantity: BigDecimal,
    referencePrice: Double
): Boolean {
    if (direction !in setOf("LONG", "SHORT") || positionQuantity.signum() <= 0 ||
        !referencePrice.isFinite() || referencePrice <= 0.0) return false
    if (order.optString("symbol") != symbol || order.optString("positionSide", "BOTH") != "BOTH") return false
    if (order.optString("orderType", order.optString("type")) != "STOP_MARKET") return false
    if (!isActiveFuturesProtectionStatus(order.optString("algoStatus").ifBlank { order.optString("status") })) return false
    if (order.optString("side") != if (direction == "LONG") "SELL" else "BUY") return false
    if (order.optString("workingType") !in setOf("MARK_PRICE", "CONTRACT_PRICE")) return false
    if (order.optString("algoId").toLongOrNull()?.let { it > 0L } != true || order.optString("clientAlgoId").isBlank()) return false
    val fullClose = order.optString("closePosition").equals("true", true)
    val reducingQuantity = order.optString("reduceOnly").equals("true", true) &&
        order.optString("quantity").toBigDecimalOrNull()?.let { it >= positionQuantity } == true
    if (!fullClose && !reducingQuantity) return false
    return isProtectiveStopOnCorrectSide(direction, referencePrice.toString(),
        order.optString("triggerPrice").ifBlank { order.optString("stopPrice") })
}
