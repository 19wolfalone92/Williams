package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WilliamsUnifiedEvidenceTest {
    private val now = 1_800_000_000_000L

    private fun signal(id: String, profile: String, family: String, role: String = "ENTRY") =
        JSONObject().put("signal_id", id).put("strategy_profile", profile)
            .put("type", family).put("role", role)
            .put("signal_bar_time_ms", now - 2 * WILLIAMS_H1_MS)
            .put("trigger_price", 100.0)
            .put("protective_price", if (family.startsWith("SHORT_")) 101.0 else 99.0)
            .put("reason", "CLOSED_H1_TEST")

    private fun scanner(complete: Boolean = true, signals: JSONArray = JSONArray()): JSONObject {
        val candidates = JSONArray()
        APPROVED_FUTURES_SYMBOLS.forEachIndexed { i, symbol ->
            val pairSignals=if(i==0) signals else JSONArray()
            val independentProof=JSONArray()
            for(source in williamsSourceProfiles) {
                val observed=CockpitDiagnostics.rows(pairSignals).count { it.optString("strategy_profile")==source }
                independentProof.put(JSONObject().put("profile",source)
                    .put("execution_role",if(williamsVerifiedNewRiskSource(source)) "LIVE" else "OBSERVE")
                    .put("state",if(observed>0) "FORMATION_OBSERVED" else "NO_VALID_FORMATION")
                    .put("as_of_close_ms",now).put("signal_count",observed))
            }
            candidates.put(JSONObject().put("symbol",symbol)
                .put("campaign_signals",pairSignals).put("closed_h1_as_of_ms",now)
                .put("edition_observations",independentProof))
        }
        return JSONObject().put("scanner_state", "READY")
            .put("scanning", !complete).put("last_scan_at", now - 3_000L)
            .put("symbols_scanned", 10).put("run_id", "futures-test")
            .put("candidates", candidates)
    }

    private fun status() = JSONObject().put("strategy_profile", williamsExecutionProfile)

    @Test fun reportsThreeSourcesWithSeparateEntryAddAndDirections() {
        val signals = JSONArray()
            .put(signal("t1", williamsTc2Profile, "REVERSAL"))
            .put(signal("n1", williamsNtdProfile, "AO_ZERO_LINE", "ADD_ON"))
            .put(signal("c1", williamsCourseProfile, "SHORT_COURSE_HUMP"))
        val result = buildWilliamsUnifiedEvidence(scanner(signals = signals), status(), now)
        assertEquals("COMPLETE_OBSERVATION_ONLY", result.getString("status"))
        assertEquals(10, result.getInt("observed_symbols"))
        assertEquals(3, result.getInt("observed_signal_count"))
        for (profile in williamsSourceProfiles) {
            assertEquals(1, result.getJSONObject("by_source").getJSONObject(profile).getInt("total"))
        }
        assertEquals(1, result.getInt("pre_context_opposing_symbol_count"))
        assertEquals(0,result.getInt("live_tc2_opposing_symbol_count"))
        assertEquals(williamsExecutionProfile,result.getJSONObject("execution_policy").getString("execution_profile"))
        val pair = result.getJSONArray("by_symbol").getJSONObject(0)
        assertTrue(pair.getBoolean("fresh_opposing_entries_before_context"))
        for (item in CockpitDiagnostics.rows(pair.getJSONArray("signals"))) {
            assertFalse(item.getBoolean("exchange_order_authorized"))
            assertEquals("NOT_EVALUATED_BY_CACHED_SCANNER", item.getString("admission_proof"))
        }
    }

    @Test fun completedButRiskBlockedScanIsValidBookEvidenceNotExecutionApproval() {
        val snapshot=scanner().put("scanner_state","READY_BLOCKED")
        val result=buildWilliamsUnifiedEvidence(snapshot,status(),now)
        assertEquals("COMPLETE_OBSERVATION_ONLY",result.getString("status"))
        assertFalse(result.getBoolean("is_execution_decision"))
        assertEquals(10,result.getInt("observed_symbols"))
    }

    @Test fun partialScanDoesNotPretendEvidenceIsFinal() {
        val result = buildWilliamsUnifiedEvidence(scanner(false), status(), now)
        assertEquals("SCAN_IN_PROGRESS", result.getString("status"))
        assertEquals(10, result.getInt("observed_symbols"))
        assertEquals(0, result.getInt("observed_signal_count"))
    }

    @Test fun unknownSourceAndInvalidProtectiveStopFailSourceContract() {
        val signals = JSONArray().put(signal("forged", "UNKNOWN_PROFILE", "REVERSAL"))
            .put(signal("inverted", williamsTc2Profile, "SHORT_REVERSAL")
                .put("protective_price", 90))
        val result = buildWilliamsUnifiedEvidence(scanner(signals = signals), status(), now)
        assertEquals("INVALID_SNAPSHOT", result.getString("status"))
        assertEquals(2, result.getJSONArray("source_contract_errors").length())
    }

    @Test fun signalsAbsentIsNotProofOfBookComplianceOrEntryPermission() {
        val result = buildWilliamsUnifiedEvidence(scanner(), status(), now)
        assertEquals("COMPLETE_OBSERVATION_ONLY", result.getString("status"))
        assertEquals(0, result.getInt("observed_signal_count"))
        assertFalse(result.getBoolean("is_execution_decision"))
        assertEquals("NO_SIGNALS_IN_SNAPSHOT; detector rejection details not persisted",
            result.getJSONArray("by_symbol").getJSONObject(0).getString("no_signal_explanation"))
        assertEquals("UNKNOWN",result.getJSONArray("by_symbol").getJSONObject(0).getString("owner_strategy_profile"))
        assertEquals("UNAVAILABLE",result.getJSONArray("by_symbol").getJSONObject(0).getString("owner_snapshot_state"))
    }

    @Test fun staleAndLegacyProfileAreExplicitlyMarked() {
        val late = buildWilliamsUnifiedEvidence(scanner().put("last_scan_at", now - 130_000L), status(), now)
        assertEquals("STALE_OR_MISSING_SCAN", late.getString("status"))
        val legacy = buildWilliamsUnifiedEvidence(scanner(),
            JSONObject().put("strategy_profile", williamsCourseProfile), now)
        assertEquals("WRONG_PROFILE", legacy.getString("status"))
    }
    @Test fun independentTc2NtdExecutionStillDoesNotGrantOrdersToCachedObservations() {
        val signals=JSONArray()
            .put(signal("t1",williamsTc2Profile,"REVERSAL"))
            .put(signal("n1",williamsNtdProfile,"SHORT_FRACTAL"))
            .put(signal("c1",williamsCourseProfile,"COURSE_THUMB"))
        val evidence=buildWilliamsUnifiedEvidence(scanner(signals=signals),status(),now)
        assertEquals("COMPLETE_OBSERVATION_ONLY",evidence.getString("status"))
        val pair=evidence.getJSONArray("by_symbol").getJSONObject(0)
        val signalsOut=CockpitDiagnostics.rows(pair.getJSONArray("signals"))
        assertEquals(listOf("LIVE","LIVE","OBSERVE"),signalsOut.map { it.getString("execution_role") })
        assertTrue(pair.getBoolean("fresh_opposing_entries_before_context"))
        assertFalse(pair.getBoolean("live_tc2_opposing_entries_before_context"))
        assertTrue(pair.getBoolean("live_source_opposing_entries_before_context"))
    }

    @Test fun pinnedSourceOwnerDoesNotChangeWhenAnotherBookFindsASignal() {
        val observed=JSONArray()
            .put(signal("n1",williamsNtdProfile,"SHORT_FRACTAL"))
            .put(signal("t2",williamsTc2Profile,"REVERSAL"))
        val active=JSONArray().put(JSONObject()
            .put("symbol","BTCUSDT").put("role","ENTRY")
            .put("direction","SHORT").put("strategy_profile",williamsTc2Profile)
            .put("signal_id","old_tc2").put("campaign_status","OPEN_PROTECTED")
            .put("strategy_ownership_verified",true))
        val status=status().put("futures_runtime",JSONObject().put("pending_intent_details",active))
        val report=buildWilliamsUnifiedEvidence(scanner(signals=observed),status,now)
        val pair=report.getJSONArray("by_symbol").getJSONObject(0)
        assertEquals(williamsTc2Profile,pair.getString("owner_strategy_profile"))
        assertEquals("old_tc2",pair.getString("owner_signal_id"))
        assertEquals("SHORT",pair.getString("owner_direction"))
        assertTrue(pair.getBoolean("campaign_occupied"))
        assertTrue(pair.getBoolean("owner_strategy_binding_verified"))
        assertEquals("PINNED_SOURCE_OBSERVED_NOT_LIVE_EXCHANGE_PROOF",pair.getString("owner_snapshot_state"))
        assertFalse(pair.getBoolean("other_strategies_can_trade_this_pair"))
        assertEquals(1,report.getInt("active_campaigns_in_snapshot"))
    }

    @Test fun multipleActiveOwnersFailDiagnosticsRatherThanPickingAnArbitraryOwner() {
        val a=JSONArray().put(JSONObject().put("symbol","BTCUSDT")
            .put("role","ENTRY").put("strategy_profile",williamsTc2Profile)
            .put("campaign_status","ENTRY_PENDING"))
            .put(JSONObject().put("symbol","BTCUSDT").put("role","ENTRY")
                .put("strategy_profile",williamsNtdProfile)
                .put("campaign_status","OPEN_PROTECTED"))
        val report=buildWilliamsUnifiedEvidence(scanner(),status().put("futures_runtime",
            JSONObject().put("pending_intent_details",a)),now)
        assertEquals("INVALID_SNAPSHOT",report.getString("status"))
        val errors=report.getJSONArray("source_contract_errors")
        assertTrue((0 until errors.length()).any { errors.optString(it)=="MULTIPLE_ACTIVE_CAMPAIGN_OWNERS:BTCUSDT" })
        assertEquals(2,report.getJSONArray("by_symbol").getJSONObject(0).getInt("active_owner_count"))
    }

    @Test fun tc2FractalIsNotArbitrarilyLimitedToFiveHours() {
        val old = now - 9L * WILLIAMS_H1_MS
        val formation=signal("wm3",williamsTc2Profile,"FRACTAL")
            .put("signal_bar_time_ms",old)
        val report=buildWilliamsUnifiedEvidence(scanner(signals=JSONArray().put(formation)),status(),now)
        val found=report.getJSONArray("by_symbol").getJSONObject(0)
            .getJSONArray("signals").getJSONObject(0)
        assertEquals("UNBROKEN_UNSUPERSEDED_FRACTAL",found.getString("validity_policy"))
        assertTrue(found.getBoolean("signal_time_eligible"))
        val reverse=signal("wm1",williamsTc2Profile,"REVERSAL")
            .put("signal_bar_time_ms",old)
        val stale=buildWilliamsUnifiedEvidence(scanner(signals=JSONArray().put(reverse)),status(),now)
        assertFalse(stale.getJSONArray("by_symbol").getJSONObject(0)
            .getJSONArray("signals").getJSONObject(0).getBoolean("signal_time_eligible"))
    }

    @Test fun failedObserverMustNotBeReportedAsNoTradingSignal() {
        val scan=scanner()
        val candidate=scan.getJSONArray("candidates").getJSONObject(0)
        candidate.getJSONArray("edition_observations").getJSONObject(1)
            .put("state","ANALYSIS_FAILED").put("error_type","IllegalStateException")
        val report=buildWilliamsUnifiedEvidence(scan,status(),now)
        assertEquals("INVALID_SNAPSHOT",report.getString("status"))
        assertFalse(report.getBoolean("observer_proof_complete"))
        val state=report.getJSONArray("by_symbol").getJSONObject(0).getJSONObject("edition_observation_proof")
            .getJSONObject(williamsNtdProfile)
        assertEquals("ANALYSIS_FAILED",state.getString("state"))
        assertEquals("IllegalStateException",state.getString("error_type"))
    }

    @Test fun staleAndMissingIndependentH1ProofCannotPretendAllThreeSourcesRan() {
        val scan=scanner()
        val first=scan.getJSONArray("candidates").getJSONObject(0)
        first.remove("edition_observations")
        val missing=buildWilliamsUnifiedEvidence(scan,status(),now)
        assertEquals("INCOMPLETE_OBSERVER_PROOF",missing.getString("status"))
        assertFalse(missing.getBoolean("observer_proof_complete"))
        val scan2=scanner()
        scan2.getJSONArray("candidates").getJSONObject(0)
            .getJSONArray("edition_observations").getJSONObject(1)
            .put("as_of_close_ms",now-WILLIAMS_H1_MS)
        val stale=buildWilliamsUnifiedEvidence(scan2,status(),now)
        assertEquals("INCOMPLETE_OBSERVER_PROOF",stale.getString("status"))
        assertEquals("H1_SNAPSHOT_MISMATCH",stale.getJSONArray("by_symbol").getJSONObject(0)
            .getJSONObject("edition_observation_proof").getJSONObject(williamsNtdProfile).getString("state"))
    }

}
