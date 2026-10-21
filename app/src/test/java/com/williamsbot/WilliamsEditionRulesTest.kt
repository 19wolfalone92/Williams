package com.williamsbot

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WilliamsEditionRulesTest {
    @Test fun oppositeAcColourCancelsAnUnhitNtdSignalEvenAfterALaterRecovery() {
        for(side in listOf("LONG","SHORT")) for(tail in listOf(
            listOf(5.0,4.0,5.0,6.0,5.0),listOf(4.0,5.0,6.0,5.0,5.5))) {
            val sign=if(side=="LONG") 1 else -1
            val bars=(0 until 60).map { i ->
                val high=if(i==58 || i==57) 110.0 else 100.0
                if(sign==1) WilliamsWaveBar((i+1)*3_600_000L,99.0,high,98.0,99.0,100.0)
                else WilliamsWaveBar((i+1)*3_600_000L,101.0,102.0,200-high,101.0,100.0)
            }
            val line=List(60) { if(sign==1) 90.0 else 110.0 }
            val ac=List(55) { 0.0 }+tail.map { sign*it }
            val ao=List(60) { sign*(it+1.0) }
            assertFalse(williamsNtdSetups(side,bars,line,line,line,ao,ac,.1).any { it.kind=="AC_SIGNAL" })
        }
    }
    @Test fun unifiedBooksAllowEveryVerifiedSourceWithoutChangingItsIdentity() {
        assertEquals(4,williamsEditionProfiles.size)
        assertEquals(3,williamsSourceProfiles.size)
        for(source in williamsSourceProfiles) {
            assertTrue(williamsProfileAllowsSource(williamsUnifiedProfile,source))
            assertTrue(williamsProfileAllowsSource(source,source))
        }
        assertFalse(williamsProfileAllowsSource(williamsTc2Profile,williamsNtdProfile))
        assertFalse(williamsProfileAllowsSource(williamsNtdProfile,williamsCourseProfile))
        assertFalse(williamsProfileAllowsSource(williamsUnifiedProfile,"UNKNOWN"))
        assertFalse(williamsProfileAllowsSource(williamsUnifiedProfile,williamsUnifiedProfile))
    }

    @Test fun unifiedBooksBlockOppositeDirectionsButNotSameDirectionEvidence() {
        assertFalse(williamsUnifiedDirectionsConflict(listOf("LONG","LONG","LONG")))
        assertFalse(williamsUnifiedDirectionsConflict(listOf("SHORT","SHORT")))
        assertFalse(williamsUnifiedDirectionsConflict(listOf("UNKNOWN","LONG")))
        assertTrue(williamsUnifiedDirectionsConflict(listOf("LONG","SHORT")))
        assertTrue(williamsUnifiedDirectionsConflict(listOf("SHORT","LONG","LONG")))
    }
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
    @Test fun oppositeCloseEventsReduceOnlyOlderPositionsWithinTheRecoveryWindow() {
        assertTrue(williamsOpposingCloseEventTriggered(100,1000,100,1100))
        assertFalse(williamsOpposingCloseEventTriggered(100,1000,1100,1100))
        assertFalse(williamsOpposingCloseEventTriggered(100,1000,100,1099))
        assertTrue(williamsOpposingCloseEventTriggered(100,1000,100,6099))
        assertFalse(williamsOpposingCloseEventTriggered(100,1000,100,6100))
        assertFalse(williamsOpposingCloseEventTriggered(100,1000,0,1100))
        assertFalse(williamsOpposingCloseEventTriggered(Long.MAX_VALUE,1000,100,Long.MAX_VALUE))
    }
    @Test fun ntdDimensionsCanShareTheFractalConfirmationBarWithoutReusingItsFamily() {
        assertTrue(williamsAdditionTimeEligible(williamsNtdProfile,100,"AC_SIGNAL",100,"FRACTAL",200))
        assertTrue(williamsAdditionTimeEligible(williamsNtdProfile,100,"SHORT_AC_SIGNAL",100,"SHORT_FRACTAL",200))
        assertFalse(williamsAdditionTimeEligible(williamsNtdProfile,100,"FRACTAL",100,"FRACTAL",100))
        assertFalse(williamsAdditionTimeEligible(williamsNtdProfile,99,"AC_SIGNAL",100,"FRACTAL",100))
        assertFalse(williamsAdditionTimeEligible(williamsTc2Profile,100,"SUPER_AO",100,"FRACTAL",100))
    }
    @Test fun bothOutsideFractalsRemainVisibleWithoutInventingTheirIntrabarOrder() {
        val highs=listOf(10.0,11.0,15.0,11.0,10.0);val lows=listOf(8.0,7.0,3.0,7.0,8.0)
        val bars=highs.indices.map { i -> WilliamsWaveBar((i+1)*3_600_000L,(highs[i]+lows[i])/2,highs[i],lows[i],(highs[i]+lows[i])/2,100.0) }
        assertEquals(setOf("HIGH","LOW"),confirmedWilliamsEditionFractals(bars).map { it.kind }.toSet())
        assertNull(williamsCourseHumpSetup("LONG",bars,.1))
    }
}
