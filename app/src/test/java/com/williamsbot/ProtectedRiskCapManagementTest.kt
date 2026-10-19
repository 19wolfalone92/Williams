package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectedRiskCapManagementTest {
    private val overCap = "ACTUAL_FUTURES_RISK_EXCEEDS_PORTFOLIO_CAP: measured risk cannot be reserved without exceeding the 0.60% portfolio cap"
    private val now = 1792400109029L

    private fun position(symbol: String, qty: String) = JSONObject()
        .put("symbol", symbol).put("positionAmt", qty)

    private fun protectedEntry(symbol: String, direction: String) = JSONObject()
        .put("symbol", symbol).put("direction", direction)
        .put("intent_role", "ENTRY").put("campaign_status", "OPEN_PROTECTED")
        .put("protection_status", "PROTECTED")
        .put("protection_client_id", "stop-$symbol")
        .put("strategy_ownership_verified", true)
        .put("liquidation_guard_status", "PASS")
        .put("protective_stop", "2450.0")
        .put("actual_all_in_risk_pct", 0.00201)
        .put("actual_risk_audit_status", "BLOCKED")
        .put("actual_risk_audit_blocker", overCap)
        .put("actual_risk_audited_at_ms", now - 1000L)

    private fun positions() = JSONArray().put(position("ETHUSDT", "-0.499"))
        .put(position("SOLUSDT", "-11.49"))
        .put(position("XRPUSDT", "-801.1"))

    private fun intents() = JSONArray().put(protectedEntry("ETHUSDT", "SHORT"))
        .put(protectedEntry("SOLUSDT", "SHORT"))
        .put(protectedEntry("XRPUSDT", "SHORT"))

    @Test fun riskCapBlockDoesNotDisableVerifiedRiskReducingManagement() {
        assertTrue(verifiedProtectedRiskCapManagement(overCap, positions(), intents(), now))
    }

    @Test fun unknownRiskOrMissingStopIsNotAProtectionProof() {
        assertFalse(verifiedProtectedRiskCapManagement(overCap, positions(),
            intents().put(0, protectedEntry("ETHUSDT", "SHORT")
                .put("actual_risk_audit_status", "UNKNOWN_ACCOUNT")), now))
        assertFalse(verifiedProtectedRiskCapManagement(overCap, positions(),
            intents().put(0, protectedEntry("ETHUSDT", "SHORT")
                .put("protection_status", "SUBMISSION_UNKNOWN")), now))
        assertFalse(verifiedProtectedRiskCapManagement(overCap, positions(),
            intents().put(0, protectedEntry("ETHUSDT", "SHORT")
                .put("previous_protection_client_id", "unknown-algo")), now))
    }

    @Test fun noRiskManagementBypassForOtherBlockersOrStaleReconciliation() {
        assertFalse(verifiedProtectedRiskCapManagement("UNKNOWN_POSITIONS",positions(),intents(),now))
        assertFalse(verifiedProtectedRiskCapManagement(overCap,positions(),intents(),now + 31_000L))
        assertFalse(verifiedProtectedRiskCapManagement(overCap,positions(),
            intents().put(0, protectedEntry("ETHUSDT", "SHORT")
                .put("actual_risk_audit_blocker", "ADDITION_FILL_RISK: unknown")), now))
    }

    @Test fun pendingEntryExitOrUnownedPositionPreventsProtectedOnlyMode() {
        assertFalse(verifiedProtectedRiskCapManagement(overCap,positions(),
            intents().put(JSONObject().put("symbol", "BTCUSDT").put("intent_role", "ENTRY")
                .put("campaign_status", "ENTRY_PENDING")),now))
        assertFalse(verifiedProtectedRiskCapManagement(overCap,positions(),
            intents().put(JSONObject().put("symbol", "ETHUSDT").put("intent_role", "EXIT")
                .put("exchange_status", "NEW")),now))
        assertFalse(verifiedProtectedRiskCapManagement(overCap,
            positions().put(position("BTCUSDT", "1.0")),intents(),now))
        assertFalse(verifiedProtectedRiskCapManagement(overCap,positions(),
            intents().put(2, protectedEntry("XRPUSDT", "LONG")), now))
    }
}
