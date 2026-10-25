package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StopTrailDecisionAuditEventTest {
    @Test fun longEvidenceAndExchangeReceiptRemainByteForByteRecoverableAfterSerialization() {
        val candles = JSONArray((0 until 250).map { i ->
            JSONObject().put("open_time_ms", i * 3_600_000L)
                .put("high", 100.0 + i).put("low", 99.0 + i)
                .put("close", 99.5 + i)
        })
        val evidence = JSONObject().put("schema", "WILLIAMS_TC2_STOP_REVIEW_V1")
            .put("source_rule", "CONFIRMED_H1_FRACTAL")
            .put("h1_candles", candles).put("old_stop", 99.0)
            .put("candidate_stop", 101.0)
            .put("wave_hypotheses_can_authorize_orders", false)
        assertTrue(evidence.toString().length > 1600)
        val exchange = JSONObject().put("ok", true)
            .put("client_id", "W2T_BTC_1234").put("confirmed_stop", "101.0")
        val event = stopTrailDecisionAuditEvent("BTCUSDT", "LONG", "campaign-123",
            "EXCHANGE_RESPONSE_OK", evidence, exchange, 42L)
        val roundTrip = JSONObject(event.toString())
        assertEquals("campaign-123", roundTrip.getString("campaign_id"))
        assertEquals("LONG", roundTrip.getString("direction"))
        assertEquals("EXCHANGE_RESPONSE_OK", roundTrip.getString("phase"))
        assertEquals(250, roundTrip.getJSONObject("decision_evidence")
            .getJSONArray("h1_candles").length())
        assertEquals(101.0, roundTrip.getJSONObject("decision_evidence")
            .getDouble("candidate_stop"), 1e-9)
        assertEquals("W2T_BTC_1234", roundTrip.getJSONObject("exchange_outcome")
            .getString("client_id"))
        assertTrue(roundTrip.getBoolean("evidence_untruncated"))
    }

    @Test fun rejectedAndDeferredPhasesCannotClaimBinanceConfirmed() {
        val evidence = JSONObject().put("schema", "WILLIAMS_TC2_STOP_REVIEW_V1")
            .put("decision", "DEFER").put("blocker", "H4_CORRECTION_CAUTION_RETAIN_EXCHANGE_STOP")
        val row = stopTrailDecisionAuditEvent("SOLUSDT", "SHORT", "campaign-456",
            "REVIEW_DEFERRED", evidence, recordedAtMs = 123L)
        assertEquals("REVIEW_DEFERRED", row.getString("phase"))
        assertTrue(row.isNull("exchange_outcome"))
        try {
            stopTrailDecisionAuditEvent("DOGEUSDT", "LONG", "x", "SILENT_SUCCESS",
                evidence, recordedAtMs = 123L)
            fail("Unrecognized exchange state must not be logged as a confirmed success")
        } catch (_: IllegalArgumentException) { }
    }
}
