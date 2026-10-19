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
            .put("signal_bar_time_ms", now - 4_000_000L)
            .put("trigger_price", 100.0)
            .put("protective_price", if (family.startsWith("SHORT_")) 101.0 else 99.0)
            .put("reason", "CLOSED_H1_TEST")

    private fun scanner(complete: Boolean = true, signals: JSONArray = JSONArray()): JSONObject {
        val candidates = JSONArray()
        APPROVED_FUTURES_SYMBOLS.forEachIndexed { i, symbol ->
            candidates.put(JSONObject().put("symbol", symbol)
                .put("campaign_signals", if (i == 0) signals else JSONArray()))
        }
        return JSONObject().put("scanner_state", "READY")
            .put("scanning", !complete).put("last_scan_at", now - 3_000L)
            .put("symbols_scanned", 10).put("run_id", "futures-test")
            .put("candidates", candidates)
    }

    private fun status() = JSONObject().put("strategy_profile", williamsUnifiedProfile)

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
        val pair = result.getJSONArray("by_symbol").getJSONObject(0)
        assertTrue(pair.getBoolean("fresh_opposing_entries_before_context"))
        for (item in CockpitDiagnostics.rows(pair.getJSONArray("signals"))) {
            assertFalse(item.getBoolean("exchange_order_authorized"))
            assertEquals("NOT_EVALUATED_BY_CACHED_SCANNER", item.getString("admission_proof"))
        }
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
    }

    @Test fun staleAndLegacyProfileAreExplicitlyMarked() {
        val late = buildWilliamsUnifiedEvidence(scanner().put("last_scan_at", now - 130_000L), status(), now)
        assertEquals("STALE_OR_MISSING_SCAN", late.getString("status"))
        val legacy = buildWilliamsUnifiedEvidence(scanner(),
            JSONObject().put("strategy_profile", williamsTc2Profile), now)
        assertEquals("WRONG_PROFILE", legacy.getString("status"))
    }
}
