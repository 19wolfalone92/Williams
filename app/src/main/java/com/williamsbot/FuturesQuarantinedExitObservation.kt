package com.williamsbot

import org.json.JSONObject

/** Read-only signed GET by the EXACT durable clientOrderId of a quarantined
 * reduce-only EXIT. Never infer flatness from an older exit while Binance
 * shows a current opposite-direction exposure. Never retry its POST.
 */
internal data class QuarantinedSignedExitObservation(
    val signedStatus:String,
    val executedQuantity:String,
    val exchangeOrderId:String,
    val stillActive:Boolean
)
internal fun verifiedQuarantinedSignedExit(
    intent:JSONObject,signedOrder:JSONObject?
):QuarantinedSignedExitObservation? {
    if(signedOrder==null) return null // A signed NOT_FOUND is not nonplacement proof.
    if(intent.optString("intent_role")!="EXIT" ||
        intent.optString("kind")!="MARKET_REDUCE_ONLY") return null
    val symbol=intent.optString("symbol")
    val clientId=intent.optString("client_id")
    val direction=intent.optString("direction")
    val requested=intent.optString("quantity").toBigDecimalOrNull()
        ?: error("QUARANTINED_EXIT_DURABLE_REQUESTED_QTY_UNKNOWN")
    val signedRequested=signedOrder.optString("origQty").toBigDecimalOrNull()
        ?: error("QUARANTINED_EXIT_SIGNED_ORIGINAL_QTY_UNKNOWN")
    val executed=signedOrder.optString("executedQty").toBigDecimalOrNull()
        ?: error("QUARANTINED_EXIT_SIGNED_EXECUTED_QTY_UNKNOWN")
    val orderId=signedOrder.optString("orderId")
    check(symbol in BinanceUsdMFuturesTestnetAdapter.APPROVED_SYMBOLS &&
        clientId.isNotBlank() && direction in setOf("LONG","SHORT") &&
        requested.signum()>0 &&
        signedOrder.optString("symbol")==symbol &&
        signedOrder.optString("clientOrderId")==clientId &&
        signedOrder.optString("positionSide")=="BOTH" &&
        signedOrder.optString("type")=="MARKET" &&
        signedOrder.optString("reduceOnly").equals("true",true) &&
        signedOrder.optString("side")==(if(direction=="LONG") "SELL" else "BUY") &&
        orderId.toLongOrNull()?.let { it>0L }==true &&
        signedRequested.compareTo(requested)==0 &&
        executed.signum()>=0 && executed<=requested
    ) { "QUARANTINED_EXIT_SIGNED_IDENTITY_OR_QUANTITY_MISMATCH" }
    val status=signedOrder.optString("status").uppercase(java.util.Locale.US)
    val active=status in setOf("NEW","PARTIALLY_FILLED","PENDING_CANCEL")
    check(active || status in setOf("FILLED","CANCELED","EXPIRED",
        "EXPIRED_IN_MATCH","REJECTED")) {
        "QUARANTINED_EXIT_UNKNOWN_SIGNED_EXCHANGE_STATUS"
    }
    return QuarantinedSignedExitObservation(status,executed.toPlainString(),orderId,active)
}


/**
 * DIAG16 — a missing exact clientOrderId GET is NOT a signed non-placement
 * receipt. With a live position OR an open order still present, keep the
 * ambiguous reduce-only EXIT quarantined while continuing read-only account
 * position/STOP reconciliation. No API mutation is authorized here.
 */
internal data class UnqueryableFuturesExitDisposition(
    val flatSnapshotOnly: Boolean,
    val blocksNewRisk: Boolean,
    val mayRetrySubmission: Boolean = false
)

internal fun classifyUnqueryableFuturesExit(
    livePositionExists: Boolean,
    matchingOpenStandard: Boolean,
    matchingOpenConditional: Boolean
): UnqueryableFuturesExitDisposition {
    val unresolved = livePositionExists || matchingOpenStandard || matchingOpenConditional
    return UnqueryableFuturesExitDisposition(
        flatSnapshotOnly = !unresolved,
        blocksNewRisk = unresolved,
        mayRetrySubmission = false
    )
}

/**
 * Binance -2022 is an explicit REJECTED reduce-only order response, not a
 * transport timeout. Scope this proof to the initial MARKET close POST:
 * after a POST receipt is returned, a later GET/cleanup failure is NOT proof
 * of non-submission. The exact failed client ID remains durably recorded.
 *
 * No local 4xx, timeout, -1021 clock error or failed signed GET may be
 * reclassified as a definite MARKET exit rejection.
 */
internal fun isDefinitiveFuturesReduceOnlyPostRejection(
    failure: Exception, closePostReturned: Boolean
): Boolean = !closePostReturned &&
    failure is FuturesHttpException &&
    failure.statusCode == 400 &&
    failure.exchangeCode == -2022 &&
    failure.requestPath == "/fapi/v1/order"

/** A durable negative POST receipt is NEVER a signed proof the position is flat. */
internal fun isDurableFuturesReduceOnlyRejection(intent: JSONObject): Boolean =
    intent.optString("intent_role") == "EXIT" &&
    intent.optString("kind") == "MARKET_REDUCE_ONLY" &&
    intent.optString("order_status") == "REJECTED" &&
    intent.optString("exchange_status") == "EXIT_TERMINAL_POSITION_OPEN" &&
    intent.optInt("rejection_http_status", 0) == 400 &&
    intent.optInt("exchange_rejection_code", 0) == -2022 &&
    intent.optBoolean("rejection_before_post_receipt", false) &&
    intent.optLong("rejected_at_ms", 0L) > 0L &&
    intent.optString("symbol").isNotBlank() &&
    intent.optString("client_id").isNotBlank()
