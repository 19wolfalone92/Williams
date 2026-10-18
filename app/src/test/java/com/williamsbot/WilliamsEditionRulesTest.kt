package com.williamsbot

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WilliamsEditionRulesTest {
    @Test fun sourceRuleExamplesHaveTheSameLevelsAndDirectionsAsPython() {
        val cases=JSONObject(requireNotNull(javaClass.getResource("/williams_editions_contract.json")).readText()).getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c=cases.getJSONObject(i);val rows=c.getJSONArray("bars")
            val bars=(0 until rows.length()).map { j -> val b=rows.getJSONArray(j)
                WilliamsWaveBar(b.getLong(0),b.getDouble(1),b.getDouble(2),b.getDouble(3),b.getDouble(4),b.getDouble(5)) }
            fun values(key:String)=c.getJSONArray(key).let { a -> (0 until a.length()).map { a.getDouble(it) } }
            val side=c.getString("side");val tick=c.getDouble("tick")
            if (c.getString("operation")=="AIRBAG") {
                assertEquals(c.getDouble("expected_stop"),requireNotNull(williamsCourseAirbagStop(side,bars,tick)),1e-9)
                continue
            }
            val setup=requireNotNull(when (c.getString("operation")) {
                "BALANCE" -> williamsNtdBalanceLineSetup(side,bars,values("jaw"),values("teeth"),values("lips"),values("ao"),values("ac"),tick)
                "THUMB" -> williamsCourseThumbSetup(side,bars,tick)
                else -> williamsCourseHumpSetup(side,bars,tick)
            }) { "Missing setup in case $i" }
            assertEquals(c.getDouble("expected_trigger"),setup.trigger,1e-9)
            assertEquals(c.getDouble("expected_stop"),setup.stop,1e-9)
            if (c.has("expected_count")) assertEquals(c.getInt("expected_count"),setup.context.getInt("required_extremes"))
        }
    }
    @Test fun marketOnCloseIsNeitherAFutureBreakoutNorAReplayAtAnOldPrice() {
        assertEquals("CLOSE_EVENT_NOT_CLOSED",williamsMarketOnCloseReason(1000,3_600_000,3_600_999,100.0,100.0))
        assertNull(williamsMarketOnCloseReason(1000,3_600_000,3_601_000,100.0,100.0))
        assertEquals("CLOSE_EVENT_EXPIRED",williamsMarketOnCloseReason(1000,3_600_000,3_691_001,100.0,100.0))
        assertEquals("CLOSE_EVENT_SLIPPAGE_LIMIT",williamsMarketOnCloseReason(1000,3_600_000,3_601_000,100.0,101.0))
    }
    @Test fun bothOutsideFractalsRemainVisibleWithoutInventingTheirIntrabarOrder() {
        val highs=listOf(10.0,11.0,15.0,11.0,10.0);val lows=listOf(8.0,7.0,3.0,7.0,8.0)
        val bars=highs.indices.map { i -> WilliamsWaveBar((i+1)*3_600_000L,(highs[i]+lows[i])/2,highs[i],lows[i],(highs[i]+lows[i])/2,100.0) }
        assertEquals(setOf("HIGH","LOW"),confirmedWilliamsEditionFractals(bars).map { it.kind }.toSet())
        assertNull(williamsCourseHumpSetup("LONG",bars,.1))
    }
}
