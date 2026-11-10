package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WilliamsDiagnosticConfigurationTest {
    @Test fun compactProjectionPreservesSafetyAndLeavesFullSettingsUnchanged() {
        val large = JSONObject().put("BTCUSDT", JSONArray().put(
            JSONObject().put("state", "observed").put("bar", 100)))
        val settings = JSONObject()
            .put("decision_traces", large)
            .put("version", "4.64.14-debug")
            .put("testnet_live_execution", true)
            .put("max_daily_loss_pct", 0.03)
            .put("risk_per_trade_pct", 0.0025)
            .put("futures_force_one_way", true)
            .put("strategy_profile", williamsTc2Profile)
            .put("futures_margin_type", "ISOLATED")
            .put("campaign_execution_timeframe", "1h")
            .put("futures_leverage_settings", JSONObject().put("default", 2))
        val copy = williamsDiagnosticConfigurationSnapshot(settings)

        assertFalse(copy.has("decision_traces"))
        assertEquals("CONFIGURATION_ONLY_V1", copy.getString("diagnostic_projection"))
        assertEquals("settings.decision_traces", copy.getString("full_decision_trace_path"))
        assertEquals("decision_traces", copy.getJSONArray("omitted_observation_fields")
            .getString(0))
        for (key in listOf("version", "testnet_live_execution", "max_daily_loss_pct",
            "risk_per_trade_pct", "futures_force_one_way", "strategy_profile",
            "futures_margin_type", "campaign_execution_timeframe",
            "futures_leverage_settings")) {
            assertEquals("Key lost: $key", settings.get(key).toString(), copy.get(key).toString())
        }
        assertTrue(settings.has("decision_traces"))
        assertEquals("observed", settings.getJSONObject("decision_traces")
            .getJSONArray("BTCUSDT").getJSONObject(0).getString("state"))
        // No observation data is reinterpreted as a safety green-light.
        assertFalse(copy.optBoolean("execution_allowed", false))
    }

    @Test fun emptySettingsProducesNamedProjectionWithoutSyntheticTradingAuthority() {
        val original = JSONObject()
        val projection = williamsDiagnosticConfigurationSnapshot(original)
        assertEquals("CONFIGURATION_ONLY_V1",projection.getString("diagnostic_projection"))
        assertFalse(projection.has("execution_allowed"))
        assertEquals(0,original.length())
    }
}
