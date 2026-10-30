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

    @Test fun ordinaryAbcFlatZigzagAndUnresolvedWxyAreAllowed() {
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
        assertTrue(williamsTc2RecentComplexPriceCandidates(expandedFlat,16*h).isEmpty())
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
            "ORDINARY_CONGESTION_OR_CORRECTION_NOT_COMPLEX",
            "NO_COMPLEX_CORRECTION_EVIDENCE"))
    }

}
