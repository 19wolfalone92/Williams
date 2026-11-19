package com.williamsbot

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CockpitDailyRiskDisplayTest {
    private fun snapshot(streak: Int): JSONObject = JSONObject()
        .put("consecutive_losses_available", true)
        .put("consecutive_losses", streak)
        .put("daily_risk_baseline_source", "INITIAL_FLAT_SNAPSHOT")
        .put("max_daily_loss_pct", 0.03)

    @Test fun zero_streak_does_not_claim_that_risk_limits_disappeared() {
        val text = CockpitDiagnostics.readiness(snapshot(0), JSONObject(), 1795116056570L)
            .getString("utc_loss_reset_explanation")
        assertTrue(text.contains("0 из 3"))
        assertTrue(text.contains("3%"))
        assertFalse(text.contains("ограничений нет"))
    }

    @Test fun diag4_null_blocker_does_not_fake_three_losses_on_an_actual_phone_snapshot() {
        // Diagnosed in Williams_Diagnostics (4).zip:
        // consecutive_losses=0, consecutive_loss_blocker=JSON null, daily loss=0.
        val status = snapshot(0)
            .put("consecutive_loss_blocker", JSONObject.NULL)
            .put("daily_loss_pct", 0.0)
        val result = CockpitDiagnostics.readiness(status, JSONObject(), 1795122266463L)
        val explanation = result.getString("utc_loss_reset_explanation")
        assertTrue(explanation, explanation.contains("0 из 3"))
        assertTrue(explanation, explanation.contains("3%"))
        assertFalse(explanation, explanation.contains("Три подряд убыточных закрытия"))
    }

    @Test fun explicit_latched_three_loss_blocker_remains_visible_when_count_is_reset() {
        val status = snapshot(0).put("consecutive_loss_blocker", "THREE_CONSECUTIVE_LOSSES")
        val result = CockpitDiagnostics.readiness(status, JSONObject(), 1795122266463L)
        assertTrue(result.getString("utc_loss_reset_explanation").contains("заблокированы"))
    }

    @Test fun three_consecutive_losses_are_reported_as_a_new_entry_block() {
        val text = CockpitDiagnostics.readiness(snapshot(3), JSONObject(), 1795116056570L)
            .getString("utc_loss_reset_explanation")
        assertTrue(text.contains("Три подряд"))
        assertTrue(text.contains("заблокированы"))
    }
}
