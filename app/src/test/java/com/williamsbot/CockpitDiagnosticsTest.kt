package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CockpitDiagnosticsTest {
    private val now = 1_800_000_000_000L
    private fun healthy() = JSONObject().put("running", true).put("rest_market_ready", true)
        .put("market_ws_connected", true).put("user_ws_connected", true)
        .put("user_stream_sync_required", false).put("history_ready", true)
        .put("positions_snapshot_ok", true).put("positions_snapshot_at_ms", now - 1000)
        .put("scanner_state", "READY").put("scanner_last_scan_at", now - 1000)
        .put("execution_enabled", true).put("paused", false).put("reconcile_required", false)
        .put("reserved_risk_pct", 0.002).put("max_total_risk_pct", 0.006)
        .put("daily_risk_baseline_source", "VERIFIED_TEST_BASELINE").put("daily_loss_pct", 0.0)
        .put("futures_positions", JSONArray())
    private fun coordinator() = JSONObject().put("ready_for_new_entry", true)
        .put("pending_intent_details", JSONArray())
    private fun entry(direction: String = "LONG") = JSONObject().put("symbol", "SOLUSDT")
        .put("direction", direction).put("role", "ENTRY").put("kind", "STOP")
        .put("entry_price", 100.0).put("persisted_protective_stop", if (direction == "LONG") 95.0 else 105.0)
        .put("order_status", "NEW").put("campaign_status", "ENTRY_PENDING")
        .put("protection_status", "NOT_SUBMITTED").put("actual_risk_audit_status", "UNKNOWN")
    private fun check(id: String, s: JSONObject, c: JSONObject = coordinator()) =
        CockpitDiagnostics.rows(CockpitDiagnostics.checks(s, c, JSONObject(), now)).single { it.optString("id") == id }
    private fun severity(id: String, s: JSONObject, c: JSONObject = coordinator()) = check(id, s, c).optString("severity")

    @Test fun unverifiedDailyRiskBaselineExplainsEntryBlockInCockpit() {
        val status = healthy().put("execution_enabled", false)
            .put("daily_risk_baseline_source", "UNKNOWN")
        val r = CockpitDiagnostics.readiness(status, coordinator(), now)
        assertFalse(r.getBoolean("new_entries_allowed"))
        assertTrue(r.getString("new_entries_reason").contains("база риска"))
    }
    @Test fun staleCachedPositionsNeverAllowNewEntriesDespiteGreenExecutionGate() {
        val s = healthy().put("positions_snapshot_ok", false).put("positions_snapshot_at_ms", now - 20_000)
        assertFalse(CockpitDiagnostics.readiness(s, coordinator(), now).getBoolean("new_entries_allowed"))
        assertEquals("WARNING", severity("DATA-04", s))
        assertEquals("WARNING", severity("AND-03", s))
    }
    @Test fun ageLimitIsEnforcedEvenIfProducerClaimsSnapshotOk() {
        assertFalse(CockpitDiagnostics.positionsFresh(healthy().put("positions_snapshot_at_ms", now - 15_001), now))
        assertTrue(CockpitDiagnostics.positionsFresh(healthy().put("positions_snapshot_at_ms", now - 15_000), now))
    }
    @Test fun futureAndMissingTimestampsAreNeverFresh() {
        assertFalse(CockpitDiagnostics.fresh(now + 1, now, 60_000))
        assertFalse(CockpitDiagnostics.fresh(0, now, 60_000))
    }
    @Test fun scannerBlockedAndCoordinatorBlockedAreDifferentFromTechnicalReadiness() {
        val s = healthy().put("scanner_state", "READY_BLOCKED")
        val c = coordinator().put("ready_for_new_entry", false)
            .put("blocker", "exchange_confirmed_pending_entry_or_fill_not_yet_resolved")
        val r = CockpitDiagnostics.readiness(s, c, now)
        assertTrue(r.getBoolean("technical_ready"))
        assertFalse(r.getBoolean("new_entries_allowed"))
        assertTrue(r.getString("new_entries_reason").contains("исполнение"))
    }
    @Test fun unfilledConditionalEntryIsValidWithoutPreSubmittedProtection() {
        val c = coordinator().put("pending_intent_details", JSONArray().put(entry()))
        assertEquals("PASS", severity("EXE-01", healthy(), c))
        assertEquals("NOT_VERIFIED", severity("EXE-03", healthy(), c))
    }
    @Test fun validShortConditionalEntryUsesStopAboveEntry() {
        val c = coordinator().put("pending_intent_details", JSONArray().put(entry("SHORT")))
        assertEquals("PASS", severity("EXE-01", healthy(), c))
    }
    @Test fun wrongSideShortAndLongStopsFail() {
        for (direction in listOf("LONG", "SHORT")) {
            val e = entry(direction).put("persisted_protective_stop", if (direction == "LONG") 105 else 95)
            assertEquals("FAIL", severity("EXE-01", healthy(), coordinator().put("pending_intent_details", JSONArray().put(e))))
        }
    }
    @Test fun partialFillRequiresProtectionBeforeFullFill() {
        val c = coordinator().put("pending_intent_details", JSONArray().put(entry().put("order_status", "PARTIALLY_FILLED")))
        assertEquals("FAIL", severity("EXE-02", healthy(), c))
        assertEquals("FAIL", severity("EXE-03", healthy(), c))
    }
    @Test fun filledEntryWithoutProtectionFailsEvenBeforePositionSnapshotConverges() {
        val c = coordinator().put("pending_intent_details", JSONArray().put(entry().put("order_status", "FILLED")))
        assertEquals("FAIL", severity("EXE-03", healthy(), c))
    }
    @Test fun stopPriceAloneNeverCountsAsExchangeProtection() {
        val s = healthy().put("futures_positions", JSONArray().put(JSONObject().put("symbol", "SOLUSDT").put("direction", "LONG").put("stop", 95)))
        assertEquals("FAIL", severity("EXE-04", s))
        assertEquals("FAIL", severity("RISK-01", s))
    }
    @Test fun protectionRequiresOwnershipDirectionAndFreshReconciliation() {
        val e = entry().put("campaign_status", "OPEN_PROTECTED").put("protection_status", "PROTECTED")
            .put("protection_client_id_suffix", "1234").put("strategy_ownership_verified", true)
            .put("last_reconciled_ms", now - 1000)
        assertTrue(CockpitDiagnostics.protectionProven(e, now))
        assertFalse(CockpitDiagnostics.protectionProven(e.put("strategy_ownership_verified", false), now))
        e.put("strategy_ownership_verified", true).put("last_reconciled_ms", now - 120_001)
        assertFalse(CockpitDiagnostics.protectionProven(e, now))
    }
    @Test fun oppositeSideProtectionDoesNotProtectPosition() {
        val e = entry("SHORT").put("campaign_status", "OPEN_PROTECTED").put("protection_status", "PROTECTED")
            .put("protection_client_id_suffix", "1234").put("strategy_ownership_verified", true).put("last_reconciled_ms", now)
        val s = healthy().put("futures_positions", JSONArray().put(JSONObject().put("symbol", "SOLUSDT").put("direction", "LONG")))
        assertEquals("FAIL", severity("EXE-04", s, coordinator().put("pending_intent_details", JSONArray().put(e))))
    }
    @Test fun actualRiskIsNotInferredFromReservation() {
        assertEquals("NOT_VERIFIED", severity("RISK-01", healthy()))
        assertEquals("PASS", severity("RISK-02", healthy()))
        assertEquals("FAIL", severity("RISK-02", healthy().put("reserved_risk_pct", 0.007)))
    }
    @Test fun missingRiskAndHistoryEvidenceIsNotPass() {
        assertEquals("NOT_VERIFIED", severity("DATA-01", healthy()))
        assertEquals("NOT_VERIFIED", severity("DATA-02", healthy()))
        assertEquals("NOT_VERIFIED", severity("RISK-03", healthy().put("daily_risk_baseline_source", "UNKNOWN")))
    }
    @Test fun currentSnapshotDoesNotProveRestartOrAlgorithmCorrectness() {
        assertEquals("NOT_VERIFIED", severity("AND-02", healthy()))
        for (n in 1..6) assertEquals("NOT_VERIFIED", severity("STR-0$n", healthy()))
    }
    @Test fun pauseKeepsMonitoringAndDisablesAdmission() {
        val r = CockpitDiagnostics.readiness(healthy().put("paused", true), coordinator(), now)
        assertTrue(r.getBoolean("position_management_ready"))
        assertFalse(r.getBoolean("new_entries_allowed"))
    }
    @Test fun overallCannotPassWithUnknownOrWarningEvidence() {
        fun tests(vararg levels: String) = JSONArray(levels.map { JSONObject().put("severity", it) })
        assertEquals("NOT_VERIFIED", CockpitDiagnostics.summary(JSONArray()).optString("overall"))
        assertEquals("NOT_VERIFIED", CockpitDiagnostics.summary(tests("PASS", "NOT_VERIFIED")).optString("overall"))
        assertEquals("WARNING", CockpitDiagnostics.summary(tests("PASS", "WARNING", "NOT_VERIFIED")).optString("overall"))
        assertEquals("FAIL", CockpitDiagnostics.summary(tests("PASS", "WARNING", "FAIL")).optString("overall"))
    }
    @Test fun sensitiveEventFieldsAreRedacted() {
        val sanitized = safeDiagnosticText("api_key=abc123&signature=xyz789 listenKey:PRIVATE secret:TOPSECRET token=hidden password=never")
        for (secret in listOf("abc123", "xyz789", "PRIVATE", "TOPSECRET", "hidden", "never")) assertFalse(sanitized.contains(secret))
        assertTrue(sanitized.contains("[redacted]"))
    }
    @Test fun fillRacingPendingCancellationPreventsRuntimeShutdown() {
        assertFalse(canShutdownFuturesRuntime(true, JSONArray().put(JSONObject().put("positionAmt", "0.5")), JSONArray()))
        assertFalse(canShutdownFuturesRuntime(true, JSONArray().put(JSONObject().put("positionAmt", "-0.5")), JSONArray()))
        assertTrue(canShutdownFuturesRuntime(true, JSONArray().put(JSONObject().put("positionAmt", "0")), JSONArray()))
    }
    @Test fun unknownCancellationMalformedPositionAndReservedIntentPreventShutdown() {
        assertFalse(canShutdownFuturesRuntime(false, JSONArray(), JSONArray()))
        assertFalse(canShutdownFuturesRuntime(true, JSONArray().put(JSONObject()), JSONArray()))
        assertFalse(canShutdownFuturesRuntime(true, JSONArray().put("invalid"), JSONArray()))
        assertFalse(canShutdownFuturesRuntime(true, JSONArray(), JSONArray().put(entry())))
        assertTrue(canShutdownFuturesRuntime(true, JSONArray(), JSONArray()))
        assertFalse(canShutdownFuturesRuntime(true, JSONArray(), JSONArray().put(JSONObject()
            .put("intent_role", "EXIT").put("exchange_status", "SUBMISSION_UNKNOWN"))))
    }
    @Test fun automaticPostChangeAuditDetectsUnprotectedPartialAndRiskBreach() {
        val partial = entry().put("order_status", "PARTIALLY_FILLED")
        assertEquals("FAIL", CockpitDiagnostics.lifecycleAudit(partial).optString("severity"))
        val protected = partial.put("protection_status", "PROTECTED").put("protection_client_id", "test-stop")
            .put("actual_risk_audit_status", "PASS")
        assertEquals("PASS", CockpitDiagnostics.lifecycleAudit(protected).optString("severity"))
        protected.put("actual_risk_audit_status", "BLOCKED")
        assertEquals("FAIL", CockpitDiagnostics.lifecycleAudit(protected).optString("severity"))
    }
}

