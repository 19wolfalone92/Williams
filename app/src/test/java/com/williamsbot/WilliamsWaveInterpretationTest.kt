package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WilliamsWaveInterpretationTest {
    private fun rows(a:JSONArray)= (0 until a.length()).map { a.getJSONObject(it) }
    private fun cases()=rows(JSONObject(requireNotNull(javaClass.getResource(
        "/williams_wave_interpretation_contract.json")).readText()).getJSONArray("cases"))

    @Test fun sharedDegreeExamplesIncludeMirrorsAndNegativeCases() {
        for(case in cases()) {
            val output=williamsWaveInterpretation(case.getJSONObject("input"))
            val expected=case.getJSONObject("expected")
            val row=rows(output.getJSONArray("scopes")).single {
                it.getString("parent_frame")==expected.getString("parent_frame") &&
                    it.getString("child_frame")==expected.getString("child_frame") }
            assertEquals(case.getString("id"),expected.getString("state"),row.getString("state"))
            assertEquals(case.getString("id"),expected.getInt("relation_count"),row.getInt("relation_count"))
            assertTrue(row.getString("explanation_ru").isNotBlank())
            for(scope in rows(output.getJSONArray("scopes"))) {
                assertFalse(scope.getBoolean("wave_genealogy_confirmed"))
                for(relation in rows(scope.getJSONArray("relations"))) {
                    assertTrue(relation.getLong("anchors_known_at")<=case.getJSONObject("input").getLong("as_of"))
                    for(key in listOf("wave_genealogy_confirmed","can_place_orders","can_close","can_reverse"))
                        assertFalse(relation.getBoolean(key))
                }
            }
            for(key in listOf("wave_genealogy_confirmed","forecast_accuracy_certified","can_place_orders","can_close","can_reverse"))
                assertFalse(output.getBoolean(key))
        }
    }

    @Test fun orderOfCompetingCountsCannotPickAWinnerAndPhaseCarriesTheProofs() {
        for(case in cases()) {
            val hierarchy=JSONObject(case.getJSONObject("input").toString())
            val result=williamsWaveInterpretation(hierarchy)
            hierarchy.put("scenarios",JSONArray(rows(hierarchy.getJSONArray("scenarios")).reversed()))
            assertEquals(result.toString(),williamsWaveInterpretation(hierarchy).toString())
            assertEquals(result.toString(),williamsMarketPhaseContext(hierarchy).getJSONObject("wave_interpretation").toString())
        }
    }

    @Test fun missingHistoryIsNotAnImplicitWaveClassification() {
        val output=williamsWaveInterpretation(JSONObject())
        assertTrue(rows(output.getJSONArray("scopes")).all { it.getString("state")=="HISTORY_UNVERIFIED" })
        assertFalse(output.getBoolean("can_place_orders"))
    }
    @Test fun competingScenariosAreShownWithTheirDistinctMeanings() {
        val case=cases().single { it.getString("id")=="conflicting-counts-have-no-majority-winner" }
        val phase=williamsMarketPhaseContext(case.getJSONObject("input"))
        val story=phase.getJSONObject("market_narrative")
        val scope=rows(story.getJSONArray("structural_relations")).single {
            it.getString("parent_frame")=="4h" && it.getString("child_frame")=="1h" }
        val text=scope.getString("explanation_ru")
        assertTrue(text.contains("младшая коррекция внутри импульса"))
        assertTrue(text.contains("продолжение импульса"))
        assertEquals("ALTERNATIVES",scope.getString("state"))
    }

    @Test fun anchorDoesNotBecomeWaveProofUntilSecondRightBarClosed() {
        val fixture=cases().first().getJSONObject("input")
        val test=JSONObject(fixture.toString())
        val child=test.getJSONArray("scenarios").getJSONObject(1)
        val pivot=child.getJSONArray("anchors").getJSONObject(0)
        pivot.put("known_at",pivot.getLong("extreme_at")+2*3_600_000L)
        val report=williamsWaveInterpretation(test)
        assertTrue(report.getJSONArray("rejected_scenario_ids").toString().contains(child.getString("scenario_id")))
        val row=rows(report.getJSONArray("scopes")).single {
            it.getString("parent_frame")=="4h" && it.getString("child_frame")=="1h"
        }
        assertEquals("UNRESOLVED",row.getString("state"))
        assertEquals(0,row.getInt("relation_count"))
        assertFalse(report.getBoolean("can_place_orders"))
    }

}
