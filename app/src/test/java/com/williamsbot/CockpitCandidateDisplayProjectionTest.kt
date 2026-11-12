package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CockpitCandidateDisplayProjectionTest {
    @Test fun compactCandidateRetainsEveryUiFieldAndRemovesHugeEvidence() {
        val deep = JSONObject().put("full_closed_h1", JSONArray().put(JSONObject().put("close", 1)))
        val sourceRow = JSONObject()
        cockpitCandidateDisplayKeys.forEach { key ->
            sourceRow.put(key, when (key) {
                "symbol" -> "BNBUSDT"
                "score" -> 61.0
                "signal" -> true
                "shadow_intent" -> JSONObject().put("direction", "SHORT")
                "xai_factors" -> JSONObject().put("AO", 2.0)
                else -> "example"
            })
        }
        sourceRow.put("decision_trace", deep)
            .put("full_wave_hierarchy", deep)
            .put("entry_execution_authority", false)
        val full = JSONArray().put(sourceRow)
        val projected = cockpitCandidateDisplayProjection(full)
        assertEquals(1, projected.length())
        val short = projected.getJSONObject(0)
        assertEquals(cockpitCandidateDisplayKeys.size, short.length())
        for (key in cockpitCandidateDisplayKeys) {
            assertTrue("Missing Cockpit parser field $key", short.has(key))
            assertEquals(sourceRow.get(key).toString(), short.get(key).toString())
        }
        assertFalse(short.has("decision_trace"))
        assertFalse(short.has("full_wave_hierarchy"))
        assertFalse(short.has("entry_execution_authority"))
        assertTrue(sourceRow.has("decision_trace")) // source diagnostic object is intact
        assertEquals("SHORT", short.getJSONObject("shadow_intent").getString("direction"))
    }

    @Test fun invalidSourceDoesNotCreateFakeCandidate() {
        try {
            cockpitCandidateDisplayProjection(JSONArray().put("not an object"))
            fail("Malformed candidate should fail, not create a trading signal")
        } catch (_: IllegalStateException) { }
    }
}
