package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FuturesExternalStopManagementTest {
    private fun owner(symbol: String)=JSONObject()
        .put("symbol",symbol).put("intent_role","ENTRY")
        .put("campaign_status","OPEN_PROTECTED")
        .put("strategy_profile",williamsTc2Profile)
        .put("direction","LONG").put("protection_status","PROTECTED")
        .put("confirmed_position_qty","1.5")
        .put("protective_stop","95.0")
        .put("protection_client_id","W2PS_${symbol}_owned")
        .put("strategy_ownership_verified",true)

    private fun observed(symbol:String)=JSONObject()
        .put("symbol",symbol).put("direction","LONG")
        .put("quantity","1.5").put("stop","95.0")
        .put("protection_client_id","W2PS_${symbol}_owned")
        .put("stop_proof","OWNED_STOP_SEEN")
        .put("strategy_ownership_verified",true)
        .put("automatic_trailing_authorized",true)

    @Test fun oneForeignSymbolDoesNotFreezeOtherExchangeProvenCampaignStops() {
        val foreign=JSONArray().put(JSONObject().put("symbol","BNBUSDT")
            .put("client_id","UNKNOWN_BNB").put("kind","EXTERNAL_ALGO_ORDER"))
        val proof=JSONArray().put(observed("XRPUSDT"))
            .put(observed("SOLUSDT")).put(observed("BNBUSDT"))
        val owners=JSONArray().put(owner("XRPUSDT"))
            .put(owner("SOLUSDT")).put(owner("BNBUSDT"))
        assertEquals(setOf("SOLUSDT","XRPUSDT"),
            verifiedExternalStopManagementScope(proof,foreign,owners))
        assertEquals(emptySet<String>(),
            verifiedExternalStopManagementScope(proof,JSONArray(),owners))
    }

    @Test fun unknownOrForeignStopAndMismatchedQuantityCannotAuthorizeTrailing() {
        val foreign=JSONArray().put(JSONObject().put("symbol","BNBUSDT"))
        val good=owner("XRPUSDT")
        val proof=observed("XRPUSDT")
        val candidate=JSONArray().put(proof)
        val owners=JSONArray().put(good)
        assertEquals(setOf("XRPUSDT"),verifiedExternalStopManagementScope(
            candidate,foreign,owners))
        proof.put("stop_proof","EXTERNAL_PROTECTED")
        assertTrue(verifiedExternalStopManagementScope(candidate,foreign,owners).isEmpty())
        proof.put("stop_proof","OWNED_STOP_SEEN")
        proof.put("quantity","2.0")
        assertTrue(verifiedExternalStopManagementScope(candidate,foreign,owners).isEmpty())
        proof.put("quantity","1.5")
        good.put("protection_client_id","W2PS_DIFFERENT")
        assertTrue(verifiedExternalStopManagementScope(candidate,foreign,owners).isEmpty())
        good.put("protection_client_id","W2PS_XRPUSDT_owned")
        good.put("previous_protection_client_id","STOP_NOT_YET_CANCELLED")
        assertTrue(verifiedExternalStopManagementScope(candidate,foreign,owners).isEmpty())
        good.remove("previous_protection_client_id")
        good.put("strategy_ownership_verified",false)
        assertTrue(verifiedExternalStopManagementScope(candidate,foreign,owners).isEmpty())
    }

    @Test fun quarantinedOtherSidePermitsOnlyFreshAuditedOwnedStopsOnUnrelatedPairs() {
        val now=1_000_000L
        val owned=owner("BTCUSDT").put("actual_risk_audit_status","PASS")
            .put("actual_risk_audited_at_ms",now).put("liquidation_guard_status","PASS")
        val proof=observed("BTCUSDT").put("observed_at_ms",now)
        val owners=JSONArray().put(owned)
        val proofs=JSONArray().put(proof)
        fun scope()=verifiedQuarantineStopManagementScope(proofs,JSONArray(),owners,setOf("ETHUSDT"),now)
        assertEquals(setOf("BTCUSDT"),scope())
        owned.put("actual_risk_audit_blocker","UNKNOWN_ACCOUNT")
        assertTrue(scope().isEmpty())
        owned.remove("actual_risk_audit_blocker")
        owned.put("actual_risk_audit_status","BLOCKED")
        assertTrue(scope().isEmpty())
        owned.put("actual_risk_audit_status","PASS")
        proof.put("observed_at_ms",now-30_001L)
        assertTrue(scope().isEmpty())
        proof.put("observed_at_ms",now)
        assertTrue(verifiedQuarantineStopManagementScope(proofs,JSONArray(),owners,setOf("BTCUSDT"),now).isEmpty())
        owners.put(org.json.JSONObject().put("symbol","BTCUSDT").put("intent_role","ADD_ON").put("order_status","NEW"))
        assertTrue(scope().isEmpty())
    }
}
