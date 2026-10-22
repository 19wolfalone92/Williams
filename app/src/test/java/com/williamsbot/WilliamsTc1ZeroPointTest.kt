package com.williamsbot

import org.junit.Assert.*
import org.junit.Test

class WilliamsTc1ZeroPointTest {
    private val h1 = WILLIAMS_H1_MS
    private fun history(n:Int=150):List<WilliamsWaveBar> =
        (0 until n).map { i ->
            // Two recurring directional extrema are observable, but their
            // labels as third/fifth waves are deliberately UNPROVEN.
            val peak=if(i%22==9) 13.0 else 0.0
            val base=100.0+i*.025+3.0*kotlin.math.sin(i*.30)
            val h=base+2.0+peak
            val l=base-2.0
            WilliamsWaveBar((i+1)*h1,base,h,l,base,100.0+i%3)
        }

    @Test fun zeroPointReportsAllFiveBookDimensionsButNeverClaimsTradePermission() {
        val rows=history()
        val now=rows.last().t+h1
        val report=williamsTc1ZeroPointObservations(rows,now)
        assertEquals("OBSERVATIONAL_PARTIAL_NOT_EXECUTABLE",report.getString("state"))
        assertFalse(report.getBoolean("zero_point_confirmed"))
        assertFalse(report.getBoolean("wave_degree_certified"))
        assertFalse(report.getBoolean("can_place_orders"))
        assertFalse(report.getBoolean("can_close_positions"))
        val candidates=report.getJSONArray("candidates")
        assertEquals(2,candidates.length())
        val upward=candidates.getJSONObject(0)
        assertEquals("NOT_VERIFIED_NO_CAUSAL_PRICE_TARGET",upward.getString("target_zone"))
        assertEquals("NOT_VERIFIED_WAVE_START_UNKNOWN",upward.getString("full_wave_100_to_140_bar_scale"))
        assertEquals("BINANCE_KLINE_BASE_VOLUME_PROXY_NOT_BOOK_TICK_VOLUME",
            upward.getString("squat_volume_source"))
        assertTrue(upward.getLong("w5_fractal_known_at_ms")<=now)
    }

    @Test fun waveEvidenceRejectsFutureOpenOrGappedCandlesWithoutLookingAhead() {
        val bars=history()
        val close=bars.last().t+h1
        assertEquals("CLOSED_CONTIGUOUS_CANDLES_UNVERIFIED",
            williamsTc1ZeroPointObservations(bars,close-1).getString("state"))
        assertEquals("CLOSED_CONTIGUOUS_CANDLES_UNVERIFIED",
            williamsTc1ZeroPointObservations(bars.take(68)+bars.drop(69),close).getString("state"))
        assertEquals("CLOSED_CONTIGUOUS_CANDLES_UNVERIFIED",
            williamsTc1ZeroPointObservations(bars.take(44),close).getString("state"))
    }

    @Test fun fullHistoryReadDoesNotPretendToValidateBookSpecificPriceTargets() {
        val bars=history()
        val now=bars.last().t+h1
        val a=williamsTc1ZeroPointObservations(bars,now)
        val b=williamsTc1ZeroPointObservations(bars.dropLast(1),now)
        assertEquals("NOT_VERIFIED_NO_CAUSAL_PRICE_TARGET",
            a.getJSONArray("candidates").getJSONObject(0).getString("target_zone"))
        assertFalse(b.getBoolean("zero_point_confirmed"))
        assertFalse(a.getBoolean("zero_point_confirmed"))
    }
}
