package com.williamsbot

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StrategyDiagnosticAuditTest {
    private fun flatObservation() = JSONObject().put("jaw", 100).put("teeth", 100).put("lips", 100)
        .put("ao", 0).put("ac", 0).put("fractal_up_index", 35)
    @Test fun verifiesObservedAlligatorAoAcAndFractalCausality() {
        val result = auditWilliamsIndicatorObservation(List(40) { 100.0 }, flatObservation())
        for (id in listOf("STR-01", "STR-02", "STR-03", "STR-04")) assertEquals("PASS", result.getJSONObject(id).getString("severity"))
    }
    @Test fun detectsWrongNumericalValuesAndLookaheadIndependently() {
        val observed = flatObservation().put("jaw", 101).put("ao", 1).put("ac", 1).put("fractal_up_index", 38)
        val result = auditWilliamsIndicatorObservation(List(40) { 100.0 }, observed)
        for (id in listOf("STR-01", "STR-02", "STR-03", "STR-04")) assertEquals("FAIL", result.getJSONObject(id).getString("severity"))
    }
    @Test fun insufficientEvidenceIsNotSuccess() {
        val result = auditWilliamsIndicatorObservation(listOf(100.0), flatObservation())
        assertEquals("NOT_VERIFIED", result.getJSONObject("STR-01").getString("severity"))
    }
    @Test fun nonconstantMedianSeriesChecksProductionWarmupAndDisplacement() {
        val m = (1..80).map(Int::toDouble)
        val ao = m.indices.map { awesomeOscillatorAt(m, it) }
        val observation = JSONObject().put("jaw", shiftAlligatorForDisplay(smmaSeededValues(m, 13), 8).last())
            .put("teeth", shiftAlligatorForDisplay(smmaSeededValues(m, 8), 5).last())
            .put("lips", shiftAlligatorForDisplay(smmaSeededValues(m, 5), 3).last())
            .put("ao", ao.last()).put("ac", acceleratorOscillatorAt(ao, ao.lastIndex))
        val result = auditWilliamsIndicatorObservation(m, observation)
        for (id in listOf("STR-01", "STR-03", "STR-04")) assertEquals("PASS", result.getJSONObject(id).getString("severity"))
        observation.put("jaw", smmaSeededValues(m, 13).last())
        assertEquals("FAIL", auditWilliamsIndicatorObservation(m, observation).getJSONObject("STR-01").getString("severity"))
    }
}

