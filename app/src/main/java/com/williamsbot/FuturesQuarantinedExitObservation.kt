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
