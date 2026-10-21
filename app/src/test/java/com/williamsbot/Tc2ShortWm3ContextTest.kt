package com.williamsbot

import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Reproduces the ten-symbol H1 scanner failure from 2026-10-20 diagnostics. */
class Tc2ShortWm3ContextTest {
    @Test fun legacyEmptyWm3ContextNoLongerCrashesShortDetector() {
        val mirrored = JSONObject(reflectedTc2SetupContext("FRACTAL", "", 5000.0))
        assertEquals("WM3", mirrored.getString("wise_man"))
        assertTrue(mirrored.getBoolean("legacy_source_context_missing"))
        // The source omission is recorded; no false price/teeth proof is created.
        assertFalse(mirrored.has("teeth_filter_verified"))
        assertFalse(mirrored.has("risk_stop_at_price"))
    }

    @Test fun confirmedWm3ContextPreservesCausalTimesOnShortReflection() {
        val original = JSONObject()
            .put("wise_man", "WM3")
            .put("signal_family", "CONFIRMED_FRACTAL")
            .put("fractal_center_time_ms", 1000L)
            .put("fractal_confirmation_time_ms", 3000L)
            .put("teeth_filter_verified", true)
            .toString()
        val mirrored = JSONObject(reflectedTc2SetupContext("FRACTAL", original, 5000.0))
        assertEquals("WM3", mirrored.getString("wise_man"))
        assertEquals(1000L, mirrored.getLong("fractal_center_time_ms"))
        assertEquals(3000L, mirrored.getLong("fractal_confirmation_time_ms"))
        assertTrue(mirrored.getBoolean("teeth_filter_verified"))
        assertFalse(mirrored.has("legacy_source_context_missing"))
    }

    @Test fun emptyWm1AndWm2ProofStillFailClosed() {
        for (type in listOf("REVERSAL", "SUPER_AO")) {
            try {
                reflectedTc2SetupContext(type, "", 5000.0)
                fail("Missing $type evidence must not be fabricated")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message.orEmpty().contains("TC2_SOURCE_CONTEXT_MISSING"))
            }
        }
    }

    @Test fun shortWm1SlopesAndWm2StopReflectCorrectly() {
        val source = JSONObject().put("angulation",
            JSONObject().put("price_slope", -2.5).put("jaw_slope", -0.5)
                .put("teeth_slope", -0.25))
            .put("risk_stop_at_price", 90.0)
            .toString()
        val mirrored = JSONObject(reflectedTc2SetupContext("REVERSAL", source, 200.0))
        assertEquals(2.5, mirrored.getJSONObject("angulation").getDouble("price_slope"), 0.00001)
        assertEquals(0.5, mirrored.getJSONObject("angulation").getDouble("jaw_slope"), 0.00001)
        assertEquals(0.25, mirrored.getJSONObject("angulation").getDouble("teeth_slope"), 0.00001)
        assertEquals(110.0, mirrored.getDouble("risk_stop_at_price"), 0.00001)
    }

    @Test fun malformedNonEmptyContextAndInvalidReflectionNeverPass() {
        try {
            reflectedTc2SetupContext("FRACTAL", "{malformed", 200.0)
            fail("Malformed proof must not be treated as a legacy-empty WM3")
        } catch (expected: JSONException) {
            // Expected: preserve the parse failure for diagnostics.
        }
        try {
            reflectedTc2SetupContext("FRACTAL", "", Double.NaN)
            fail("NaN reflection must never be accepted")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("TC2_SHORT_REFLECTION_INVALID"))
        }
    }

    @Test fun wm1AoColourIsMirroredIntoRealShortEvidenceNotAHiddenVeto() {
        val source=JSONObject().put("wm1_ao_color","RED")
            .put("wm1_ao_opposite_color_observed",true)
            .put("wm1_ao_color_is_evidence_not_new_execution_veto",true).toString()
        val result=JSONObject(reflectedTc2SetupContext("REVERSAL",source,200.0))
        assertEquals("GREEN",result.getString("wm1_ao_color"))
        assertTrue(result.getBoolean("wm1_ao_opposite_color_observed"))
        assertTrue(result.getBoolean("wm1_ao_color_is_evidence_not_new_execution_veto"))
    }

}
