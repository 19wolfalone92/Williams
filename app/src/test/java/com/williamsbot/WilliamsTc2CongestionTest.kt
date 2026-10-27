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

    @Test fun correctiveCongestionVetoesOnlyInsideRangeNewWm2Wm3Risk() {
        val b=oscillating()
        val now=(b.last().t+h)
        val inside=williamsTc2CongestionEvidence(b,now,"SHORT_FRACTAL","SHORT",740.0)
        assertEquals("CONGESTION_CANDIDATE",inside.getString("state"))
        assertTrue(inside.getBoolean("blocked"))
        assertEquals(WILLIAMS_TC2_CONGESTION_BLOCKER,inside.getString("reason_code"))
        assertTrue(inside.getInt("teeth_close_crossings")>=3)
        assertTrue(inside.getInt("ao_zero_crossings")>=2)
        assertFalse(inside.getBoolean("can_place_orders"))
        assertFalse(inside.getBoolean("can_close"))
        val longInside=williamsTc2CongestionEvidence(b,now,"SUPER_AO","LONG",740.0)
        assertTrue(longInside.getBoolean("blocked"))
        val escapeUp=williamsTc2CongestionEvidence(b,now,"FRACTAL","LONG",744.0)
        val escapeDown=williamsTc2CongestionEvidence(b,now,"SHORT_FRACTAL","SHORT",736.0)
        assertFalse(escapeUp.getBoolean("blocked"))
        assertFalse(escapeDown.getBoolean("blocked"))
        assertEquals("STOP_BREAKOUT_OUTSIDE_24_H1_RANGE",escapeDown.getString("reason_code"))
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
        assertEquals("NOT_CONFIRMED",result.getString("state"))
        val missing=williamsTc2CongestionEvidence(b.takeLast(35),now,"SHORT_FRACTAL","SHORT",760.0)
        assertEquals("HISTORY_UNVERIFIED",missing.getString("state"))
        assertFalse(missing.getBoolean("blocked"))
        val future=b+WilliamsWaveBar(now,1.0,999.0,.1,1.0)
        assertEquals("HISTORY_UNVERIFIED",
            williamsTc2CongestionEvidence(future,now,"SHORT_FRACTAL","SHORT",760.0).getString("state"))
        assertFalse(williamsTc2CongestionEvidence(b,now,"SHORT_FRACTAL","SHORT",760.0)
            .getBoolean("can_change_stops"))
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
                setOf("CONGESTION_CANDIDATE","NOT_CONFIRMED"))
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
        // Independent H1 review in this historical snapshot flagged at
        // least BNB as bounded; this is a regression of project thresholds,
        // never a claim that the remaining symbols had no correction.
        assertEquals("CONGESTION_CANDIDATE",classifications["BNBUSDT"])
    }

}
