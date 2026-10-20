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

    @Test fun indexedArchiveWindowMatchesUnsortedPathAndRejectsBadWarmupOrDuplicate() {
        val rows=series(q,500)
        val start=zero+40*h;val end=zero+70*h;val now=zero+120*h
        val expected=verifiedAdaptiveWaveWindow(rows.reversed(),"15m",start,end,now)
        assertFalse(expected.isEmpty())
        assertEquals(expected,verifiedAdaptiveWaveWindow(rows,"15m",start,end,now,true))
        val bad=rows.toMutableList();bad[140]=bad[140].copy(h=Double.NaN)
        assertTrue(verifiedAdaptiveWaveWindow(bad,"15m",start,end,now,true).isEmpty())
        val duplicates=(rows+rows[170]).sortedBy { it.t }
        assertTrue(verifiedAdaptiveWaveWindow(duplicates,"15m",start,end,now,true).isEmpty())
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

    @Test fun olderActualChildWindowWorksWhenTheMoreRecentTailHasNotYetDownloaded() {
        val start=zero+40*h
        val end=start+30*h
        val now=zero+100*h
        // M15 archive contains the targeted older wave and AO warm-up,
        // but NOT the later 30 hours of M15 data.
        val archivedM15=series(q,284)
        val r=chooseAdaptiveWaveResolution(start,end,"1h",
            mapOf("15m" to archivedM15),now)
        assertEquals("15m",r.frame)
        assertEquals(120,r.observedBars)
        assertEquals("BOOK_WINDOW_OBSERVED",r.status)
        val gap=archivedM15.filterIndexed { i,_ -> i!=170 }
        assertTrue(verifiedAdaptiveWaveWindow(gap,"15m",start,end,now).isEmpty())
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

    @Test fun fourthWaveCrossesAoZeroOnlyAfterTheActualThirdPriceLegInBothDirections() {
        val start=zero+40*h
        val end=zero+70*h
        val asOf=zero+74*h
        for (direction in listOf("UP","DOWN")) {
            val positive=direction=="UP"
            val prices=(0 until 296).map { i ->
                val hours=i/4.0
                val p=when {
                    hours<50.0->100.0
                    hours<=58.0->100.0+7.5*(hours-50.0)
                    hours<=70.0->160.0-8.0*(hours-58.0)
                    else->64.0
                }
                if(positive) p else 300.0-p
            }
            val bars=prices.mapIndexed { i,p ->
                WilliamsWaveBar(zero+i*q,p,p+1.0,p-1.0,p,10.0)
            }
            val types=if(positive) listOf("LOW","HIGH","LOW","HIGH","LOW")
                else listOf("HIGH","LOW","HIGH","LOW","HIGH")
            val anchors=listOf(40,45,50,58,70).mapIndexed { i,n ->
                WilliamsWaveAnchor(types[i],prices[n*4]+if(types[i]=="HIGH") 1.0 else -1.0,
                    i,zero+n*h,zero+(n+3)*h)
            }
            val scenario=WilliamsWaveScenario(
                "H1:"+direction,"1h",direction,"W4",anchors,asOf,
                anchors.last().extremeAt,60.0,240.0,100.0)
            val choice=chooseAdaptiveWaveResolution(start,end+q,"1h",
                mapOf("15m" to bars),asOf)
            assertEquals("BOOK_WINDOW_OBSERVED",choice.status)
            val out=adaptiveWave4AoEvidence(scenario,choice,
                mapOf("15m" to bars),asOf)
            assertEquals("ZERO_CROSSED_MINIMUM_ONLY",out.getString("status"))
            assertTrue(out.getBoolean("crossed_zero"))
            // The hourly low/high repeats throughout the last hourly candle;
            // use the last matching real M15 extreme, not its H1 open time.
            assertEquals(zero+70*h+3*q,out.getLong("w4_actual_extreme_ms"))
            assertFalse(out.getBoolean("wave4_completion_confirmed"))
            assertFalse(out.getBoolean("can_close_positions"))
        }
    }

    @Test fun missingD1H4OrH1CannotCreateAFabricatedParentChildWaveLineage() {
        val context=buildAdaptiveHistoricalWaveReport(
            mapOf("1h" to series(h,72)),zero+72*h)
        assertFalse(context.getBoolean("wave_genealogy_confirmed"))
        assertFalse(context.getBoolean("can_place_orders"))
        assertEquals(0,context.getJSONArray("observed_parent_child_links").length())
    }
}
