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

    @Test fun courseRemainsObservationOnlyWhileTc2AndNtdOwnTheirExecution() {
        assertTrue(williamsVerifiedNewRiskSource(williamsTc2Profile))
        assertTrue(williamsVerifiedNewRiskSource(williamsNtdProfile))
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

    @Test fun bothOutsideFractalsRemainVisibleWithoutInventingTheirIntrabarOrder() {
        val highs=listOf(10.0,11.0,15.0,11.0,10.0);val lows=listOf(8.0,7.0,3.0,7.0,8.0)
        val bars=highs.indices.map { i -> WilliamsWaveBar((i+1)*3_600_000L,(highs[i]+lows[i])/2,highs[i],lows[i],(highs[i]+lows[i])/2,100.0) }
        assertEquals(setOf("HIGH","LOW"),confirmedWilliamsEditionFractals(bars).map { it.kind }.toSet())
        assertNull(williamsCourseHumpSetup("LONG",bars,.1))
    }
}
