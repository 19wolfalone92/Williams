package com.williamsbot

import org.junit.Assert.*
import org.junit.Test

class WilliamsAdaptiveWaveHistoryTest {
    private val h=3_600_000L
    private val q=h/4
    private val zero=1_000L*h
    private fun series(frame:Long, count:Int, origin:Long=zero):List<WilliamsWaveBar> =
        (0 until count).map { i ->
            val t=origin+i*frame
            WilliamsWaveBar(t,100.0,101.0,99.0,100.0,10.0)
        }

    @Test fun thirtyHourlyBarsActuallyResolveToOneHundredTwentyFifteenMinuteBars() {
        val start=zero+40*h
        val end=start+30*h
        val asOf=end+h
        val history=mapOf("1h" to series(h,71),
            "15m" to series(q,284))
        val r=chooseAdaptiveWaveResolution(start,end,"1h",history,asOf)
        assertEquals("15m",r.frame)
        assertEquals("BOOK_WINDOW_OBSERVED",r.status)
        assertEquals(120,r.observedBars)
        assertFalse(r.toJson().getBoolean("can_place_orders"))
    }

    @Test fun onlyThirtyHourlyBarsIsUnverifiedWithoutRealLowerFrameData() {
        val start=zero+40*h
        val end=start+30*h
        val history=mapOf("1h" to series(h,71))
        val r=chooseAdaptiveWaveResolution(start,end,"1h",history,end+h)
        assertEquals("WRONG_SCALE_UNCONFIRMED",r.status)
        assertEquals("1h",r.frame)
        assertEquals(30,r.observedBars)
        assertEquals("15m",r.wantedFrame)
    }

    @Test fun missingMiddleCandleCannotBeInvisibleToAdaptiveWaveCount() {
        val start=zero+40*h
        val end=start+30*h
        val m15=series(q,284).filterIndexed { index,_ -> index!=220 }
        val r=chooseAdaptiveWaveResolution(start,end,"1h",
            mapOf("15m" to m15,"1h" to series(h,71)),end+h)
        assertNotEquals("BOOK_WINDOW_OBSERVED",r.status)
    }

    @Test fun futureCandleAppendCannotChangeAsOfWaveResolution() {
        val start=zero+40*h
        val end=start+30*h
        val asOf=end+h
        val m15=series(q,284)
        val without=chooseAdaptiveWaveResolution(start,end,"1h",
            mapOf("15m" to m15),asOf)
        val future=m15+WilliamsWaveBar(asOf+h,999.0,1000.0,998.0,999.0,1.0)
        val with=chooseAdaptiveWaveResolution(start,end,"1h",
            mapOf("15m" to future),asOf)
        assertEquals(without,with)
    }

    @Test fun wave4CannotBeCompletedFromNearZeroOrMissingAnchorEvidence() {
        val anchors=listOf(
            "LOW" to 100.0,"HIGH" to 130.0,"LOW" to 110.0,
            "HIGH" to 145.0,"LOW" to 120.0
        ).mapIndexed { i,(kind,p) ->
            val t=zero+listOf(40,45,50,58,70)[i]*h
            WilliamsWaveAnchor(kind,p,i,t,t+3*h)
        }
        val scenario=WilliamsWaveScenario("1h:UP", "1h","UP","W4",anchors,
            zero+72*h,anchors.last().extremeAt,105.0,145.0,100.0)
        val asOf=zero+74*h
        val m15=series(q,296)
        val resolution=chooseAdaptiveWaveResolution(anchors.first().extremeAt,
            anchors.last().extremeAt+q,"1h",mapOf("15m" to m15),asOf)
        val evidence=adaptiveWave4AoEvidence(scenario,resolution,
            mapOf("15m" to m15),asOf)
        assertFalse(evidence.getBoolean("can_place_orders"))
        assertFalse(evidence.getBoolean("wave4_completion_confirmed"))
    }

    @Test fun missingD1H4OrH1CannotCreateAFabricatedParentChildWaveLineage() {
        val context=buildAdaptiveHistoricalWaveReport(
            mapOf("1h" to series(h,72)),zero+72*h)
        assertFalse(context.getBoolean("wave_genealogy_confirmed"))
        assertFalse(context.getBoolean("can_place_orders"))
        assertEquals(0,context.getJSONArray("observed_parent_child_links").length())
    }
}
