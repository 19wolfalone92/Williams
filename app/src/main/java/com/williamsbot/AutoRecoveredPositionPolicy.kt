package com.williamsbot

import org.json.JSONObject

/**
 * A signed Binance position and its same-direction stop may be proposed for
 * CLOSED H1/H4 stop-only analysis. They never authorize trade ownership.
 * Approval additionally requires two independent signed REST reads and a
 * fresh journal write in NativeFuturesCampaignCoordinator.
 */
internal fun autonomousRecoveredStopCandidateBlocker(
    position:JSONObject,proof:JSONObject,nowMs:Long
):String? {
    val symbol=position.optString("symbol")
    val signedQty=position.optString("positionAmt").toBigDecimalOrNull()
        ?: return "SIGNED_POSITION_QUANTITY_UNKNOWN"
    if(signedQty.signum()==0) return "SIGNED_POSITION_FLAT"
    val side=if(signedQty.signum()>0) "LONG" else "SHORT"
    if(position.optString("positionSide","BOTH")!="BOTH")
        return "SIGNED_HEDGE_MODE_NOT_SUPPORTED"
    if(proof.optString("symbol")!=symbol || proof.optString("direction")!=side)
        return "SIGNED_STOP_DIRECTION_MISMATCH"
    if(proof.optString("stop_proof")!="EXTERNAL_PROTECTED" ||
        proof.optBoolean("strategy_ownership_verified",true) ||
        proof.optString("management_mode")!="OBSERVE_ONLY")
        return "STOP_NOT_EXCHANGE_PROTECTED_OR_ALREADY_MANAGED"
    if(!CockpitDiagnostics.fresh(proof.optLong("observed_at_ms"),nowMs,15_000L))
        return "SIGNED_STOP_PROOF_STALE"
    if(proof.optString("quantity").toBigDecimalOrNull()?.abs()
        ?.compareTo(signedQty.abs())!=0)
        return "SIGNED_STOP_COVERAGE_MISMATCH"
    val stop=proof.optString("stop").toBigDecimalOrNull()
    val entry=position.optString("entryPrice").toBigDecimalOrNull()
    if(stop==null || stop.signum()<=0 || entry==null || entry.signum()<=0)
        return "SIGNED_ENTRY_OR_STOP_LEVEL_UNKNOWN"
    if(proof.optString("protection_client_id").isBlank())
        return "SIGNED_STOP_IDENTITY_UNKNOWN"
    return null
}

/** First and second independent signed observations MUST bind the same exact
 * account symbol, side, quantity, entry, STOP price and exchange stop IDs. */
internal fun autonomousStopSecondSignedProofEligible(
    candidate:JSONObject?, exactIdentity:String, nowMs:Long
):Boolean {
    if(candidate==null || exactIdentity.isBlank() ||
        candidate.optString("identity")!=exactIdentity) return false
    val first=candidate.optLong("first_seen_at_ms",0L)
    return first>0L && nowMs>=first && nowMs-first in 30_000L..300_000L
}
