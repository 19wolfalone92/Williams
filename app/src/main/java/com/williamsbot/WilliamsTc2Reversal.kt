package com.williamsbot

import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** TC2 PDF154-156/165: the opposite breakout starts a NEW campaign only
 * after the original reduce-only exit is FILLED, flat and cleaned up.
 * A reversal never inherits the old quantity, stop, risk budget or ownership. */
internal const val WILLIAMS_TC2_REVERSAL = "TC2_CONFIRMED_FLAT_REVERSAL_V1"
internal val tc2BookOppositeReasons=setOf("OPPOSITE_WILLIAMS_LEVEL_TRIGGERED",
    "OPPOSITE_WILLIAMS_TRIGGER_IN_HIGHER_CORRECTION")

internal fun williamsTc2ReversalClientId(ownerId:String,signalId:String):String = "W2V_"+
    MessageDigest.getInstance("SHA-256").digest("$ownerId|$signalId".toByteArray())
        .joinToString("") { "%02x".format(it) }.take(28)

/** Read-only durable handoff; partial fills and a close POST reply alone do not qualify. */
internal fun williamsTc2ReversalCandidate(ledger:JSONArray,ownerId:String,now:Long,
    ignoreClientId:String=""):JSONObject? {
    if(ownerId.isBlank()) return null
    fun rows()=(0 until ledger.length()).mapNotNull { ledger.optJSONObject(it) }
    val owner=rows().singleOrNull { it.optString("intent_role","ENTRY")=="ENTRY" && it.optString("client_id")==ownerId }
        ?: return null
    val symbol=owner.optString("symbol")
    if(!owner.optBoolean("strategy_ownership_verified") || owner.optString("strategy_profile")!=williamsTc2Profile ||
        campaignLifecycleStatus(owner)!="CLOSED_OR_STOPPED")
        return null
    val proof=owner.optJSONObject("edition_management")?.optJSONObject("exit_evidence") ?: return null
    val reason=proof.optString("reason")
    if(reason !in tc2BookOppositeReasons || owner.optString("close_reason").let { it.isNotBlank() && it!=reason }) return null
    val c=proof.optJSONObject("reverse_candidate") ?: return null
    val decision=proof.optJSONObject("opposition_check") ?: return null
    val opposite=decision.optJSONObject("opposition") ?: return null
    val flat=rows().singleOrNull { it.optString("intent_role")=="EXIT" && it.optString("entry_client_id")==ownerId &&
        it.optString("reason")==reason && !it.optBoolean("partial_reduction") &&
        it.optString("exchange_status")=="EXIT_CONFIRMED" && it.optString("order_status")=="FILLED" }
        ?: return null
    val flatAt=flat.optLong("confirmed_flat_at_ms")
    val signalId=c.optString("signal_id")
    val cid=williamsTc2ReversalClientId(ownerId,signalId)
    val side=c.optString("direction")
    val family=c.optString("signal_type").removePrefix("SHORT_").removePrefix("LONG_")
    val trigger=c.optDouble("trigger",Double.NaN)
    val stop=c.optDouble("protective_stop",Double.NaN)
    val signalAt=c.optLong("signal_bar_time_ms")
    val filled=flat.optString("executed_qty").toBigDecimalOrNull() ?: return null
    val requested=flat.optString("quantity").toBigDecimalOrNull() ?: return null
    val before=flat.optString("position_before_qty").toBigDecimalOrNull() ?: return null
    if(filled.signum()<=0 || filled.compareTo(requested)!=0 || requested.compareTo(before)!=0) return null
    if(flatAt<=0 || flatAt>now || now-flatAt>120_000L || signalId.isBlank() ||
        flat.optString("client_id").isBlank() || flat.optString("exchange_order_id").isBlank() ||
        c.optString("symbol")!=symbol || c.optString("strategy_profile")!=williamsTc2Profile ||
        side !in listOf("LONG","SHORT") || side==owner.optString("direction") ||
        campaignSignalDirection(c.optString("signal_type"))!=side || family !in listOf("REVERSAL","SUPER_AO","FRACTAL") ||
        !trigger.isFinite() || trigger<=0 || !stop.isFinite() || stop<=0 ||
        (if(side=="LONG") stop>=trigger else stop<=trigger) ||
        c.optString("execution_semantics")!="STOP_ENTRY" ||
        opposite.optString("signal_id")!=signalId || opposite.optString("direction")!=side ||
        opposite.optLong("signal_bar_time_ms")!=signalAt || opposite.optDouble("trigger",Double.NaN)!=trigger ||
        williamsTc2OppositeExitEvidenceBlocker(owner,decision.toString(),symbol,owner.optString("direction"),
            reason,owner.optLong("position_open_observed_at_ms"),now,trigger)!=null)
        return null
    val sameSymbol=rows().filter { it.optString("intent_role","ENTRY")=="ENTRY" &&
        it.optString("symbol")==symbol && it.optString("client_id")!=ignoreClientId }
    if(sameSymbol.lastOrNull()?.optString("client_id")!=ownerId ||
        rows().any { it.optString("reversal_owner_id")==ownerId && it.optString("client_id")!=ignoreClientId }) return null
    return JSONObject(c.toString()).put("policy",WILLIAMS_TC2_REVERSAL).put("owner_id",ownerId)
        .put("exit_client_id",flat.getString("client_id")).put("flat_confirmed_at_ms",flatAt).put("client_id",cid)
}

/** Live order-door check, independent of the scanner. Price must STILL be
 * across the same trade-price trigger, with a bounded executable quote. */
internal fun williamsTc2ReversalEntryBlocker(
    ledger:JSONArray,ownerId:String,symbol:String,side:String,signalId:String,signalType:String,
    signalAt:Long,clientId:String,entry:Double,stop:Double,now:Long,contract:Double,mark:Double,
    tick:Double,allowPersistedSelf:Boolean=false,maxDeviation:Double=.0015
):String? {
    val c=williamsTc2ReversalCandidate(ledger,ownerId,now,if(allowPersistedSelf) clientId else "")
        ?: return "TC2_REVERSAL_FLAT_OWNER_OR_EXIT_PROOF_UNVERIFIED"
    if(c.optString("symbol")!=symbol || c.optString("direction")!=side || c.optString("signal_id")!=signalId ||
        c.optString("signal_type")!=signalType || c.optLong("signal_bar_time_ms")!=signalAt || c.optString("client_id")!=clientId)
        return "TC2_REVERSAL_SIGNAL_OR_OWNER_MISMATCH"
    if(listOf(entry,stop,contract,mark,tick).any { !it.isFinite() || it<=0 } ||
        !maxDeviation.isFinite() || maxDeviation<=0) return "TC2_REVERSAL_PRICE_UNVERIFIED"
    val trigger=c.getDouble("trigger")
    if(kotlin.math.abs(stop-c.getDouble("protective_stop"))>tick*.11)
        return "TC2_REVERSAL_BOOK_STOP_CHANGED"
    if(if(side=="LONG") contract<trigger else contract>trigger) return "TC2_REVERSAL_TRIGGER_NO_LONGER_CROSSED"
    if(kotlin.math.abs(contract/trigger-1)>maxDeviation || kotlin.math.abs(entry/trigger-1)>maxDeviation)
        return "TC2_REVERSAL_TRIGGER_TOO_FAR_NO_CHASING"
    if(if(side=="LONG") entry<contract || stop>=minOf(mark,contract) else entry>contract || stop<=maxOf(mark,contract))
        return "TC2_REVERSAL_EXECUTION_BOUND_OR_PROTECTION_INVALID"
    return null
}
