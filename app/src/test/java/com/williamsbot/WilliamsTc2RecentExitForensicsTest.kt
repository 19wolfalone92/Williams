package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WilliamsTc2RecentExitForensicsTest {
    @Test fun bchShortShowsActualOppositeH1TriggerWithoutInventingTrendEnd() {
        val opposition=JSONObject().put("signal_id","BCHUSDT:1h:SUPER_AO:1794387600000")
            .put("trigger",229.43)
            .put("event",JSONObject().put("state","TRIGGERED"))
        val evidence=JSONObject().put("reason","OPPOSITE_WILLIAMS_LEVEL_TRIGGERED")
            .put("opposition_check", JSONObject().put("contract_price",229.64)
                .put("opposition",opposition)
                .put("opposition_revalidation",
                    JSONObject().put("status","FRESH_CONFIRMED_OPPOSITE")
                        .put("closed_h1_after_first_trigger",0)))
        val row=JSONObject().put("symbol","BCHUSDT").put("direction","SHORT")
            .put("status","CLOSED").put("entry_price",229.81).put("exit_price",229.38)
            .put("qty",0.506).put("net_pnl_usdt",0.12463995)
            .put("exit_reason","OPPOSITE_WILLIAMS_LEVEL_TRIGGERED")
            .put("entry_snapshot",JSONObject().put("edition_management",
                JSONObject().put("exit_evidence",evidence)))
        val summary=WilliamsTc2RecentExitForensics.summarize(JSONArray().put(row))
        val exit=summary.getJSONArray("recent_closed_trades").getJSONObject(0)
        assertEquals("SUPER_AO",exit.getString("opposite_signal_type"))
        assertTrue(exit.getBoolean("opposite_h1_signal_confirmed_in_saved_evidence"))
        assertFalse(exit.getBoolean("higher_trend_reversal_independently_proved"))
        assertFalse(exit.getBoolean("can_override_exit_or_stop"))
        assertEquals(0,exit.getInt("closed_h1_after_first_trigger"))
        assertEquals(229.43,exit.getDouble("opposite_trigger"),1e-9)
    }
    @Test fun missingOrConflictingProofCannotBeRewrittenAsVerifiedOpposite() {
        val row=JSONObject().put("status","CLOSED")
            .put("exit_reason","OPPOSITE_WILLIAMS_LEVEL_TRIGGERED")
        val exit=WilliamsTc2RecentExitForensics.summarize(JSONArray().put(row))
            .getJSONArray("recent_closed_trades").getJSONObject(0)
        assertFalse(exit.getBoolean("opposite_h1_signal_confirmed_in_saved_evidence"))
        assertEquals(0,WilliamsTc2RecentExitForensics.summarize(null).getInt("trade_count"))
    }
}
