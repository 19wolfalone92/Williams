package com.williamsbot

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WilliamsEditionRulesTest {
    @Test fun courseAirbagPreservesFormationProtectionAndRejectsAlreadyCrossedBoundaries() {
        assertEquals(95.0,williamsCourseInitialProtection("LONG",95.0,90.0,100.0)!!,1e-10)
        assertEquals(97.0,williamsCourseInitialProtection("LONG",95.0,97.0,100.0)!!,1e-10)
        assertEquals(105.0,williamsCourseInitialProtection("SHORT",105.0,110.0,100.0)!!,1e-10)
        assertEquals(103.0,williamsCourseInitialProtection("SHORT",105.0,103.0,100.0)!!,1e-10)
        assertNull(williamsCourseInitialProtection("LONG",95.0,101.0,100.0))
        assertNull(williamsCourseInitialProtection("SHORT",105.0,99.0,100.0))
        assertNull(williamsCourseInitialProtection("LONG",Double.NaN,90.0,100.0))
        assertNull(williamsCourseInitialProtection("SHORT",105.0,Double.POSITIVE_INFINITY,100.0))
    }

    @Test fun ntdPdf43OldFractalPersistsUntilTriggerOrNewValidFractal() {
        val start=1_700_002_800_000L/WILLIAMS_H1_MS*WILLIAMS_H1_MS
        for(side in listOf("LONG","SHORT")) {
            fun mirror(b:WilliamsWaveBar)=if(side=="LONG") b else WilliamsWaveBar(b.t,220-b.o,220-b.l,220-b.h,220-b.c,b.v)
            val heights=List(45) {104.0}+listOf(104.0,105.0,110.0,106.0,105.0)+List(100) {104.0}
            val bars=heights.mapIndexed { i,h -> mirror(WilliamsWaveBar(start+i*WILLIAMS_H1_MS,101.0,h,99.0,101.0,100.0)) }
            fun detect(series:List<WilliamsWaveBar>):List<WilliamsEditionSetup> {
                val line=List(series.size) {if(side=="LONG") 100.0 else 120.0}
                val osc=List(series.size) {1.0}
                return williamsNtdSetups(side,series,line,line,line,osc,osc,.1).filter { it.kind=="FRACTAL" }
            }
            val old=detect(bars).single()
            assertEquals(49,old.knownIndex)
            assertEquals(0L,williamsH1SignalExpiry(bars[49].t,"FRACTAL",williamsNtdProfile))
            val extra=listOf(104.0,105.0,104.0,103.0).mapIndexed { i,h ->
                mirror(WilliamsWaveBar(bars.last().t+(i+1)*WILLIAMS_H1_MS,101.0,h,99.0,101.0,100.0)) }
            assertEquals(bars.size+extra.size-1,detect(bars+extra).single().knownIndex)
            val crossed=if(side=="LONG") bars.last().copy(h=111.0) else bars.last().copy(l=109.0)
            assertTrue(detect(bars.dropLast(1)+crossed).isEmpty())
        }
    }

    @Test fun allThreeBookSignalEnginesRequireUnbrokenGenuineCandleIntervals() {
        val bars=(1..50).map { i ->
            WilliamsWaveBar(i*WILLIAMS_H1_MS,100.0,103.0,97.0,100.0,10.0)
        }
        assertNull(williamsEditionSignalSeriesBlocker(bars,"1h"))
        assertEquals("BOOK_SIGNAL_CANDLE_GAP",
            williamsEditionSignalSeriesBlocker(bars.take(24)+bars.drop(25),"1h"))
        assertEquals("BOOK_SIGNAL_BAR_TIME_MISALIGNED",
            williamsEditionSignalSeriesBlocker(bars.mapIndexed { i,b ->
                if(i==10) b.copy(t=b.t+5_000L) else b
            },"1h"))
        assertEquals("BOOK_SIGNAL_OHLCV_UNVERIFIED",
            williamsEditionSignalSeriesBlocker(bars.take(43),"1h"))
        assertEquals("UNSUPPORTED_BOOK_SIGNAL_FRAME",
            williamsEditionSignalSeriesBlocker(bars,"unknown_frame"))
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

    @Test fun tc2OnlyOwnsNewRiskWhileNtdAndCourseRemainObservational() {
        assertTrue(williamsVerifiedNewRiskSource(williamsTc2Profile))
        assertFalse(williamsVerifiedNewRiskSource(williamsNtdProfile))
        assertFalse(williamsVerifiedNewRiskSource(williamsCourseProfile))
        assertFalse(williamsVerifiedNewRiskSource(williamsUnifiedProfile))
        // The same disabled sources must remain available for independent
        // analysis; scanning visibility is not financial mutation authority.
        assertTrue(williamsProfileAllowsSource(williamsUnifiedProfile,williamsNtdProfile))
        assertTrue(williamsProfileAllowsSource(williamsUnifiedProfile,williamsCourseProfile))
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
    @Test fun courseSquatStructuralStopIsUnaffectedByTheAirbagBookCorrection() {
        val bars = listOf(
            WilliamsWaveBar(3_600_000L, 95.0, 100.0, 90.0, 95.0, 100.0),
            WilliamsWaveBar(7_200_000L, 97.75, 100.5, 95.0, 97.75, 200.0)
        )
        val mirrored = bars.map { b ->
            WilliamsWaveBar(b.t, 300.0-b.o, 300.0-b.l, 300.0-b.h, 300.0-b.c, b.v)
        }
        assertEquals(94.9, requireNotNull(williamsCourseSquatSetup("LONG", bars, .1)).stop, 1e-9)
        assertEquals(205.1, requireNotNull(williamsCourseSquatSetup("SHORT", mirrored, .1)).stop, 1e-9)
    }

    private fun editionAoSignals(normalized: List<Double>, side: String): List<String> {
        val n = normalized.size
        val bars = (0 until n).map { i ->
            WilliamsWaveBar((i + 1) * 3_600_000L, 100.0, 101.0, 99.0, 100.0, 100.0)
        }
        val ao = if (side == "LONG") normalized else normalized.map { -it }
        val jaw = List(n) { 100.0 }
        val ac = List(n) { 0.0 }
        return williamsNtdSetups(side, bars, jaw, jaw, jaw, ao, ac, .1)
            .map { it.kind }.filter { it.startsWith("AO_") }
    }

    @Test fun ntdAoTwoPeaksRejectSkippedIntermediatePivotOnBothSides() {
        val values = List(45) { -1.0 } + listOf(-1.0, -5.0, -1.0, -2.0, -1.0, -3.0, -2.0)
        for (side in listOf("LONG", "SHORT")) {
            assertFalse(editionAoSignals(values, side).contains("AO_TWIN_PEAKS"))
        }
    }

    @Test fun ntdAoZeroCrossCannotBeMislabelledTwinPeaksOnBothSides() {
        val values = List(45) { -1.0 } + listOf(-1.0, -5.0, -1.0, -3.0, 1.0)
        for (side in listOf("LONG", "SHORT")) {
            assertEquals(listOf("AO_ZERO_LINE"), editionAoSignals(values, side))
        }
    }

    @Test fun ntdAoConsecutiveValidTwinPeaksRetainBothDirections() {
        val values = List(45) { -1.0 } + listOf(-1.0, -5.0, -1.0, -3.0, -2.0)
        for (side in listOf("LONG", "SHORT")) {
            assertEquals(listOf("AO_TWIN_PEAKS"), editionAoSignals(values, side))
        }
    }

    @Test fun ntdFirstFractalQualificationUsesActualBreakoutNotPivotForBothDirections() {
        val n=50
        val b=(0 until n).map { i ->
            val h=if(i==46)105.0 else if(i>=47)102.0 else 100.0
            WilliamsWaveBar((i+1)*WILLIAMS_H1_MS,99.0,h,98.0,99.0,100.0)
        }
        val ao=List(n){0.0};val ac=List(n){0.0}
        fun setups(side:String, bars:List<WilliamsWaveBar>, teeth:Double,
            jaw:Double, lips:Double) = williamsNtdSetups(
            side,bars,List(n){jaw},List(n){teeth},List(n){lips},ao,ac,.1)
                .filter { it.kind=="FRACTAL" }
        assertEquals(1,setups("LONG",b,105.05,99.0,99.0).size)
        assertTrue(setups("LONG",b,105.1,99.0,99.0).isEmpty())
        val inverted=b.map { it.copy(o=250.0-it.o,h=250.0-it.l,
            l=250.0-it.h,c=250.0-it.c) }
        assertEquals(1,setups("SHORT",inverted,144.95,151.0,151.0).size)
        assertTrue(setups("SHORT",inverted,144.9,151.0,151.0).isEmpty())
    }

    @Test fun fourBookExactOneTickSourcePricesAreDecimalAndRejectOffGrid() {
        assertEquals(79524.7,williamsBookExactPriceTick(79524.8,.1,false)!!,0.0)
        assertEquals(79524.9,williamsBookExactPriceTick(79524.8,.1,true)!!,0.0)
        assertEquals(2296.99,williamsBookExactPriceTick(2297.0,.01,false)!!,0.0)
        assertEquals(2297.01,williamsBookExactPriceTick(2297.0,.01,true)!!,0.0)
        assertEquals(.2061,williamsBookExactPriceTick(.2060,.0001,true)!!,0.0)
        assertNull(williamsBookExactPriceTick(99.005,.01,false))
        assertNull(williamsBookExactPriceTick(Double.NaN,.01,true))
        assertNull(williamsBookExactPriceTick(100.0,Double.NaN,false))
    }

    @Test fun courseSquatHistoricAdaH1PriceBarIsExactlyOneTickBothWays() {
        // Frozen PUBLIC OHLCV only; ADAUSDT book observer in 27-Oct ZIP.
        val series=listOf(
            WilliamsWaveBar(3_600_000L,.2050,.2055,.2047,.2052,920578.0),
            WilliamsWaveBar(7_200_000L,.2051,.2060,.2047,.2052,61576755.0)
        )
        val long= requireNotNull(williamsCourseSquatSetup("LONG",series,.0001))
        val short=requireNotNull(williamsCourseSquatSetup("SHORT",series,.0001))
        assertEquals(.2061,long.trigger,0.0)
        assertEquals(.2046,long.stop,0.0)
        assertEquals(.2046,short.trigger,0.0)
        assertEquals(.2061,short.stop,0.0)
        assertNull(williamsCourseSquatSetup("LONG",series,Double.NaN))
    }

    @Test fun ntdPdf63StrictZeroLineCrossExcludesZeroTouchLongAndShort() {
        // NTD PDF p.63 Fig5-19: positive -> negative, not zero -> negative.
        // Figure mirrors to LONG: negative -> positive, not zero -> positive.
        val touch=List(45){-1.0}+listOf(-1.0,0.0,1.0)
        val crossing=List(45){-1.0}+listOf(-1.0,-0.1,1.0)
        for(side in listOf("LONG","SHORT")) {
            assertFalse(editionAoSignals(touch,side).contains("AO_ZERO_LINE"))
            assertTrue(editionAoSignals(crossing,side).contains("AO_ZERO_LINE"))
        }
    }

    @Test fun bothOutsideFractalsRemainVisibleWithoutInventingTheirIntrabarOrder() {
        val highs=listOf(10.0,11.0,15.0,11.0,10.0);val lows=listOf(8.0,7.0,3.0,7.0,8.0)
        val bars=highs.indices.map { i -> WilliamsWaveBar((i+1)*3_600_000L,(highs[i]+lows[i])/2,highs[i],lows[i],(highs[i]+lows[i])/2,100.0) }
        assertEquals(setOf("HIGH","LOW"),confirmedWilliamsEditionFractals(bars).map { it.kind }.toSet())
        assertNull(williamsCourseHumpSetup("LONG",bars,.1))
    }

    @Test fun ntdBlueLightCausalStopImprovesOnlyWhileUnfilledAndSameColour() {
        // NTD PDF pp.64-66, 86-89. This does NOT amend a live exchange order.
        val high=listOf(110.0,108.0,106.0,104.0)
        val lows=listOf(100.0,101.0,102.0,103.0)
        val bars=high.indices.map { i ->
            WilliamsWaveBar((i+1)*WILLIAMS_H1_MS,(high[i]+lows[i])/2,high[i],lows[i],
                (high[i]+lows[i])/2,100.0)
        }
        val rising=listOf(-1.0,1.0,2.0,3.0)
        val long=williamsNtdBlueLight("LONG",bars,rising,0,110.1,.1)
        assertEquals(104.1,long?.trigger ?: 0.0,0.0)
        assertEquals(3,long?.sourceIndex)
        val shortBars=bars.map { it.copy(o=200-it.o,h=200-it.l,
            l=200-it.h,c=200-it.c) }
        val short=williamsNtdBlueLight("SHORT",shortBars,rising.map {-it},0,89.9,.1)
        assertEquals(95.9,short?.trigger ?: 0.0,0.0)
        assertEquals(3,short?.sourceIndex)
        // After the first improvement to 108.1, the third bar touched 108.1:
        // that order is historical, never revive it as a new 106.1 proposal.
        val touched=bars.toMutableList();touched[2]=touched[2].copy(h=108.1)
        assertNull(williamsNtdBlueLight("LONG",touched,rising,0,110.1,.1))
        assertNull(williamsNtdBlueLight("LONG",bars,listOf(-1.0,1.0,.5,2.0),0,110.1,.1))
        assertNull(williamsNtdBlueLight("LONG",bars,listOf(-1.0,1.0,1.0,2.0),0,110.1,.1))
        assertNull(williamsNtdBlueLight("LONG",bars,rising,0,110.1,Double.NaN))
    }

    @Test fun ntdAoAndAcPersistOriginalFormationButImproveUnfilledPriceLevel() {
        val n=49
        val highs=MutableList(n){110.0}; val lows=MutableList(n){100.0}
        for(i in 46 until n) { highs[i]=110.0-(i-45)*2.0; lows[i]=100.0+(i-45) }
        val bars=(0 until n).map { i ->
            val lo=lows[i];val hi=highs[i]
            WilliamsWaveBar((i+1)*WILLIAMS_H1_MS,(hi+lo)/2,hi,lo,(hi+lo)/2,100.0)
        }
        val ao=MutableList(n){-1.0};ao[45]=1.0;ao[46]=2.0;ao[47]=3.0;ao[48]=4.0
        val ac=MutableList(n){-1.0};ac[44]=-2.0;ac[45]=-1.0
        ac[46]=1.0;ac[47]=2.0;ac[48]=3.0
        val jaw=List(n){95.0};val teeth=List(n){95.0};val lips=List(n){95.0}
        fun detect(side:String, bs:List<WilliamsWaveBar>, a:List<Double>, c:List<Double>) =
            williamsNtdSetups(side,bs,jaw,teeth,lips,a,c,.1,exitMode=true)
        val long=detect("LONG",bars,ao,ac)
        val oldAo=long.single { it.kind=="AO_ZERO_LINE" && it.knownIndex==45 }
        assertEquals(104.1,oldAo.trigger,0.0)
        assertEquals(45,oldAo.context.getInt("formation_index"))
        assertEquals(48,oldAo.context.getInt("level_source_index"))
        val oldAc=long.single { it.kind=="AC_SIGNAL" && it.knownIndex==46 }
        assertEquals(104.1,oldAc.trigger,0.0)
        assertEquals(48,oldAc.context.getInt("level_source_index"))
        val mirror=bars.map { it.copy(o=200-it.o,h=200-it.l,l=200-it.h,c=200-it.c) }
        // Mirrored AO/AC is SHORT: precise symmetric lower sell STOP.
        val short=detect("SHORT",mirror,ao.map {-it},ac.map {-it})
        assertEquals(95.9,short.single { it.kind=="AO_ZERO_LINE" && it.knownIndex==45 }.trigger,0.0)
        assertEquals(95.9,short.single { it.kind=="AC_SIGNAL" && it.knownIndex==46 }.trigger,0.0)
    }


    @Test fun ntdPdf117FiveSameColoursArmsStickyOneTickExitBothWays() {
        // Fixture for NTD 9-5: 5 same-colour AO+AC bars after entry,
        // and trailing under/above each following CLOSED price bar.
        val hour=3_600_000L
        val lows=listOf(100.0,100.0,100.0,100.0,100.0,100.0,97.0,105.0)
        val highs=listOf(110.0,111.0,112.0,113.0,114.0,115.0,118.0,119.0)
        val original=lows.indices.map {i->
            val price=(highs[i]+lows[i])/2
            WilliamsWaveBar(i*hour,price,highs[i],lows[i],price,100.0)
        }
        for(side in listOf("LONG","SHORT")) {
            val mirror=side=="SHORT"
            val bars=if(mirror) original.map {it.copy(o=250-it.o,h=250-it.l,
                l=250-it.h,c=250-it.c)} else original
            // Both oscillators CHANGE COLOUR after the fifth bar. The book
            // profit stop remains armed and trails closed prices anyway.
            val ao=bars.indices.map { i ->
                val v=if(i<6) i.toDouble() else 5.0-(i-5)*.5
                if(mirror) -v else v
            }
            val ac=ao.map {it*.5}
            val line=List(bars.size){if(mirror) 200.0 else 80.0}
            fun plan(count:Int,state:JSONObject)=williamsNtdManagementPlan(
                side,bars.take(count),line.take(count),line.take(count),
                ao.take(count),ac.take(count),.1,hour,state)
            assertNull(plan(5,JSONObject()).stop)
            val five=plan(6,JSONObject())
            assertTrue(five.state.optLong("zone_active_at",0L)>0)
            assertEquals(if(mirror)150.1 else 99.9,five.stop!!,0.0)
            assertEquals(5*hour,five.state.getLong("zone_book_stop_source_time_ms"))
            val dip=plan(7,five.state)
            // Bar 6 would LOOSEN the stop; all existing protection stays.
            assertEquals(if(mirror)150.1 else 99.9,dip.stop!!,0.0)
            assertEquals(5*hour,dip.state.getLong("zone_book_stop_source_time_ms"))
            val improvement=plan(8,dip.state)
            assertEquals(if(mirror)145.1 else 104.9,improvement.stop!!,0.0)
            assertEquals(7*hour,improvement.state.getLong("zone_book_stop_source_time_ms"))
            assertFalse(improvement.state.getBoolean("zone_book_stop_authorized_order"))
        }
    }

    @Test fun ntdBookFiveColourStopCannotUseUnknownPriceTick() {
        val hour=3_600_000L
        val bars=(0..5).map {i->WilliamsWaveBar(i*hour,110.0,112.0,100.0,110.0,100.0)}
        val ao=(0..5).map {it.toDouble()}
        val lines=List(6){80.0}
        val m=williamsNtdManagementPlan("LONG",bars,lines,lines,ao,ao,
            Double.NaN,hour,JSONObject())
        assertNull(m.stop)
        assertFalse(m.state.has("zone_active_at"))
    }

    @Test fun ntdPdf116TeethWickIsNotACloseOnlyExitForLongOrShort() {
        val hour=3_600_000L
        val base=(0..5).map {i->WilliamsWaveBar(i*hour,101.0,103.0,98.0,101.0,100.0)}
        for(side in listOf("LONG","SHORT")) {
            val mirror=side=="SHORT"
            val bars=if(mirror) base.map { it.copy(o=200-it.o,h=200-it.l,
                l=200-it.h,c=200-it.c) } else base
            val alligator=List(6){100.0}
            val ao=List(6){0.0};val ac=List(6){0.0}
            fun plan(rows:List<WilliamsWaveBar>)=williamsNtdManagementPlan(
                side,rows,alligator,alligator,ao,ac,.1,hour,JSONObject())
            // The wick crosses Teeth but the closed price stays on the
            // position's safe side: NTD chapter 9 says CLOSE ONLY.
            assertEquals("",plan(bars).exitReason)
            val last=bars.last()
            val closedAcross=bars.dropLast(1)+last.copy(c=if(mirror)101.0 else 99.0)
            assertEquals("TEETH_CLOSE_EXIT",plan(closedAcross).exitReason)
        }
    }
}
