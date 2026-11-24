package com.williamsbot

import org.junit.Assert.*
import org.junit.Test

class ClosedCandleSyncCadenceTest {
    @Test fun m5RecalculationWakeFollowsUtcFiveMinuteClose() {
        val now=10*3_600_000L+3*60_000L
        assertEquals(122_500L,nextClosedCandleSyncDelayMs(now,"5m"))
        // Next request occurs after the 10:05:00 candle boundary.
        assertEquals(3_500L,nextClosedCandleSyncDelayMs(
            10*3_600_000L+4*60_000L+59_000L,"5m"))
    }

    @Test fun h1M15M5EachHaveOwnIndependentCloseCadence() {
        val now=10*3_600_000L+12*60_000L
        assertEquals(2_882_500L,nextClosedCandleSyncDelayMs(now,"1h"))
        assertEquals(182_500L,nextClosedCandleSyncDelayMs(now,"15m"))
        assertEquals(182_500L,nextClosedCandleSyncDelayMs(now,"5m"))
    }

    @Test fun noVerifiedExchangeUtcNeverInventsClosedCandle() {
        assertEquals(30_000L,nextClosedCandleSyncDelayMs(0L,"5m"))
        assertEquals(30_000L,nextClosedCandleSyncDelayMs(-100L,"1h"))
        assertEquals(30_000L,nextClosedCandleSyncDelayMs(1_800_000_000_000L,"1M"))
    }

    @Test fun closedWsCandleCannotBeReplacedWithFormingTick() {
        val closed=CandleN(3_600_000L,100.0,105.0,98.0,104.0,12.0,true)
        val live=CandleN(3_600_000L,100.0,110.0,90.0,109.0,15.0,false)
        val series=mutableListOf(closed)
        assertFalse(upsertNativeCandleObservation(series,live))
        assertEquals(closed,series.single())
        val next=CandleN(7_200_000L,104.0,106.0,101.0,105.0,10.0,false)
        assertTrue(upsertNativeCandleObservation(series,next))
        assertEquals(listOf(closed),closedNativeStrategyCandles(series) { true })
        val nextClosed=next.copy(exchangeClosed=true)
        assertTrue(upsertNativeCandleObservation(series,nextClosed))
        assertEquals(2,closedNativeStrategyCandles(series) { true }.size)
        assertEquals(1,closedNativeStrategyCandles(series) { it.t<7_200_000L }.size)
    }
}
