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
}
