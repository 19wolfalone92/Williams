package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Regression for Samsung SM-S918B OutOfMemoryError while serializing
 * /api/v1/diagnostics (2026-11-09, StandaloneServer.route).
 * The full report is archived, but the Compose UI retains ONLY small fields.
 */
class DiagnosticUiSnapshotTest {
    @Test fun excludesLargeRawHistoryAndConfigurationFromScreenState() {
        val full = JSONObject()
            .put("created_at_ms", 1794230624481L)
            .put("status", JSONObject()
                .put("android_runtime", JSONObject().put("foreground_service_active", true))
                .put("decision_traces", "large-status-marker"))
            .put("settings", JSONObject().put("decision_traces", "large-settings-marker"))
            .put("configuration_sanitized", JSONObject().put("decision_traces", "large-config-marker"))
            .put("signal_decision_history", JSONObject().put("huge", "large-history-marker"))
            .put("observer_paper_history", JSONObject().put("huge", "large-observer-marker"))
            .put("observer_paper_archive", JSONObject().put("huge", "large-archive-marker"))
            .put("unified_strategy_evidence", JSONObject().put("observed_signal_count", 7))
            .put("tests", JSONArray().put(JSONObject().put("severity", "WARNING")))
            .put("diagnostic_triage", JSONObject().put("overall", "WARNING"))
            .put("diagnostic_summary", JSONObject().put("execution_allowed", false))
            .put("diagnostic_comparison", JSONObject().put("status", "NOT_COMPARABLE"))
            .put("event_timeline", JSONArray().put(JSONObject().put("e", "COCKPIT_EXECUTION")))
        val ui = williamsDiagnosticUiSnapshot(full)

        assertEquals(1794230624481L, ui.getLong("created_at_ms"))
        assertEquals("WARNING", ui.getJSONObject("diagnostic_triage").getString("overall"))
        assertFalse(ui.getJSONObject("diagnostic_summary").getBoolean("execution_allowed"))
        assertTrue(ui.getJSONObject("status").getJSONObject("android_runtime")
            .getBoolean("foreground_service_active"))
        assertEquals(7, ui.getJSONObject("unified_strategy_evidence").getInt("observed_signal_count"))
        assertEquals(1, ui.getJSONArray("tests").length())
        for (forbidden in listOf("settings", "configuration_sanitized", "signal_decision_history",
                "observer_paper_history", "observer_paper_archive")) {
            assertFalse("UI must not retain $forbidden", ui.has(forbidden))
        }
        assertFalse(ui.getJSONObject("status").has("decision_traces"))
        assertFalse(ui.toString().contains("large-"))
        // Never mutate the original report destined for the full streamed ZIP.
        assertTrue(full.has("signal_decision_history"))
        assertTrue(full.has("configuration_sanitized"))
    }

    @Test fun missingOptionalFieldsDoNotRaiseOrInventReadiness() {
        val ui = williamsDiagnosticUiSnapshot(JSONObject())
        assertEquals(0L, ui.getLong("created_at_ms"))
        assertEquals(0, ui.getJSONArray("tests").length())
        assertFalse(ui.getJSONObject("diagnostic_summary").optBoolean("execution_allowed"))
    }
}
