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

/**
 * DIAG10: A human/other-client Binance CONTRACT_PRICE reduce-only STOP is
 * NOT the native MARK_PRICE+closePosition contract and grants NO strategy
 * ownership, trailing, cancellation, reversal or new-entry authorization.
 *
 * It can nevertheless be observed as independent exchange-side coverage if
 * EVERY field is signed, the remaining quantity exactly equals the current
 * one-way exposure and the trigger is on the correct side of the CONTRACT
 * price (not MARK). A missing priceProtect or non-reduce-only order is UNKNOWN.
 * Caller must additionally demand UNIQUE orders, matching account-wide and
 * symbol-scoped signed GETs, zero ordinary orders and stable signed exposure.
 */
private fun signedExternalContractStopStructure(
    order: JSONObject,
    symbol: String,
    direction: String,
    positionQuantity: BigDecimal,
    contractPrice: Double
): Boolean {
    if (symbol.isBlank() || direction !in setOf("LONG", "SHORT") ||
        positionQuantity.signum() <= 0 ||
        !contractPrice.isFinite() || contractPrice <= 0.0) return false
    if (order.optString("symbol") != symbol ||
        order.optString("algoType") != "CONDITIONAL" ||
        order.optString("orderType") != "STOP_MARKET" ||
        order.optString("positionSide") != "BOTH" ||
        order.optString("workingType") != "CONTRACT_PRICE" ||
        !isActiveFuturesProtectionStatus(order.optString("algoStatus")) ||
        order.optString("side") != (if (direction == "LONG") "SELL" else "BUY") ||
        !order.optString("closePosition").equals("false",ignoreCase=true) ||
        !order.optString("reduceOnly").equals("true",ignoreCase=true) ||
        order.optString("clientAlgoId").isBlank() ||
        order.optString("algoId").toLongOrNull()?.let { it > 0L } != true
    ) return false
    val quantity = order.optString("quantity").toBigDecimalOrNull()
    if (quantity == null || quantity.signum() <= 0 ||
        quantity.compareTo(positionQuantity) != 0) return false
    return isProtectiveStopOnCorrectSide(direction,contractPrice.toString(),
        order.optString("triggerPrice").ifBlank { order.optString("stopPrice") })
}

/** Only this strict method can certify unrestricted CONTRACT_PRICE protection. */
internal fun isSignedExternalContractPriceFullQuantityStop(
    order: JSONObject,
    symbol: String,
    direction: String,
    positionQuantity: BigDecimal,
    contractPrice: Double
): Boolean = isBinanceUnrestrictedStopTrigger(order) &&
    signedExternalContractStopStructure(order,symbol,direction,positionQuantity,contractPrice)

/**
 * DIAG11: priceProtect=true is a signed, conditional STOP but can delay
 * activation when mark/contract price divergence exceeds triggerProtect.
 * This method exists ONLY for observer diagnostics; never call it from
 * execution admission, H1 recovery or risk approval. No protective proof.
 */
internal fun isSignedPriceProtectedExternalContractStopForObservation(
    order: JSONObject,
    symbol: String,
    direction: String,
    positionQuantity: BigDecimal,
    contractPrice: Double
): Boolean = order.optString("priceProtect").equals("true",ignoreCase=true) &&
    signedExternalContractStopStructure(order,symbol,direction,positionQuantity,contractPrice)

/** Explicitly unverified, read-only description; not a protective STOP. */
internal fun signedPriceProtectedConditionalStopObservation(
    order: JSONObject,scope: String
): JSONObject = JSONObject()
    .put("order_type",order.optString("orderType"))
    .put("working_type",order.optString("workingType"))
    .put("exchange_algo_id",order.optString("algoId"))
    .put("client_algo_id",order.optString("clientAlgoId"))
    .put("trigger_price",order.optString("triggerPrice"))
    .put("quantity",order.optString("quantity"))
    .put("side",order.optString("side"))
    .put("price_protect",true)
    .put("reason","SIGNED_STOP_PRICE_PROTECT_TRUE_TRIGGER_CAN_BE_DELAYED")
    .put("read_only_not_execution_authority",true)
    .put("can_confirm_unrestricted_protection",false)
    .put("scope",scope)

/** Stable signed-order fingerprint for reconciling symbol and account reads.
 * Never accept missing execution-critical attributes as proof. */
internal fun signedExternalContractStopFingerprint(order: JSONObject): String =
    listOf("symbol","algoId","clientAlgoId","algoType","orderType","algoStatus",
        "side","positionSide","workingType","triggerPrice","quantity","closePosition",
        "reduceOnly","priceProtect")
        .joinToString("|") { field -> "$field=${order.optString(field)}" }
