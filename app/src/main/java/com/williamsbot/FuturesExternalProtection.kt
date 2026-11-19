package com.williamsbot

import org.json.JSONObject
import java.math.BigDecimal

/** Binance signed algo response must prove the price-protection limiter is OFF.
 * When true, the mark-price STOP can be deferred by excessive mark/contract
 * deviation and must not be counted as immediate exchange-side protection.
 * Missing / null is UNKNOWN and never a protective proof.
 */
internal fun isBinanceUnrestrictedStopTrigger(order: JSONObject): Boolean =
    order.opt("priceProtect") == false ||
        order.optString("priceProtect").equals("false", ignoreCase = true)

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
    // Omitting positionSide in a signed exchange record is UNKNOWN, not
    // evidence that this is the actual one-way BOTH position.
    if (order.optString("symbol") != symbol || order.optString("positionSide") != "BOTH") return false
    if (order.optString("orderType", order.optString("type")) != "STOP_MARKET") return false
    if (!isActiveFuturesProtectionStatus(order.optString("algoStatus").ifBlank { order.optString("status") })) return false
    if (order.optString("side") != if (direction == "LONG") "SELL" else "BUY") return false
    // All stops created by this bot are MARK_PRICE. A CONTRACT_PRICE
    // conditional may be risk-reducing, but it is NOT proof of the signed
    // MARK_PRICE protection contract needed for automatic management.
    if (order.optString("workingType") != "MARK_PRICE") return false
    if (!isBinanceUnrestrictedStopTrigger(order)) return false
    if (order.optString("algoId").toLongOrNull()?.let { it > 0L } != true || order.optString("clientAlgoId").isBlank()) return false
    val fullClose = order.optString("closePosition").equals("true", true)
    val reducingQuantity = order.optString("reduceOnly").equals("true", true) &&
        // Binance may reject an oversized fixed-quantity reduceOnly stop at
        // trigger time after a partial reduction. Only the exact signed
        // current position quantity can certify fixed-size protection.
        order.optString("quantity").toBigDecimalOrNull()?.let {
            it.signum() > 0 && it.compareTo(positionQuantity) == 0
        } == true
    if (!fullClose && !reducingQuantity) return false
    return isProtectiveStopOnCorrectSide(direction, referencePrice.toString(),
        order.optString("triggerPrice").ifBlank { order.optString("stopPrice") })
}
