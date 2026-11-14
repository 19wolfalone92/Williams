package com.williamsbot

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class FuturesHalfMarginTest {
    @Test fun leaseRejectsFutureMissingAndExpiredProofs() {
        assertTrue(futuresHalfMarginLeaseFresh(1L, 15_000_000_001L))
        assertFalse(futuresHalfMarginLeaseFresh(1L, 15_000_000_002L))
        assertFalse(futuresHalfMarginLeaseFresh(0L, 1L))
        assertFalse(futuresHalfMarginLeaseFresh(2L, 1L))
        assertFalse(futuresHalfMarginLeaseFresh(1L, Long.MAX_VALUE))
    }
    private fun cases(): List<JSONObject> {
        val raw = javaClass.classLoader!!.getResourceAsStream("williams_half_margin_contract.json")!!
            .bufferedReader().use { it.readText() }
        val cases = JSONObject(raw).getJSONArray("cases")
        return (0 until cases.length()).map { cases.getJSONObject(it) }
    }
    private fun observe(case: JSONObject): HalfMarginObservation {
        val symbols = case.getJSONObject("symbols")
        return futuresMarginObservation(case.getJSONObject("account"), case.getJSONObject("configuration"),
            case.getJSONArray("orders"), symbols.keys().asSequence().associateWith { symbols.getJSONObject(it) })
    }
    private fun evaluate(case: JSONObject): JSONObject = assessFuturesHalfMargin(observe(case),
        observe(case.optJSONObject("second") ?: case), case.getJSONObject("proposal"), case.getJSONArray("liabilities"),
        case.optLong("elapsed_ms", 0L))

    @Test fun sharedPythonAndroidAcceptanceContract() {
        for (case in cases()) {
            val expected = case.getString("expected")
            val result = runCatching { evaluate(case) }
            if (expected.startsWith("UNKNOWN:")) {
                assertTrue(case.getString("id") + ": " + result, result.isFailure)
                assertTrue(result.exceptionOrNull().toString(), result.exceptionOrNull().toString()
                    .contains("FUTURES_HALF_MARGIN_$expected"))
            } else {
                assertTrue(case.getString("id") + ": " + result.exceptionOrNull(), result.isSuccess)
                assertEquals(case.getString("id"), expected == "PASS", result.getOrThrow().getBoolean("allowed"))
            }
        }
    }
    @Test fun explicitAmountsPartialAndBoundary() {
        val cases = cases().associateBy { it.getString("id") }
        assertEquals(0, evaluate(cases.getValue("440_plus_40_pending_plus_30_rejected"))
            .getString("projected_usdt").toBigDecimal().compareTo(BigDecimal("510.595735")))
        assertEquals(0, evaluate(cases.getValue("partial_parent_bound_to_child_once"))
            .getString("projected_usdt").toBigDecimal().compareTo(BigDecimal("490.425525")))
        assertEquals(0, evaluate(cases.getValue("exact_50_percent_including_costs"))
            .getString("projected_usdt").toBigDecimal().compareTo(BigDecimal("500")))
    }
    @Test fun aChildCannotBeClaimedByTwoParents() {
        val case = cases().single { it.getString("id") == "partial_parent_bound_to_child_once" }
        val liabilities = case.getJSONArray("liabilities")
        liabilities.put(JSONObject(liabilities.getJSONObject(0).toString()).put("client_id", "another-parent"))
        val result = runCatching { evaluate(case) }
        assertTrue(result.exceptionOrNull().toString(), result.exceptionOrNull().toString().contains("LIABILITY_DUPLICATE_CLAIM"))
    }
}
