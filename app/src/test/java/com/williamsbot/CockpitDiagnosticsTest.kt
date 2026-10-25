package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CockpitDiagnosticsTest {
    @Test fun currentAndroidFlagsCannotCertifyUninterruptedBackgroundOperation() {
        val s=healthy().put("android_runtime",JSONObject().put("foreground_service_active",true)
            .put("battery_optimization_ignored",true).put("background_restricted",false)
            .put("cpu_wake_lock_held",true).put("service_guard_age_ms",1000))
        assertEquals("NOT_VERIFIED",severity("AND-01",s))
        s.getJSONObject("android_runtime").put("cpu_wake_lock_held",false)
        assertEquals("WARNING",severity("AND-01",s))
        s.getJSONObject("android_runtime").put("cpu_wake_lock_held",true).put("service_guard_age_ms",120_000)
        assertEquals("WARNING",severity("AND-01",s))
    }
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
    private fun coordinator() = JSONObject().put("engine", "USD_M_FUTURES_TESTNET")
        .put("ready_for_new_entry", true).put("pending_intent_details", JSONArray())
    private fun entry(direction: String = "LONG") = JSONObject().put("symbol", "SOLUSDT")
        .put("direction", direction).put("role", "ENTRY").put("kind", "STOP")
        .put("entry_price", 100.0).put("persisted_protective_stop", if (direction == "LONG") 95.0 else 105.0)
        .put("order_status", "NEW").put("campaign_status", "ENTRY_PENDING")
        .put("protection_status", "NOT_SUBMITTED").put("actual_risk_audit_status", "UNKNOWN")
    private fun check(id: String, s: JSONObject, c: JSONObject = coordinator()) =
        CockpitDiagnostics.rows(CockpitDiagnostics.checks(s, c, JSONObject(), now)).single { it.optString("id") == id }
    private fun severity(id: String, s: JSONObject, c: JSONObject = coordinator()) = check(id, s, c).optString("severity")

    @Test fun diagnostics30StopProofAgesWhileRestReconcilesWithoutInventingMissingStop() {
        val p=JSONObject().put("symbol","SOLUSDT").put("direction","LONG")
            .put("positionAmt",0.6).put("qty",0.6).put("stop",101.71)
            .put("stop_verification_status","OWNED_STOP_SEEN")
            .put("stop_confirmed_at_ms",now-32_000L)
        val proof=JSONObject().put("symbol","SOLUSDT").put("direction","LONG")
            .put("quantity","0.60").put("stop","101.71")
            .put("stop_proof","OWNED_STOP_SEEN")
            .put("protection_client_id","W2T_SOLUSDT_hc1got0t")
            .put("observed_at_ms",now-30_000L)
        val intent=entry().put("campaign_status","OPEN_PROTECTED")
            .put("protection_status","PROTECTED")
            .put("strategy_ownership_verified",true)
            .put("protection_client_id_suffix","hc1got0t")
            .put("last_reconciled_ms",now-30_000L)
            .put("actual_risk_audit_status","PASS")
            .put("actual_all_in_risk_pct",0.0004)
            .put("actual_risk_audited_at_ms",now-30_000L)
        val status=healthy().put("execution_enabled",false)
            .put("futures_positions",JSONArray().put(p))
        val exchange=coordinator().put("ready_for_new_entry",false)
            .put("blocker","exchange_reconciliation_in_progress")
            .put("reconciliation_running",true)
            .put("pending_intent_details",JSONArray().put(intent))
            .put("exchange_protection_observations",JSONArray().put(proof))
        val readiness=CockpitDiagnostics.readiness(status,exchange,now)
        assertEquals("NOT_VERIFIED",readiness.getString("protection_state"))
        assertTrue(readiness.getBoolean("stop_proof_refresh_in_progress"))
        assertEquals(1,readiness.getJSONArray("unprotected_positions").length())
        assertFalse(readiness.getBoolean("position_management_ready"))
        assertFalse(readiness.getBoolean("new_entries_allowed"))
        assertEquals("NOT_VERIFIED",severity("EXE-03",status,exchange))
        assertEquals("NOT_VERIFIED",severity("EXE-04",status,exchange))
        assertEquals("NOT_VERIFIED",severity("RISK-01",status,exchange))

        // An actually absent or mismatched exchange STOP is not excused by
        // reconciliation. This is the critical difference from a 30s-old
        // perfectly matching previously signed stop.
        proof.put("quantity","0.59")
        assertEquals("FAIL",severity("EXE-03",status,exchange))
        assertEquals("FAIL",severity("RISK-01",status,exchange))
        proof.put("quantity","0.60")
        intent.put("protection_status","REPLACEMENT_PENDING_PROTECTED")
        assertEquals("FAIL",severity("EXE-03",status,exchange))
        intent.put("protection_status","PROTECTED")
        exchange.put("reconciliation_running",false).put("blocker","")
        assertEquals("FAIL",severity("EXE-04",status,exchange))
        assertEquals("FAIL",CockpitDiagnostics.readiness(status,exchange,now)
            .getString("protection_state"))

        // Only a NEW exact Binance observation can change NOT_VERIFIED to
        // PASS. Never backdate or silently extend the strict 15s proof TTL.
        proof.put("observed_at_ms",now-2_000L)
        p.put("stop_confirmed_at_ms",now-2_000L)
        assertEquals("PASS",severity("EXE-03",status,exchange))
        assertEquals("PASS",severity("EXE-04",status,exchange))
        assertEquals("PASS",severity("RISK-01",status,exchange))
    }

    @Test fun diagnostics27OrphanLinkMustBeExplainedBeforeGenericReconciliationProgress() {
        val order=JSONObject().put("symbol","LINKUSDT")
            .put("client_id","W2H_LINKUSDT_061dd0339b76781d06e92b")
        val c=coordinator().put("reconciliation_running",true)
            .put("blocker","exchange_reconciliation_in_progress")
            .put("ready_for_new_entry",false)
            .put("external_open_orders",JSONArray().put(order))
        val reason=CockpitDiagnostics.readiness(healthy(),c,now).getString("new_entries_reason")
        assertTrue(reason.contains("LINKUSDT"))
        assertTrue(reason.contains("заявка"))
        assertTrue(reason.contains("заблокированы"))
        assertEquals("FAIL",severity("EXE-11",healthy(),c))
        c.put("external_open_orders",JSONArray())
        val waiting=CockpitDiagnostics.readiness(healthy(),c,now).getString("new_entries_reason")
        assertTrue(waiting.contains("сверка"))
    }
    @Test fun liquidationCheckNeedsFreshMeasuredEvidenceAndReportsAnUnsafeStop() {
        val s = healthy().put("futures_positions", JSONArray().put(JSONObject()
            .put("symbol", "SOLUSDT").put("direction", "LONG").put("qty", 1)))
        val e = entry().put("campaign_status", "OPEN_PROTECTED")
        val c = coordinator().put("pending_intent_details", JSONArray().put(e))
        assertEquals("NOT_VERIFIED", severity("RISK-04", s, c))
        e.put("liquidation_guard_status", "UNSAFE").put("liquidation_guard_audited_at_ms", now)
        assertEquals("FAIL", severity("RISK-04", s, c))
        e.put("liquidation_guard_status", "PASS")
        assertEquals("PASS", severity("RISK-04", s, c))
        e.put("liquidation_guard_audited_at_ms", now - 120_001)
        assertEquals("NOT_VERIFIED", severity("RISK-04", s, c))
    }

    @Test fun unverifiedDailyRiskBaselineExplainsEntryBlockInCockpit() {
        val status = healthy().put("execution_enabled", false)
            .put("daily_risk_baseline_source", "UNKNOWN")
        val r = CockpitDiagnostics.readiness(status, coordinator(), now)
        assertFalse(r.getBoolean("new_entries_allowed"))
        assertTrue(r.getString("new_entries_reason").contains("база риска"))
    }

    @Test fun externalStopProofIsSeparateFromStrategyOwnershipAndManagement() {
        val position=JSONObject().put("symbol","SOLUSDT").put("direction","SHORT")
            .put("qty",2).put("stop",105)
        val observation=JSONObject().put("symbol","SOLUSDT").put("direction","SHORT")
            .put("quantity","-2.0").put("stop","105.0").put("protection_client_id","external_stop")
            .put("stop_proof","EXTERNAL_PROTECTED").put("observed_at_ms",now)
        val s=healthy().put("futures_positions",JSONArray().put(position)).put("execution_enabled",false)
        val c=coordinator().put("ready_for_new_entry",false)
            .put("exchange_protection_observations",JSONArray().put(observation))
        val r=CockpitDiagnostics.readiness(s,c,now)
        assertEquals("PASS",r.getString("protection_state"))
        assertEquals(0,r.getJSONArray("unprotected_positions").length())
        assertEquals(1,r.getJSONArray("unmanaged_positions").length())
        assertFalse(r.getBoolean("position_management_ready"))
        assertFalse(r.getBoolean("new_entries_allowed"))
        assertEquals("PASS",severity("EXE-03",s,c))
        observation.put("automatic_trailing_authorized",true)
            .put("management_mode","OPERATOR_APPROVED_STOP_ONLY")
        val record=JSONObject().put("symbol","SOLUSDT").put("state","ACTIVE")
            .put("protection_client_id","external_stop").put("last_evaluated_at_ms",now)
            .put("last_evaluation_status","WAITING_FOR_STRUCTURE")
        c.put("operator_supervised_positions",JSONArray().put(record))
        assertTrue(CockpitDiagnostics.readiness(s,c,now).getBoolean("stop_only_management_ready"))
        assertFalse(CockpitDiagnostics.readiness(s,c,now).getBoolean("new_entries_allowed"))
        record.put("last_evaluation_status","BLOCKED")
        assertFalse(CockpitDiagnostics.readiness(s,c,now).getBoolean("stop_only_management_ready"))
        assertEquals("PASS",CockpitDiagnostics.readiness(s,c,now).getString("protection_state"))
    }

    @Test fun staleWrongQuantityOrAmbiguousExternalStopIsNotProtectionProof() {
        val s=healthy().put("futures_positions",JSONArray().put(JSONObject()
            .put("symbol","SOLUSDT").put("direction","LONG").put("qty",2).put("stop",95)))
        val observation=JSONObject().put("symbol","SOLUSDT").put("direction","LONG")
            .put("quantity","2").put("stop","95").put("protection_client_id","external_stop")
            .put("stop_proof","EXTERNAL_PROTECTED").put("observed_at_ms",now)
        val c=coordinator().put("exchange_protection_observations",JSONArray().put(observation))
        for(change in listOf<Pair<String,Any>>(
            "quantity" to "1", "direction" to "SHORT", "stop" to "94",
            "observed_at_ms" to now-15_001L, "stop_proof" to "MULTIPLE_PROTECTIVE_ORDERS_UNRESOLVED")) {
            val previous=observation.get(change.first)
            observation.put(change.first,change.second)
            assertEquals(change.toString(),"FAIL",CockpitDiagnostics.readiness(s,c,now).getString("protection_state"))
            observation.put(change.first,previous)
        }
        c.put("exchange_protection_observations",JSONArray().put(observation).put(JSONObject(observation.toString())))
        assertEquals("FAIL",CockpitDiagnostics.readiness(s,c,now).getString("protection_state"))
    }
    @Test fun threePendingStopOrdersUseEntireRiskBudgetEvenWhenDailyLossReset() {
        val orders = JSONArray()
        for (symbol in listOf("ETHUSDT","BNBUSDT","BTCUSDT")) {
            orders.put(entry().put("symbol", symbol).put("risk_pct", 0.002))
        }
        val healthyAccount = healthy()
            .put("reserved_risk_pct", 0.006)
            .put("max_total_risk_pct", 0.006)
            .put("daily_loss_pct", 0.0)
            .put("consecutive_losses", 0)
        val admission = CockpitDiagnostics.readiness(healthyAccount,
            coordinator().put("pending_intent_details", orders), now)
        assertTrue(admission.getBoolean("technical_ready"))
        assertTrue(admission.getBoolean("execution_door_ready"))
        assertFalse(admission.getBoolean("new_entries_allowed"))
        assertTrue(admission.getBoolean("entry_risk_budget_exhausted"))
        assertEquals(0.0, admission.getDouble("entry_risk_budget_remaining_pct"), 1e-9)
        assertEquals(3, admission.getInt("pending_orders"))
        assertTrue(admission.getString("new_entries_reason").contains("Ожидающие заявки"))
        assertTrue(admission.getString("new_entries_reason").contains("0.60%"))
        assertTrue(admission.getString("new_entries_reason").contains("Суточный лимит"))
        // Releasing exchange-confirmed reservations (not bypassing them) restores
        // the UI explanation, while the native entry door stays authoritative.
        healthyAccount.put("reserved_risk_pct", 0.004)
        val freed = CockpitDiagnostics.readiness(healthyAccount, coordinator(), now)
        assertTrue(freed.getBoolean("new_entries_allowed"))
        assertFalse(freed.getBoolean("entry_risk_budget_exhausted"))
        assertEquals(0.002, freed.getDouble("entry_risk_budget_remaining_pct"), 1e-9)
    }

    @Test fun riskBudgetUnknownIsNotMisreportedAsZeroOrUnlimitedCapacity() {
        // org.json rejects NaN; the incomplete API snapshot omits the limit.
        val s = healthy()
        s.remove("max_total_risk_pct")
        val readiness = CockpitDiagnostics.readiness(s, coordinator(), now)
        assertFalse(readiness.getBoolean("entry_risk_budget_known"))
        assertFalse(readiness.has("entry_risk_budget_remaining_pct") &&
            !readiness.isNull("entry_risk_budget_remaining_pct"))
    }

    @Test fun staleDisplayOnlyPositionsDoNotOverrideAuthoritativeFuturesEntryGate() {
        val s = healthy().put("positions_snapshot_ok", false).put("positions_snapshot_at_ms", now - 20_000)
        val readiness = CockpitDiagnostics.readiness(s, coordinator(), now)
        assertTrue(readiness.getBoolean("new_entries_allowed"))
        assertFalse(readiness.getBoolean("positions_snapshot_fresh"))
        assertFalse(readiness.getBoolean("position_management_ready"))
        assertEquals("NOT_VERIFIED", readiness.getString("protection_state"))
        assertEquals("WARNING", severity("DATA-04", s))
        assertEquals("WARNING", severity("AND-03", s))
        // Only the live native door can grant a new entry; an old UI snapshot never can.
        assertFalse(CockpitDiagnostics.readiness(s.put("execution_enabled", false), coordinator(), now)
            .getBoolean("new_entries_allowed"))
    }

    @Test fun ongoingBinanceReconciliationExplainsTemporaryEntryFenceInRussian() {
        val c = coordinator().put("ready_for_new_entry", false)
            .put("blocker", "exchange_reconciliation_in_progress")
        val r = CockpitDiagnostics.readiness(healthy().put("execution_enabled", false), c, now)
        assertFalse(r.getBoolean("new_entries_allowed"))
        assertTrue(r.getString("new_entries_reason").contains("сверка"))
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
    @Test fun diagnostics9InFlightSwapWithStaleRestIsUnverifiedNotFalselyUnprotected() {
        // Journal has a Binance-confirmed new STOP, but the UI still has the
        // pre-swap stop and reconciliation is running (BNB SHORT, 4.53.7).
        val stale = healthy()
            .put("positions_snapshot_ok", false)
            .put("positions_snapshot_at_ms", now - 25_000)
            .put("futures_positions", JSONArray().put(JSONObject()
                .put("symbol", "SOLUSDT").put("direction", "SHORT")
                .put("qty", 1).put("stop", 105)))
        val e = entry("SHORT").put("order_status", "TRIGGERING")
            .put("campaign_status", "POSITION_OPEN")
            .put("protection_status", "PROTECTED")
            .put("protection_client_id_suffix", "newstop")
            .put("strategy_ownership_verified", true)
            .put("last_reconciled_ms", now - 25_000)
            .put("trailing_at_ms", now - 2_000)
            .put("previous_protection_unresolved", false)
        val c = coordinator().put("ready_for_new_entry", false)
            .put("reconciliation_running", true)
            .put("pending_intent_details", JSONArray().put(e))
        assertEquals("NOT_VERIFIED", severity("EXE-03", stale, c))
        assertEquals("NOT_VERIFIED", severity("EXE-04", stale, c))
        assertEquals("NOT_VERIFIED", severity("EXE-05", stale, c))
        assertFalse(CockpitDiagnostics.readiness(stale, c, now).getBoolean("position_management_ready"))
        // A genuinely unconfirmed stop must never get the same lenient result.
        e.put("protection_status", "SUBMISSION_UNKNOWN")
        assertEquals("FAIL", severity("EXE-03", stale, c))
        assertEquals("FAIL", severity("EXE-05", stale, c))
    }

    @Test fun fractalDetectionUsesTwoClosedRightBarsAndNeverFutureInformation() {
        val confirmedAt = now - 3_600_000L
        fun scanner(fractalFormationAt: Long) = JSONObject().put("candidates", JSONArray().apply {
            for(symbol in APPROVED_FUTURES_SYMBOLS) {
                val signal = JSONObject().put("type", "LONG_FRACTAL")
                    .put("signal_bar_time_ms", confirmedAt)
                    .put("known_at_ms", now)
                    .put("formation_at_ms", fractalFormationAt)
                put(JSONObject().put("symbol", symbol).put("decision_trace",
                    JSONObject().put("signals", JSONArray().put(signal))))
            }
        })
        fun causal(s: JSONObject) = CockpitDiagnostics.rows(
            CockpitDiagnostics.checks(healthy(),coordinator(),s,now))
            .single { it.optString("id")=="STR-13" }.getString("severity")
        assertEquals("PASS",causal(scanner(confirmedAt - 2*3_600_000L)))
        assertEquals("FAIL",causal(scanner(confirmedAt - 3_600_000L)))
        assertEquals("FAIL",causal(scanner(confirmedAt + 3_600_000L)))
    }

    @Test fun journalProtectedWithoutCurrentSignedStopCannotPass() {
        val position = JSONObject().put("symbol","SOLUSDT").put("direction","LONG")
            .put("positionAmt","1").put("qty",1).put("stop",95)
            .put("mark_price",102).put("stop_verification_status","UNVERIFIED_OR_STALE")
        val e = entry().put("order_status","FILLED").put("campaign_status","OPEN_PROTECTED")
            .put("protection_status","PROTECTED").put("protection_client_id_suffix","old")
            .put("strategy_ownership_verified",true).put("last_reconciled_ms",now)
        val s=healthy().put("futures_positions",JSONArray().put(position))
        val c=coordinator().put("pending_intent_details",JSONArray().put(e))
        assertEquals("FAIL", severity("EXE-03",s,c))
        assertEquals("FAIL", severity("EXE-04",s,c))
        assertEquals("NOT_VERIFIED", severity("EXE-08",s,c))
        assertEquals("NOT_VERIFIED", severity("EXE-09",s,c))
        assertEquals("PASS", severity("DATA-05",s,c))
    }

    @Test fun signedStopProofMustMatchDashboardOwnershipTimestampAndMark() {
        val p=JSONObject().put("symbol","SOLUSDT").put("direction","SHORT")
            .put("positionAmt","-2").put("qty",2).put("stop",105)
            .put("mark_price",100).put("stop_verification_status","OWNED_STOP_SEEN")
            .put("stop_confirmed_at_ms",now)
        val proof=JSONObject().put("symbol","SOLUSDT").put("direction","SHORT")
            .put("quantity","-2").put("stop","105")
            .put("stop_proof","OWNED_STOP_SEEN").put("protection_client_id","exchange_new")
            .put("observed_at_ms",now)
        val s=healthy().put("futures_positions",JSONArray().put(p))
        val e=entry("SHORT").put("campaign_status","OPEN_PROTECTED")
            .put("strategy_ownership_verified",true)
            .put("protection_client_id_suffix","exchange_new")
        val c=coordinator().put("pending_intent_details",JSONArray().put(e))
            .put("exchange_protection_observations",JSONArray().put(proof))
        assertEquals("PASS",severity("EXE-08",s,c))
        assertEquals("PASS",severity("EXE-09",s,c))
        p.put("mark_price",106)
        assertEquals("FAIL",severity("EXE-09",s,c))
        // Same-age UI can still contain a genuinely inconsistent signed
        // acknowledgement; that remains FAIL, not a stale-screen excuse.
        s.put("positions_snapshot_at_ms",now)
        p.put("mark_price",100).put("stop_confirmed_at_ms",now-1)
        assertEquals("FAIL",severity("EXE-08",s,c))
        p.put("stop_confirmed_at_ms",now)
        e.put("protection_client_id_suffix","wrong")
        assertEquals("FAIL",severity("EXE-08",s,c))
        e.put("protection_client_id_suffix","exchange_new")
        c.put("exchange_protection_observations",JSONArray().put(proof).put(JSONObject(proof.toString())))
        assertEquals("FAIL",severity("EXE-08",s,c))
        assertEquals("FAIL",severity("EXE-03",s,c))
    }

    @Test fun diagnostics26NewerSignedStopIsStaleUiWarningNotFalseIdentityFailure() {
        // The 4.64 diagnostics captured an earlier UI STOP confirmation and
        // a later identical signed exchange STOP. A fresh but behind display
        // must NOT be classified as Binance order-identity corruption.
        val displayAt=now-11_000L
        val previousConfirmed=now-13_000L
        val proofAt=now-3_000L
        val p=JSONObject().put("symbol","ETHUSDT").put("direction","SHORT")
            .put("positionAmt","-0.395").put("qty",0.395).put("stop",2368.98)
            .put("mark_price",2338.67).put("stop_verification_status","EXTERNAL_PROTECTED")
            .put("stop_confirmed_at_ms",previousConfirmed)
        val signed=JSONObject().put("symbol","ETHUSDT").put("direction","SHORT")
            .put("quantity","-0.395").put("stop","2368.98")
            .put("stop_proof","EXTERNAL_PROTECTED")
            .put("protection_client_id","external_test_stop")
            .put("observed_at_ms",proofAt)
        val s=healthy().put("positions_snapshot_at_ms",displayAt)
            .put("futures_positions",JSONArray().put(p))
        val c=coordinator().put("exchange_protection_observations",JSONArray().put(signed))
        assertEquals("WARNING",severity("EXE-08",s,c))
        assertTrue(check("EXE-08",s,c).getString("detail").contains("ETHUSDT"))
        assertEquals("PASS",severity("EXE-03",s,c))
        // Once the UI consumes this signed observation the warning clears.
        s.put("positions_snapshot_at_ms",now-2_000L)
        p.put("stop_confirmed_at_ms",proofAt)
        assertEquals("PASS",severity("EXE-08",s,c))
        // Same-generation impossible status remains a real conflict.
        p.put("stop_verification_status","UNVERIFIED_OR_STALE")
        assertEquals("FAIL",severity("EXE-08",s,c))
        p.put("stop_verification_status","EXTERNAL_PROTECTED")
        // A different signed order price is NOT the same protection proof.
        signed.put("stop","2369.99")
        assertEquals("NOT_VERIFIED",severity("EXE-08",s,c))
        assertEquals("FAIL",severity("EXE-03",s,c))
    }
    @Test fun diagnostics26OrphanBinanceConditionalOrderCannotBeIgnoredAsZeroPendingIntents() {
        val s=healthy()
        val externalEntry=JSONObject().put("symbol","LINKUSDT")
            .put("client_id","W2H_LINKUSDT_orphan")
            .put("kind","EXTERNAL_ALGO_ORDER")
        val externalStop=JSONObject().put("symbol","ETHUSDT")
            .put("client_id","W2T_ETHUSDT_external_stop")
            .put("kind","EXTERNAL_ALGO_ORDER")
        val c=coordinator().put("external_open_orders",
            JSONArray().put(externalEntry).put(externalStop))
            .put("pending_intents",0).put("pending_intent_details",JSONArray())
        assertEquals("FAIL",severity("EXE-11",s,c))
        assertTrue(check("EXE-11",s,c).getString("detail").contains("LINKUSDT"))
        c.put("external_open_orders",JSONArray().put(externalStop))
        assertEquals("PASS",severity("EXE-11",s,c))
        c.remove("external_open_orders")
        assertEquals("NOT_VERIFIED",severity("EXE-11",s,c))
    }
    @Test fun malformedOrDuplicatedSignedPositionSnapshotIsDetected() {
        val p=JSONObject().put("symbol","SOLUSDT").put("direction","LONG")
            .put("positionAmt","-1").put("qty",1)
        val s=healthy().put("futures_positions",JSONArray().put(p))
        assertEquals("FAIL",severity("DATA-05",s))
        p.put("positionAmt","1")
        assertEquals("PASS",severity("DATA-05",s))
        s.put("futures_positions",JSONArray().put(p).put(JSONObject(p.toString())))
        assertEquals("FAIL",severity("DATA-05",s))
    }

    @Test fun onlyVerifiedSourcesMayCreatePendingExposure() {
        val foreign=entry().put("strategy_profile","NTD")
        val c=coordinator().put("pending_intent_details",JSONArray().put(foreign))
        assertEquals("FAIL",severity("STR-10",healthy(),c))
        foreign.put("strategy_profile",williamsTc2Profile)
        assertEquals("PASS",severity("STR-10",healthy(),c))
        foreign.put("strategy_profile",williamsNtdProfile)
        assertEquals("FAIL",severity("STR-10",healthy(),c))
        foreign.put("strategy_profile",williamsCourseProfile)
        assertEquals("FAIL",severity("STR-10",healthy(),c))
        foreign.put("campaign_status","OPEN_PROTECTED").put("strategy_profile","COURSE")
        assertEquals("PASS",severity("STR-10",healthy(),c))
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

    @Test fun confirmedExitIsNotAnUnprotectedEntry() {
        val confirmed = JSONObject()
            .put("intent_role", "EXIT")
            .put("kind", "MARKET_REDUCE_ONLY")
            .put("order_status", "FILLED")
            .put("exchange_status", "EXIT_CONFIRMED")
        assertEquals("PASS", CockpitDiagnostics.lifecycleAudit(confirmed).optString("severity"))
    }

    @Test fun terminalExitWithAnotherLivePositionIsNeverDeclaredFlat() {
        val stillOpen = JSONObject()
            .put("intent_role", "EXIT")
            .put("kind", "MARKET_REDUCE_ONLY")
            .put("order_status", "FILLED")
            .put("exchange_status", "EXIT_TERMINAL_POSITION_OPEN")
        assertEquals("WARNING", CockpitDiagnostics.lifecycleAudit(stillOpen).optString("severity"))
    }

    @Test fun ambiguousOrUnconfirmedExitCannotPassAudit() {
        for (status in listOf("SUBMISSION_UNKNOWN", "CANCEL_SUBMISSION_UNKNOWN")) {
            val exit = JSONObject().put("intent_role", "EXIT").put("exchange_status", status)
            assertEquals("WARNING", CockpitDiagnostics.lifecycleAudit(exit).optString("severity"))
        }
        val justFilled = JSONObject().put("intent_role", "EXIT")
            .put("exchange_status", "FILLED")
            .put("order_status", "FILLED")
        assertEquals("NOT_VERIFIED", CockpitDiagnostics.lifecycleAudit(justFilled).optString("severity"))
    }

    @Test fun exitRoleCannotMaskUnsafeFilledEntry() {
        val unsafeEntry = JSONObject().put("intent_role", "ENTRY")
            .put("campaign_status", "POSITION_OPEN").put("order_status", "FILLED")
        assertEquals("FAIL", CockpitDiagnostics.lifecycleAudit(unsafeEntry).optString("severity"))
        val malformedRole = JSONObject().put("intent_role", "UNKNOWN")
            .put("exchange_status", "EXIT_CONFIRMED")
        assertEquals("FAIL", CockpitDiagnostics.lifecycleAudit(malformedRole).optString("severity"))
    }

    @Test fun nativeSwapShowsSignedTemporaryProtectionWithoutEnablingManagementOrNewRisk() {
        val position = JSONObject().put("symbol", "SOLUSDT").put("direction", "SHORT").put("qty", 2).put("stop", 104)
        val observation = JSONObject().put("symbol", "SOLUSDT").put("direction", "SHORT")
            .put("quantity", "-2").put("stop", "104").put("protection_client_id", "W2B_TEMP")
            .put("stop_proof", "NATIVE_REPLACEMENT_PROTECTION_SEEN").put("observed_at_ms", now)
            .put("automatic_trailing_authorized", false).put("management_mode", "NATIVE_REPLACEMENT_PENDING")
        val state = healthy().put("futures_positions", JSONArray().put(position)).put("reconcile_required", true)
            .put("execution_enabled", false)
        val c = coordinator().put("ready_for_new_entry", false)
            .put("blocker", "SOLUSDT native stop replacement pending")
            .put("exchange_protection_observations", JSONArray().put(observation))
        val r = CockpitDiagnostics.readiness(state, c, now)
        assertEquals("PASS", r.getString("protection_state"))
        assertEquals(0, r.getJSONArray("unprotected_positions").length())
        assertFalse(r.getBoolean("new_entries_allowed"))
        assertFalse(r.getBoolean("position_management_ready"))
        observation.put("quantity", "-1")
        assertEquals("FAIL", CockpitDiagnostics.readiness(state, c, now).getString("protection_state"))
    }

}
