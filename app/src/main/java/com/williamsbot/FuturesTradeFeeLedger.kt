package com.williamsbot

import org.json.JSONObject

/**
 * Financial audit of historical private Futures TRADE receipts.
 *
 * A successful signed REST position check does NOT certify commission history.
 * The legacy journal could store the same numeric Binance trade id twice if
 * its websocket transaction timestamp changed; reconstruct and deduplicate
 * by (orderId, tradeId), flag incompatible duplicate fee evidence, and never
 * report a missing fee as a verified zero-cost trade.
 *
 * This does not authorize new exposure or mutate protective STOP orders.
 */
/** Validate the actual raw Binance TRADE, not SQLite's historically
 * synthesized zeros (missing n was persisted as 0 in older versions).
 * The retained raw JSON is durable evidence without any table migration. */
internal fun futuresTradeFeeHasCompleteRawEvidence(
    rawJson:String?, symbol:String, orderId:String, tradeId:String
):Boolean {
    val raw=runCatching { JSONObject(rawJson.orEmpty()) }.getOrNull() ?: return false
    val order=normalizedExecutionPayload(raw) ?: return false
    val commission=order.optString("n").toDoubleOrNull()
    val quantity=order.optString("l").toDoubleOrNull()
    val price=order.optString("L").toDoubleOrNull()
    return order.optString("x")=="TRADE" &&
        order.optString("s")==symbol &&
        order.optString("i")==orderId &&
        order.optString("t")==tradeId &&
        order.optLong("T",0L)>0L &&
        commission!=null && commission.isFinite() && commission>=0.0 &&
        quantity!=null && quantity.isFinite() && quantity>0.0 &&
        price!=null && price.isFinite() && price>0.0 &&
        order.optString("N").isNotBlank()
}

internal class FuturesTradeFeeLedger(
    private val baseAsset:String,
    private val fallbackExitPrice:Double
) {
    private data class FeeRecord(val commission:Double,val asset:String,val price:Double)
    private val seen=mutableMapOf<String,FeeRecord>()
    private var trades=0
    private var total=0.0
    private var known=true

    fun add(
        orderId:String,tradeId:String,executionType:String,
        commission:Double,commissionAsset:String,fillPrice:Double,
        signedRawEvidenceValid:Boolean=true
    ) {
        if(executionType!="TRADE") return
        trades++
        if(!signedRawEvidenceValid) known=false
        val stable=if(privateBinanceTradeHasStableId(executionType,tradeId) &&
            orderId.isNotBlank()) "$orderId:$tradeId" else null
        val proof=FeeRecord(commission,commissionAsset,fillPrice)
        if(stable!=null) {
            val previous=seen.putIfAbsent(stable,proof)
            if(previous!=null) {
                // A duplicate with inconsistent fee facts cannot certify
                // amount or currency even if we count the fill only once.
                if(previous!=proof) known=false
                return
            }
        } else {
            // Unknown order/trade identity cannot be deduplicated reliably.
            // Sum the observable receipt but never claim the total is exact.
            known=false
        }
        if(!commission.isFinite() || commission<0.0) { known=false;return }
        if(commission==0.0) {
            // A zero amount with absent currency could be a stored default,
            // not the signed exchange field. Never certify zero costs.
            if(commissionAsset.isBlank()) known=false
            return
        }
        val usdt = when(commissionAsset) {
            "USDT" -> commission
            baseAsset -> {
                if(baseAsset.isBlank()) { known=false;return }
                val price=if(fillPrice.isFinite() && fillPrice>0.0) fillPrice
                    else {
                        known=false
                        fallbackExitPrice.takeIf { it.isFinite() && it>0.0 }
                            ?: return
                    }
                commission*price
            }
            else -> { known=false;return } // includes missing asset
        }
        if(!usdt.isFinite() || usdt<0.0 || !total.plus(usdt).isFinite()) {
            known=false
            return
        }
        total+=usdt
    }

    fun result():Pair<Double,Boolean> = total to (known && trades>0)
}
