package com.williamsbot

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WilliamsScenarioLifecycleTest {
    private val hour=3_600_000L
    private fun contract()=JSONObject(requireNotNull(javaClass.getResource("/williams_scenario_lifecycle_contract.json")).readText())

    @Test fun sameMirroredPriceEventContractAsPython() {
        val cases=contract().getJSONArray("level_events")
        for(i in 0 until cases.length()) {
            val case=cases.getJSONObject(i);val a=case.getJSONObject("input");val expected=case.getJSONObject("expected")
            val rows=a.getJSONArray("history")
            val bars=(0 until rows.length()).map { j -> val b=rows.getJSONObject(j)
                WilliamsWaveBar(b.getLong("t"),b.getDouble("o"),b.getDouble("h"),b.getDouble("l"),b.getDouble("c"),b.getDouble("v")) }
            val event=evaluateWilliamsLevelEvent(a.getString("side"),a.getDouble("trigger"),a.getLong("signal_closed_at"),
                a.getLong("scope_started_at"),a.getDouble("mark"),bars,a.getLong("now"),a.getLong("expires_at"),
                a.getLong("frame_ms"),a.getDouble("invalidation"))
            assertEquals(case.getString("name"),expected.getString("state"),event.state)
            assertEquals(case.getString("name"),expected.getString("basis"),event.basis)
            assertEquals(case.getString("name"),if(expected.isNull("known_at_ms")) 0L else expected.getLong("known_at_ms"),event.knownAt)
        }
    }

    @Test fun sameDegreeScopedPhaseContractAsPython() {
        val cases=contract().getJSONArray("phase_cases")
        for(i in 0 until cases.length()) {
            val case=cases.getJSONObject(i);val result=williamsMarketPhaseContext(case.getJSONObject("input"))
            val expected=case.getJSONObject("expected")
            assertEquals(case.getString("name"),expected.getString("warning_scope"),result.getString("warning_scope"))
            val frames=result.getJSONArray("frames")
            val all=mutableMapOf<String,JSONObject>()
            for(j in 0 until frames.length()) {
                val f=frames.getJSONObject(j)
                // H4 is an additive, explicitly unavailable observer until
                // native H4 candles/scenarios are actually present. Legacy
                // shared Python fixture covers D1/H6/H1/M15 only.
                if(f.getString("frame")=="4h") {
                    assertEquals("UNAVAILABLE",f.getString("state"))
                    assertFalse(f.getBoolean("wave_degree_confirmed"))
                    assertFalse(f.getBoolean("can_place_orders"))
                    continue
                }
                assertEquals(expected.getJSONObject("frame_states").getString(f.getString("frame")),f.getString("state"))
                val hs=f.getJSONArray("hypotheses")
                for(k in 0 until hs.length()) { val h=hs.getJSONObject(k);all[h.getString("scenario_id")]=h }
            }
            val signs=expected.getJSONObject("signs_by_id")
            for(id in signs.keys()) assertEquals(signs.getJSONArray(id).toString(),all.getValue(id).getJSONArray("signs").toString())
            for(key in listOf("can_place_orders","can_close","can_reverse","wave_genealogy_confirmed","probability_calibrated")) assertFalse(result.getBoolean(key))
        }
    }

    @Test fun sameTriggeredOppositionRevokesParentAndExitsOlderPositionInBothDirections() {
        for(short in listOf(false,true)) {
            val direction=if(short) "SHORT" else "LONG"
            val source=WilliamsScenarioSignal("later",if(short) "LONG" else "SHORT","TC2_THREE_WISE_MEN","STOP_ENTRY",19*hour,
                if(short) 110.0 else 90.0,if(short) 90.0 else 110.0,24*hour)
            val bar=if(short) WilliamsWaveBar(20*hour,100.0,112.0,99.0,100.0) else WilliamsWaveBar(20*hour,100.0,101.0,88.0,100.0)
            for(started in listOf(18*hour,19*hour)) {
                val result=assessWilliamsScenarioOpposition(direction,started,listOf(source),listOf(bar),100.0,21*hour)
                assertEquals("H1_PARENT_OPPOSITE_TRIGGERED",result.blocker)
                assertEquals("later",result.opposition!!.signal.id)
            }
            assertNull(assessWilliamsScenarioOpposition(direction,20*hour,listOf(source),listOf(bar),100.0,21*hour).blocker)
        }
    }

    @Test fun intrabarAmbiguityHoldsNewRiskButDoesNotInventAnExit() {
        val source=WilliamsScenarioSignal("ambiguous","SHORT","TC2_THREE_WISE_MEN","STOP_ENTRY",19*hour,90.0,110.0,24*hour)
        val result=assessWilliamsScenarioOpposition("LONG",18*hour,listOf(source),
            listOf(WilliamsWaveBar(20*hour,100.0,112.0,88.0,100.0)),100.0,21*hour)
        assertEquals("H1_OPPOSITION_AMBIGUOUS",result.blocker);assertNull(result.opposition)
        assertEquals("AMBIGUOUS",result.uncertain.single().event.state)
    }

    @Test fun onlyNtdHasTheConfirmedCloseEventSemantics() {
        val source=WilliamsScenarioSignal("close","SHORT","NTD_FIVE_DIMENSIONS","MARKET_ON_CLOSE",19*hour,100.0,null,24*hour)
        assertNotNull(williamsScenarioOpposition("LONG",18*hour,listOf(source),emptyList(),100.0,21*hour))
        assertNull(williamsScenarioOpposition("LONG",18*hour,listOf(source.copy(profile="TC2_THREE_WISE_MEN")),emptyList(),100.0,21*hour))
    }
}
