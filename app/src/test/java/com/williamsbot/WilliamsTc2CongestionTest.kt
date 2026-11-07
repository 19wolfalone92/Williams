package com.williamsbot

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class WilliamsTc2CongestionTest {
    private val h=3_600_000L
    private fun oscillating():List<WilliamsWaveBar> =(0 until 120).map { i ->
        val c=740.0+1.6*sin(i*PI/3.0)
        WilliamsWaveBar((i+1)*h,c-.1,c+.4,c-.4,c,100.0)
    }
    private fun trending():List<WilliamsWaveBar> =(0 until 120).map { i ->
        val c=700.0+i*.7
        WilliamsWaveBar((i+1)*h,c-.1,c+.4,c-.4,c,100.0)
    }

    @Test fun ordinarySidewaysMarketIsNotMistakenForComplexCorrection() {
        val b=oscillating()
        val now=(b.last().t+h)
        val inside=williamsTc2CongestionEvidence(b,now,"SHORT_FRACTAL","SHORT",740.0)
        assertEquals("ORDINARY_CONGESTION_OR_CORRECTION_NOT_COMPLEX",inside.getString("state"))
        assertFalse(inside.getBoolean("blocked"))
        assertEquals("ONLY_CONGESTION_NOT_COMPLEX_NO_VETO",inside.getString("reason_code"))
        assertEquals(0,inside.getInt("complex_structure_count"))
        assertTrue(inside.getInt("teeth_close_crossings")>=3)
        assertTrue(inside.getInt("ao_zero_crossings")>=2)
        assertFalse(inside.getBoolean("can_place_orders"))
        assertFalse(inside.getBoolean("can_close"))
        val longInside=williamsTc2CongestionEvidence(b,now,"SUPER_AO","LONG",740.0)
        assertFalse(longInside.getBoolean("blocked"))
        val escapeUp=williamsTc2CongestionEvidence(b,now,"FRACTAL","LONG",744.0)
        val escapeDown=williamsTc2CongestionEvidence(b,now,"SHORT_FRACTAL","SHORT",736.0)
        assertFalse(escapeUp.getBoolean("blocked"))
        assertFalse(escapeDown.getBoolean("blocked"))
        assertEquals("ONLY_CONGESTION_NOT_COMPLEX_NO_VETO",escapeDown.getString("reason_code"))
        // Valid countertrend WM1 is still assessed by the book's strict AO /
        // mouth angulation; range classification cannot invent a WM1 veto.
        assertFalse(williamsTc2CongestionEvidence(b,now,"SHORT_REVERSAL","SHORT",740.0)
            .getBoolean("blocked"))
        assertNull(williamsTc2CongestionEntryBlocker(williamsNtdProfile,"FRACTAL","LONG",740.0,b,now))
    }

    @Test fun confirmedTrendOrMissingHistoryNeverClaimCertainCorrection() {
        val b=trending();val now=b.last().t+h
        val result=williamsTc2CongestionEvidence(b,now,"SHORT_FRACTAL","SHORT",760.0)
        assertFalse(result.getBoolean("blocked"))
        assertEquals("NO_COMPLEX_CORRECTION_EVIDENCE",result.getString("state"))
        val missing=williamsTc2CongestionEvidence(b.takeLast(35),now,"SHORT_FRACTAL","SHORT",760.0)
        assertEquals("HISTORY_UNVERIFIED",missing.getString("state"))
        assertFalse(missing.getBoolean("blocked"))
        val future=b+WilliamsWaveBar(now,1.0,999.0,.1,1.0)
        assertEquals("HISTORY_UNVERIFIED",
            williamsTc2CongestionEvidence(future,now,"SHORT_FRACTAL","SHORT",760.0).getString("state"))
        assertFalse(williamsTc2CongestionEvidence(b,now,"SHORT_FRACTAL","SHORT",760.0)
            .getBoolean("can_change_stops"))
    }


    private fun triangularBars():List<WilliamsWaveBar> {
        val highAt=mapOf(94 to 130.0,102 to 125.0,110 to 120.0)
        val lowAt=mapOf(98 to 100.0,106 to 105.0,114 to 110.0)
        return (0 until 120).map { i ->
            val hi=highAt[i] ?: 116.0
            val lo=lowAt[i] ?: 114.0
            WilliamsWaveBar((i+1)*h,115.0,hi,lo,115.0,100.0)
        }
    }

    @Test fun missingOrStaleH1CannotSilentlyBypassNewTc2EntryRiskScreen() {
        val full=trending()
        val now=full.last().t+h
        val missing=full.takeLast(35)
        val missingReport=williamsTc2CongestionEvidence(
            missing,now,"FRACTAL","LONG",1000.0)
        assertEquals("HISTORY_UNVERIFIED",missingReport.getString("state"))
        // A missing chart is NOT evidence that a complex correction exists:
        assertFalse(missingReport.getBoolean("blocked"))
        // But a new entry still MUST NOT be admitted on that absence.
        assertEquals(WILLIAMS_TC2_CONGESTION_UNVERIFIED,
            williamsTc2CongestionAdmissionBlocker(missingReport))
        for(side in listOf("LONG","SHORT")) {
            assertEquals(WILLIAMS_TC2_CONGESTION_UNVERIFIED,
                williamsTc2CongestionEntryBlocker(williamsTc2Profile,
                    if(side=="LONG") "FRACTAL" else "SHORT_FRACTAL",
                    side,800.0,missing,now))
        }
        assertEquals(WILLIAMS_TC2_CONGESTION_UNVERIFIED,
            williamsTc2CongestionEntryBlocker(williamsTc2Profile,
                "REVERSAL","LONG",900.0,full,now+13*h))
        assertEquals(WILLIAMS_TC2_CONGESTION_UNVERIFIED,
            williamsTc2CongestionEntryBlocker(williamsTc2Profile,
                "SUPER_AO","LONG",900.0,
                full+WilliamsWaveBar(now,100.0,200.0,90.0,100.0),now))
        // TC1, NTD and Course stay OBSERVE, NEVER acquire trading authority
        // from this extra TC2 policy.
        assertNull(williamsTc2CongestionEntryBlocker(
            williamsNtdProfile,"FRACTAL","LONG",900.0,missing,now))
        assertNull(williamsTc2CongestionAdmissionBlocker(JSONObject()
            .put("state","NO_COMPLEX_CORRECTION_EVIDENCE")
            .put("blocked",false)))
        assertTrue(russianExecutionBlocker(WILLIAMS_TC2_CONGESTION_UNVERIFIED)
            .contains("закрытых H1"))
    }

    @Test fun activeComplexCorrectionStillWinsOverOtherwiseVerifiedTradeSignal() {
        val bars=triangularBars()
        val now=bars.last().t+h
        val report=williamsTc2CongestionEvidence(bars,now,"FRACTAL","LONG",115.0)
        assertEquals(WILLIAMS_TC2_CONGESTION_BLOCKER,
            williamsTc2CongestionAdmissionBlocker(report))
        assertEquals(WILLIAMS_TC2_CONGESTION_BLOCKER,
            williamsTc2CongestionEntryBlocker(williamsTc2Profile,
                "FRACTAL","LONG",115.0,bars,now))
        val outside=williamsTc2CongestionEvidence(bars,now,"FRACTAL","LONG",131.0)
        assertNull(williamsTc2CongestionAdmissionBlocker(outside))
    }

    @Test fun confirmedRecentTriangleOnlyBlocksEntriesInsideActualComplexCorrection() {
        val bars=triangularBars()
        val now=bars.last().t+h
        val anchors=confirmedWilliamsWaveAnchors(bars,h)
        val actual=williamsTc2RecentComplexPriceCandidates(anchors,now)
        assertTrue(actual.any { it.pattern=="CONTRACTING_TRIANGLE_ABCDE_CANDIDATE" })
        for(family in listOf("REVERSAL","SUPER_AO","FRACTAL")) {
            val report=williamsTc2CongestionEvidence(bars,now,family,"LONG",115.0)
            assertEquals(family,"COMPLEX_CORRECTION_CANDIDATE",report.getString("state"))
            assertTrue(family,report.getBoolean("blocked"))
            assertEquals(family,WILLIAMS_TC2_CONGESTION_BLOCKER,
                report.getString("reason_code"))
            assertTrue(report.getString("explanation_ru").contains("Новая заявка TC2 не разрешена"))
            assertTrue(report.getString("explanation_ru").contains("Диапазон:"))
            assertTrue(report.getString("explanation_ru").contains("треугольник"))
            assertTrue(report.has("blocking_complex_structure"))
            assertTrue(report.getJSONObject("blocking_complex_structure").getLong("known_at_ms")<=now)
            assertTrue(williamsRuRuntimeMessage(WILLIAMS_TC2_CONGESTION_BLOCKER)
                .contains("дополнительный фильтр риска"))
            assertFalse(report.getBoolean("can_place_orders"))
            assertFalse(report.getBoolean("can_close"))
            assertFalse(report.getBoolean("can_change_stops"))
            assertNotNull(williamsTc2CongestionEntryBlocker(
                williamsTc2Profile,family,"LONG",115.0,bars,now))
        }
        // Still trade a valid breakout beyond the actual complex pattern,
        // subject to the normal Wise-Man, Teeth, risk and Binance checks.
        val up=williamsTc2CongestionEvidence(bars,now,"FRACTAL","LONG",131.0)
        val down=williamsTc2CongestionEvidence(bars,now,"SHORT_FRACTAL","SHORT",99.0)
        assertFalse(up.getBoolean("blocked"))
        assertFalse(down.getBoolean("blocked"))
        val old=williamsTc2CongestionEvidence(bars,now+13*h,"FRACTAL","LONG",115.0)
        assertFalse(old.getBoolean("blocked"))
    }

    @Test fun triangleNeverBecomesKnownBeforeTwoRightH1BarsHaveClosed() {
        val source=triangularBars()
        val notYet=source.take(116)
        val early=williamsTc2CongestionEvidence(notYet,notYet.last().t+h,
            "FRACTAL","LONG",115.0)
        assertFalse(early.toString(),early.getBoolean("blocked"))
        val confirmed=source.take(117)
        val now=confirmed.last().t+h
        val later=williamsTc2CongestionEvidence(confirmed,now,
            "FRACTAL","LONG",115.0)
        assertTrue(later.toString(),later.getBoolean("blocked"))
        assertTrue(later.getJSONArray("complex_structures").let { arr ->
            (0 until arr.length()).any { i ->
                arr.getJSONObject(i).getLong("known_at_ms")<=now
            }
        })
        // A forming next bar with an extreme high is not allowed into
        // the confirmation/reclassification prefix.
        val future=confirmed+WilliamsWaveBar(now,115.0,1000.0,1.0,115.0,100.0)
        assertEquals("HISTORY_UNVERIFIED",williamsTc2CongestionEvidence(
            future,now,"FRACTAL","LONG",115.0).getString("state"))
    }

    @Test fun olderActiveCorrectionNotDiscardedByFortyEightNewerCandidates() {
        // Former failure: a source candidate older than 48 newer labels
        // was dropped before its current price state was inspected.
        // These 80 verified alternating pivots provide many triangles.
        val pivots=(0 until 80).map { i ->
            val high=i%2==0
            val step=i/2
            WilliamsWaveAnchor(if(high) "HIGH" else "LOW",
                if(high) 200.0-step*0.1 else 100.0+step*0.1,
                i, i*h, (i+3L)*h)
        }
        val candidates=williamsTc2RecentComplexPriceCandidates(pivots,100*h)
        assertTrue("Every historically known candidate must be classified",
            candidates.count { it.pattern=="CONTRACTING_TRIANGLE_ABCDE_CANDIDATE" }>48)
        val old=candidates.filter { it.pattern=="CONTRACTING_TRIANGLE_ABCDE_CANDIDATE" }
            .minByOrNull { it.knownAt }!!
        assertTrue(candidates.indexOf(old)>=48)
        val samples=(0 until 70).map { i ->
            org.json.JSONObject().put("range_resolved",true)
                .put("crosses_entry",false).put("id","RESOLVED_"+i)
        }+org.json.JSONObject().put("range_resolved",false)
            .put("crosses_entry",true).put("id","OLDEST_STILL_ACTIVE")
        val shown=williamsTc2ComplexStructureDisplay(samples)
        assertTrue(williamsTc2ComplexEntryBlocked(samples))
        assertTrue(williamsTc2ComplexPatternRu("EXPANDED_FLAT_ABC_CANDIDATE").contains("плоская"))
        assertTrue(williamsTc2ComplexPatternRu("UNKNOWN").contains("не установлен"))
        assertEquals(48,shown.size)
        assertEquals("OLDEST_STILL_ACTIVE",shown.first().getString("id"))
        assertTrue(shown.any { it.optBoolean("crosses_entry") })
    }

    @Test fun displayContainsNoMoreThanCapacityWithManyActiveStructures() {
        val all=(0 until 95).map { i ->
            org.json.JSONObject().put("crosses_entry",i<50)
                .put("range_resolved",i>=70)
        }
        val selected=williamsTc2ComplexStructureDisplay(all)
        assertEquals(48,selected.size)
        assertTrue(selected.all { it.optBoolean("crosses_entry") })
        assertEquals(0,williamsTc2ComplexStructureDisplay(all,0).size)
        assertTrue(williamsTc2ComplexEntryBlocked(all))
        val onlyResolved=(0 until 95).map {
            org.json.JSONObject().put("crosses_entry",false).put("range_resolved",true)
        }
        assertFalse(williamsTc2ComplexEntryBlocked(onlyResolved))
    }

    @Test fun confirmedComplexPatternDoesNotExpireAfterTwelveMoreClosedHours() {
        val initial=triangularBars()
        val later=(1..18).map { hour ->
            WilliamsWaveBar(initial.last().t+hour*h,115.0,116.0,114.0,115.0,100.0)
        }
        val history=initial+later
        val now=history.last().t+h
        for(side in listOf("LONG","SHORT")) {
            val report=williamsTc2CongestionEvidence(history,now,
                if(side=="LONG") "FRACTAL" else "SHORT_FRACTAL",side,115.0)
            assertTrue(report.toString(),report.getBoolean("blocked"))
            assertEquals("COMPLEX_CORRECTION_CANDIDATE",report.getString("state"))
            assertTrue(report.getInt("active_complex_structure_count")>0)
            assertEquals("CONFIRMED_PRICE_PIVOT_PREFIXES_UNTIL_CLOSED_H1_BREAKOUT",
                report.getString("complex_candidate_retention"))
        }
        // A real *closed* H1 exit invalidates the previous range for both
        // directions. The historical correction is recorded, not erased.
        val breakout=history+WilliamsWaveBar(now,115.0,138.0,114.0,135.0,100.0)
        val completeAt=now+h
        for(side in listOf("LONG","SHORT")) {
            val report=williamsTc2CongestionEvidence(breakout,completeAt,
                if(side=="LONG") "FRACTAL" else "SHORT_FRACTAL",side,115.0)
            assertFalse(report.toString(),report.getBoolean("blocked"))
            assertEquals("COMPLEX_CORRECTION_RESOLVED",report.getString("state"))
            assertTrue(report.getJSONArray("complex_structures").let { arr ->
                (0 until arr.length()).any { i ->
                    arr.getJSONObject(i).getBoolean("range_resolved") &&
                        arr.getJSONObject(i).getString("resolved_direction")=="UP"
                }
            })
        }
    }

    @Test fun alreadyConfirmedTriangleSurvivesAnExtraInnerPivot() {
        val seed=triangularBars()
        val next=(1..12).map { i ->
            val hi=if(i==4) 117.0 else 116.0
            val lo=if(i==8) 113.0 else 114.0
            WilliamsWaveBar(seed.last().t+i*h,115.0,hi,lo,115.0,100.0)
        }
        val bars=seed+next
        val now=bars.last().t+h
        val report=williamsTc2CongestionEvidence(bars,now,"FRACTAL","LONG",115.0)
        assertTrue(report.toString(),report.getBoolean("blocked"))
        assertTrue(report.getJSONArray("complex_structures").let { arr ->
            (0 until arr.length()).any { i ->
                val p=arr.getJSONObject(i)
                p.getString("pattern")=="CONTRACTING_TRIANGLE_ABCDE_CANDIDATE" &&
                    !p.getBoolean("range_resolved")
            }
        })
    }

    @Test fun completedH1CloseBeyondTriangleEndsTheOldComplexRangeVeto() {
        // TC1 ch7 explains triangle corrections; TC2 ch11 fig 11.7
        // warns against trading stale internal fractals. A confirmed
        // CLOSED H1 escape is different from an unresolved triangle.
        val old=triangularBars()
        val baseTime=old.last().t+h
        val baseline=williamsTc2CongestionEvidence(old,baseTime,"FRACTAL","LONG",115.0)
        assertTrue(baseline.toString(),baseline.getBoolean("blocked"))
        val escaped=old.toMutableList()
        val previous=escaped.last()
        escaped[escaped.lastIndex]=previous.copy(h=137.0,c=134.0)
        val report=williamsTc2CongestionEvidence(escaped,baseTime,"FRACTAL","LONG",115.0)
        assertFalse(report.toString(),report.getBoolean("blocked"))
        assertEquals("COMPLEX_CORRECTION_RESOLVED",report.getString("state"))
        assertEquals("COMPLEX_RANGE_RESOLVED_BY_CLOSED_H1",report.getString("reason_code"))
        assertEquals(0,report.getInt("active_complex_structure_count"))
        assertTrue(report.getString("explanation_ru").contains("закрытая свеча H1"))
        assertFalse(report.getBoolean("confidence_calibrated"))
        assertTrue(report.getJSONArray("complex_structures").let { entries ->
            (0 until entries.length()).any { entries.getJSONObject(it).optBoolean("range_resolved") &&
                entries.getJSONObject(it).getString("resolved_direction")=="UP" &&
                entries.getJSONObject(it).getLong("first_closed_breakout_at_ms")==baseTime }
        })
        // This is only removal of a congestion veto; normal TC2 signal
        // validation and stop protection must still authorize any order.
        assertFalse(report.getBoolean("can_place_orders"))
    }

    @Test fun wickBeyondBoundaryWithoutClosingOutsideDoesNotResolveTriangle() {
        val old=triangularBars()
        val spike=old.toMutableList()
        val last=spike.last()
        spike[spike.lastIndex]=last.copy(h=137.0,c=115.0)
        val now=spike.last().t+h
        val report=williamsTc2CongestionEvidence(spike,now,"SHORT_SUPER_AO","SHORT",115.0)
        assertEquals(report.toString(),"COMPLEX_CORRECTION_CANDIDATE",report.getString("state"))
        assertTrue(report.toString(),report.getBoolean("blocked"))
        assertTrue(report.getInt("active_complex_structure_count")>0)
        assertTrue(report.getJSONArray("complex_structures").let { entries ->
            (0 until entries.length()).any {
                !entries.getJSONObject(it).optBoolean("range_resolved")
            }
        })
    }

    private fun completedFlatBars(expanded:Boolean):List<WilliamsWaveBar> {
        // Actual sequential H1 price geometry: W1/W2/W3/W4/W5 then A/B/C,
        // with each pivot having two strict bars to left and right.
        val knots=sortedMapOf(
            0 to 115.0,70 to 100.0,76 to 120.0,82 to 110.0,
            88 to 145.0,94 to 125.0,100 to 155.0,106 to 135.0,
            112 to (if(expanded) 158.0 else 153.0),
            118 to (if(expanded) 128.0 else 124.0),122 to 134.0
        )
        fun price(i:Int):Double {
            val before=knots.keys.last { it<=i }
            val after=knots.keys.first { it>=i }
            val start=knots.getValue(before)
            return if(before==after) start else start+
                (knots.getValue(after)-start)*(i-before)/(after-before)
        }
        return (0..122).map { i ->
            val p=price(i)
            WilliamsWaveBar((i+1)*h,p,p+.1,p-.1,p,100.0)
        }
    }

    @Test fun tc1RegularAndExpandedFlatAreComplexCorrectionNotSimpleZigzag() {
        for(expanded in listOf(false,true)) {
            val bars=completedFlatBars(expanded)
            val now=bars.last().t+h
            val actual=confirmedWilliamsWaveAnchors(bars,h)
            val expected=if(expanded) "EXPANDED_FLAT_ABC_CANDIDATE"
                else "REGULAR_FLAT_ABC_CANDIDATE"
            assertTrue(expected+" "+actual.map { it.kind+":"+it.price },
                williamsTc2RecentComplexPriceCandidates(actual,now).any { it.pattern==expected })
            val inside=williamsTc2CongestionEvidence(bars,now,"FRACTAL","LONG",140.0)
            assertEquals(inside.toString(),"COMPLEX_CORRECTION_CANDIDATE",inside.getString("state"))
            assertTrue(inside.toString(),inside.getBoolean("blocked"))
            assertTrue(inside.getString("explanation_ru").contains("сложной коррекции"))
            assertFalse(inside.getBoolean("can_place_orders"))
            // Breakout outside the actual completed structure is not a
            // trade by itself; it merely removes this additional risk veto.
            val breakout=williamsTc2CongestionEvidence(bars,now,"FRACTAL","LONG",170.0)
            assertFalse(breakout.getBoolean("blocked"))
        }
    }

    @Test fun simpleZigzagAndUnconfirmedFlatRemainTradableButCompletedFlatIsComplex() {
        fun anchors(values:List<Double>):List<WilliamsWaveAnchor> = values.mapIndexed { i,p ->
            WilliamsWaveAnchor(if(i%2==0) "LOW" else "HIGH",
                p,i,(i+1)*h,(i+4)*h)
        }
        val zigzag=anchors(listOf(100.0,120.0,110.0,145.0,125.0,155.0,
            135.0,143.0,120.0))
        val zigzagNames=detectWilliamsComplexWave(zigzag,"UP",16*h).map { it.pattern }
        assertTrue("ZIGZAG_ABC_CANDIDATE" in zigzagNames)
        assertEquals(0,williamsTc2RecentComplexPriceCandidates(zigzag,16*h).size)
        val expandedFlat=anchors(listOf(100.0,120.0,110.0,145.0,125.0,155.0,
            135.0,158.0,128.0))
        assertTrue("EXPANDED_FLAT_ABC_CANDIDATE" in
            detectWilliamsComplexWave(expandedFlat,"UP",16*h).map { it.pattern })
        assertTrue(williamsTc2RecentComplexPriceCandidates(expandedFlat,16*h).any {
            it.pattern=="EXPANDED_FLAT_ABC_CANDIDATE" })
        val regularFlat=anchors(listOf(100.0,120.0,110.0,145.0,125.0,155.0,
            135.0,153.0,125.0))
        assertTrue(williamsTc2RecentComplexPriceCandidates(regularFlat,16*h).any {
            it.pattern=="REGULAR_FLAT_ABC_CANDIDATE" })
        val running=anchors(listOf(100.0,120.0,110.0,145.0,125.0,155.0,
            135.0,158.0,140.0))
        assertTrue(detectWilliamsComplexWave(running,"UP",16*h).any {
            it.pattern=="RUNNING_FLAT_ABC_CANDIDATE" })
        assertTrue(williamsTc2RecentComplexPriceCandidates(running,16*h).isEmpty())
        val unresolved=anchors(listOf(100.0,120.0,110.0,145.0,125.0,155.0,
            135.0,143.0,120.0,132.0,116.0,125.0))
        val names=detectWilliamsComplexWave(unresolved,"UP",16*h).map { it.pattern }
        assertTrue("W_X_Y_COMBINATION_UNRESOLVED" in names)
        assertTrue("W_X_Y_X_Z_COMBINATION_UNRESOLVED" in names)
        assertEquals(0,williamsTc2RecentComplexPriceCandidates(unresolved,16*h).size)
        val invalidTime=triangularBars().mapIndexed { i,b ->
            if(i==114) b.copy(t=b.t+40*h) else b
        }
        assertEquals("HISTORY_UNVERIFIED",williamsTc2CongestionEvidence(
            invalidTime,120*h,"REVERSAL","LONG",115.0).getString("state"))
    }

    @Test fun onlyVerifiedUntriggeredH1EntriesEligibleForCongestionCancellation() {
        for(state in listOf("NEW","ACCEPTED","PENDING","WORKING","new")) {
            assertTrue(williamsTc2PendingCongestionRecheckEligible(
                "ENTRY",state,"W2H_BNBUSDT_test"))
            assertTrue(williamsTc2PendingCongestionRecheckEligible(
                "ADD_ON",state,"W2A_BNBUSDT_test"))
        }
        for(state in listOf("UNKNOWN","TRIGGERING","TRIGGERED","PARTIALLY_FILLED",
            "FILLED","CANCELED","REJECTED","")) {
            assertFalse(state,williamsTc2PendingCongestionRecheckEligible(
                "ENTRY",state,"W2H_BNBUSDT_test"))
            assertFalse(state,williamsTc2PendingCongestionRecheckEligible(
                "ADD_ON",state,"W2A_BNBUSDT_test"))
        }
        assertFalse(williamsTc2PendingCongestionRecheckEligible(
            "PROTECTIVE_STOP","NEW","W2H_BNBUSDT_test"))
        assertFalse(williamsTc2PendingCongestionRecheckEligible(
            "ENTRY","NEW","EXTERNAL_ORDER"))
        assertFalse(williamsTc2PendingCongestionRecheckEligible(
            "ENTRY","NEW","W2T_STOP_REPLACEMENT"))
        assertFalse(williamsTc2PendingCongestionRecheckEligible(
            "ENTRY","NEW","W2H_BNBUSDT_test",knownFill=true))
        assertFalse(williamsTc2PendingCongestionRecheckEligible(
            "ADD_ON","WORKING","W2A_BNBUSDT_test",knownFill=true))
    }

    @Test fun rollingTenSymbolH1PrefixReplayDoesNotInventComplexVeto() {
        // Historical causality check: each prefix is exactly the CLOSED H1
        // data available at that hour. The test does not predict future PnL.
        val fixture=org.json.JSONObject(requireNotNull(javaClass.getResource(
            "/williams_tc2_public_h1_congestion_20261027.json")).readText())
        val symbols=fixture.getJSONArray("symbols")
        var examined=0
        var vetoes=0
        for(i in 0 until symbols.length()) {
            val raw=symbols.getJSONObject(i)
            val candles=raw.getJSONArray("bars")
            val history=(0 until candles.length()).map { j ->
                val a=candles.getJSONArray(j)
                WilliamsWaveBar(a.getLong(0),a.getDouble(1),a.getDouble(2),
                    a.getDouble(3),a.getDouble(4),a.getDouble(5))
            }
            for(length in 60..history.size step 6) {
                val prefix=history.take(length)
                val now=prefix.last().t+h
                for(side in listOf("LONG","SHORT")) {
                    val signal=if(side=="LONG") "FRACTAL" else "SHORT_FRACTAL"
                    val report=williamsTc2CongestionEvidence(
                        prefix,now,signal,side,prefix.last().c)
                    examined++
                    assertEquals("CLOSED_CONTIGUOUS_H1_CANDLES",report.getString("source"))
                    assertFalse(report.getBoolean("can_place_orders"))
                    assertFalse(report.getBoolean("can_close"))
                    assertFalse(report.getBoolean("can_change_stops"))
                    if(report.getBoolean("blocked")) {
                        vetoes++
                        assertEquals(WILLIAMS_TC2_CONGESTION_BLOCKER,report.getString("reason_code"))
                        assertTrue(report.getInt("active_complex_structure_count")>0)
                        val structures=report.getJSONArray("complex_structures")
                        assertTrue((0 until structures.length()).any { j ->
                            val item=structures.getJSONObject(j)
                            item.getBoolean("crosses_entry") && !item.getBoolean("range_resolved") &&
                                item.getLong("known_at_ms")<=now
                        })
                    }
                }
            }
        }
        assertEquals(10,symbols.length())
        assertTrue(examined>=300)
        assertTrue(vetoes<=examined)
    }

    @Test fun frozenTenSymbolBinanceH1GeometryIsCausalAndTransparent() {
        // PUBLIC Binance Futures OHLC only, not the operator's signed Demo
        // positions and not an independent Elliott-wave expert verdict.
        val fixture=org.json.JSONObject(requireNotNull(javaClass.getResource(
            "/williams_tc2_public_h1_congestion_20261027.json")).readText())
        val end=fixture.getLong("last_closed_at_ms")
        val raw=fixture.getJSONArray("symbols")
        assertEquals(10,raw.length())
        val classifications=linkedMapOf<String,String>()
        for(i in 0 until raw.length()) {
            val pair=raw.getJSONObject(i)
            val symbol=pair.getString("symbol")
            val candles=pair.getJSONArray("bars")
            val bars=(0 until candles.length()).map { k ->
                val a=candles.getJSONArray(k)
                WilliamsWaveBar(a.getLong(0),a.getDouble(1),a.getDouble(2),
                    a.getDouble(3),a.getDouble(4),a.getDouble(5))
            }
            assertEquals(symbol,150,bars.size)
            assertEquals(symbol,end,bars.last().t+h)
            assertTrue(symbol,bars.zipWithNext().all { (a,b)->b.t-a.t==h })
            val last=bars.last().c
            val v=williamsTc2CongestionEvidence(bars,end,"SHORT_FRACTAL","SHORT",last)
            assertEquals(symbol,"CLOSED_CONTIGUOUS_H1_CANDLES",v.getString("source"))
            assertTrue(symbol,v.getString("state") in
                setOf("COMPLEX_CORRECTION_CANDIDATE",
                    "COMPLEX_CORRECTION_RESOLVED",
                    "ORDINARY_CONGESTION_OR_CORRECTION_NOT_COMPLEX",
                    "NO_COMPLEX_CORRECTION_EVIDENCE"))
            assertFalse(symbol,v.getBoolean("can_place_orders"))
            assertFalse(symbol,v.getBoolean("can_close"))
            assertFalse(symbol,v.getBoolean("can_change_stops"))
            classifications[symbol]=v.getString("state")
            // Formation/future bars cannot leak into H1 confirmation.
            assertEquals(symbol,"HISTORY_UNVERIFIED",
                williamsTc2CongestionEvidence(
                    bars+WilliamsWaveBar(end,1.0,999999.0,.1,1.0),
                    end,"SHORT_FRACTAL","SHORT",last).getString("state"))
            // Identical raw history must be deterministic regardless of scans.
            assertEquals(symbol,v.toString(),
                williamsTc2CongestionEvidence(bars,end,"SHORT_FRACTAL","SHORT",last).toString())
        }
        assertEquals(setOf("BTCUSDT","ETHUSDT","BNBUSDT","SOLUSDT","XRPUSDT",
            "DOGEUSDT","ADAUSDT","LINKUSDT","AVAXUSDT","LTCUSDT"),
            classifications.keys)
        // A historical sideways-range classification is no longer automatically
        // a trade veto; only a demonstrated complex price-grammar candidate is.
        assertTrue(classifications["BNBUSDT"] in setOf(
            "COMPLEX_CORRECTION_CANDIDATE",
            "COMPLEX_CORRECTION_RESOLVED",
            "ORDINARY_CONGESTION_OR_CORRECTION_NOT_COMPLEX",
            "NO_COMPLEX_CORRECTION_EVIDENCE"))
    }

}
