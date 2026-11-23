package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject

/**
 * P0: independently signed, read-only authority for an ALREADY-APPROVED
 * STOP-only supervision while an unrelated historic opposite-side entry
 * remains quarantined. This is not strategy ENTRY ownership or NEW-risk
 * authorization. The stop replacement executor still requires its own signed
 * GETs, durable Execution Door and protective-first swap.
 *
 * Never use a client-id prefix as proof. Two signed order scopes must agree.
 */
internal fun signedQuarantinedStopOnlyManagementProven(
    symbol: String,
    direction: String,
    position: JSONObject,
    symbolAlgos: JSONArray,
    accountAlgos: JSONArray,
    accountOrders: JSONArray,
    supervisions: JSONArray,
    mark: Double,
    nowMs: Long
): Boolean {
    if (symbol !in BinanceUsdMFuturesTestnetAdapter.APPROVED_SYMBOLS ||
        direction !in setOf("LONG", "SHORT") || nowMs <= 0L ||
        !mark.isFinite() || mark <= 0.0 ||
        position.optString("symbol") != symbol ||
        position.optString("positionSide") != "BOTH") return false
    val signedQty=position.optString("positionAmt").toBigDecimalOrNull() ?: return false
    val entry=position.optString("entryPrice").toBigDecimalOrNull() ?: return false
    if (signedQty.signum() == 0 || entry.signum() <= 0 ||
        (signedQty.signum() > 0) != (direction == "LONG")) return false
    val supervisors=(0 until supervisions.length()).map {
        supervisions.optJSONObject(it) ?: return false
    }.filter { it.optString("symbol") == symbol && it.optString("state") == "ACTIVE" }
    if(supervisors.size != 1) return false
    val record=supervisors.single()
    if(record.optString("mode") !in setOf("OPERATOR_APPROVED_STOP_ONLY","AUTO_VERIFIED_STOP_ONLY") ||
        record.optString("direction") != direction ||
        record.optBoolean("strategy_ownership_verified",true) ||
        record.optBoolean("market_close_authorized",true) ||
        record.optBoolean("add_on_authorized",true) ||
        record.optBoolean("reverse_authorized",true) ||
        record.optString("protection_client_id").isBlank() ||
        record.optString("pending_protection_client_id").isNotBlank() ||
        record.optString("previous_protection_client_id").isNotBlank() ||
        record.optString("last_evaluation_status") !in setOf("WAITING_FOR_FIRST_CLOSED_H1",
            "WAITING_FOR_STRUCTURE","TRAILED","RECOVERED") ||
        !CockpitDiagnostics.fresh(record.optLong("last_evaluated_at_ms"),nowMs,120_000L)
    ) return false
    if(record.optString("mode")=="AUTO_VERIFIED_STOP_ONLY") {
        val first=record.optLong("first_signed_observed_at_ms")
        val second=record.optLong("second_signed_observed_at_ms")
        if(first<=0L || second-first !in 30_000L..300_000L ||
            record.optLong("approved_at_ms")<second ||
            record.optString("source")!="SIGNED_TWO_SNAPSHOTS_CLOSED_H1_H4_TC2_STOP_REVIEW"
        ) return false
    }
    val qty=record.optString("quantity").toBigDecimalOrNull() ?: return false
    val recordedEntry=record.optString("entry_price").toBigDecimalOrNull() ?: return false
    val stop=record.optString("stop").toBigDecimalOrNull() ?: return false
    if (qty.signum() <= 0 || qty.compareTo(signedQty.abs()) != 0 ||
        recordedEntry.compareTo(entry) != 0 || stop.signum() <= 0 ||
        !isProtectiveStopOnCorrectSide(direction,mark.toString(),stop.toPlainString())
    ) return false
    val standard=(0 until accountOrders.length()).map {
        accountOrders.optJSONObject(it) ?: return false
    }
    // A malformed account-wide row can belong to THIS position. Never
    // ignore an unidentified normal or conditional order by filterNotNull.
    if(standard.any { it.optString("symbol").isBlank() ||
        it.optString("symbol") == symbol }) return false
    val local=(0 until symbolAlgos.length()).map {
        symbolAlgos.optJSONObject(it) ?: return false
    }
    val accountRows=(0 until accountAlgos.length()).map {
        accountAlgos.optJSONObject(it) ?: return false
    }
    if(accountRows.any { it.optString("symbol").isBlank() ||
        it.optString("clientAlgoId").isBlank() }) return false
    val global=accountRows.filter { it.optString("symbol") == symbol }
    if(local.size!=1 || global.size!=1) return false
    val native=local.single();val account=global.single()
    val stopId=record.optString("protection_client_id")
    fun strict(o: JSONObject):Boolean =
        o.optString("clientAlgoId")==stopId &&
        o.optString("algoType")=="CONDITIONAL" &&
        isFullPositionFuturesStop(o,symbol,direction,signedQty.abs(),mark) &&
        o.optString("triggerPrice").ifBlank { o.optString("stopPrice") }
            .toBigDecimalOrNull()?.compareTo(stop)==0
    return strict(native) && strict(account) &&
        signedExternalContractStopFingerprint(native) ==
            signedExternalContractStopFingerprint(account)
}
