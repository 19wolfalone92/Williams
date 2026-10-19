package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WilliamsEntryPassportTest {
    private val interval = 900_000L
    private fun bar(index: Int, high: Double = 110.0, low: Double = 90.0) =
        WilliamsWaveBar((index + 1) * interval, 100.0, high, low, 100.0, 10.0)

    @Test fun h1DirectionLongM15HighNeedsBothClosedRightCandles() {
        val bars = (0..8).map { bar(it, if (it == 4) 130.0 else 110.0) }
        val before = williamsM15FractalEvidence(bars, "LONG", 8 * interval - 1)
        assertEquals("NO_CONFIRMED_FRACTAL", before.getString("state"))
        assertTrue(before.isNull("fractal_price"))
        val confirmed = williamsM15FractalEvidence(bars, "LONG", 8 * interval)
        assertEquals("CONFIRMED_PIVOT_OBSERVED", confirmed.getString("state"))
        assertEquals(130.0, confirmed.getDouble("fractal_price"), 0.001)
        assertEquals(5 * interval, confirmed.getLong("fractal_formed_at_ms"))
        assertEquals(8 * interval, confirmed.getLong("fractal_confirmed_at_ms"))
        assertFalse(confirmed.getBoolean("trade_authority"))
        // A future candle is unavailable at the exact snapshot time.
        val withFuture = bars + bar(100, 99999.0)
        assertEquals(confirmed.toString(),
            williamsM15FractalEvidence(withFuture, "LONG", 8 * interval).toString())
    }

    @Test fun shortUsesLowFractalAndDoesNotAssumeAnyH1Trigger() {
        val bars = (0..8).map { bar(it, low = if (it == 4) 70.0 else 90.0) }
        val observed = williamsM15FractalEvidence(bars, "SHORT", 8 * interval)
        assertEquals("LOW", observed.getString("fractal_kind"))
        assertEquals(70.0, observed.getDouble("fractal_price"), 0.001)
        assertEquals("OBSERVATION_ONLY", observed.getString("role"))
    }

    @Test fun missingGappedAndStaleM15MustNotFakeAFractal() {
        val bars = (0..8).map { bar(it, if (it == 4) 130.0 else 110.0) }
        assertEquals("CLOSED_HISTORY_UNAVAILABLE",
            williamsM15FractalEvidence(bars.take(4), "LONG", 8 * interval).getString("state"))
        assertEquals("CLOSED_HISTORY_GAP",
            williamsM15FractalEvidence(bars.filter { it.t != 3 * interval }, "LONG", 9 * interval).getString("state"))
        assertEquals("CLOSED_HISTORY_STALE",
            williamsM15FractalEvidence(bars, "LONG", 13 * interval).getString("state"))
    }

    @Test fun passportOnlyAttributesProtectionToSameExchangeIntent() {
        val evidence = JSONObject().put("state", "NO_CONFIRMED_FRACTAL")
        val execution = JSONObject().put("client_id", "entryA")
            .put("direction", "SHORT").put("signal_id", "formation1")
            .put("signal_type", "SHORT_FRACTAL")
            .put("entry_price", "95").put("protective_stop", "108")
            .put("setup_context", JSONObject().put("m15_evidence", evidence))
            .put("outcome", JSONObject().put("accepted", true))
        val unrelated = JSONObject().put("client_id", "entryB").put("direction", "SHORT")
            .put("symbol", "BTCUSDT").put("order_status", "FILLED")
            .put("protection_status", "PROTECTED")
        val unknown = williamsEntryPassport("BTCUSDT", execution, JSONArray().put(unrelated))
        assertEquals("ACCEPTED_UNVERIFIED", unknown.getString("state"))
        assertTrue(unknown.isNull("protection_status"))
        assertEquals("NO_CONFIRMED_FRACTAL", unknown.getJSONObject("m15_evidence").getString("state"))
        val matched = williamsEntryPassport("BTCUSDT", execution, JSONArray().put(unrelated)
            .put(JSONObject(unrelated.toString()).put("client_id", "entryA")
                .put("order_status", "NEW").put("protection_status", "NOT_YET_VERIFIED")))
        assertEquals("INTENT_JOURNALED", matched.getString("state"))
        assertEquals("NEW", matched.getString("order_status"))
        assertEquals("NOT_YET_VERIFIED", matched.getString("protection_status"))
        assertEquals("SHORT", matched.getString("direction"))
    }
}
