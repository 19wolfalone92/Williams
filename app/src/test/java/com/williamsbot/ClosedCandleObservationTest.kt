package com.williamsbot

import org.junit.Assert.*
import org.junit.Test

class ClosedCandleObservationTest {
    private fun candle(t:Long,closed:Boolean,close:Double=100.0)=CandleN(t,100.0,102.0,98.0,close,10.0,closed)
    @Test fun anElapsedClockCannotTurnALostFinalWsUpdateIntoAConfirmedClose() {
        val cache=mutableListOf(candle(0,true),candle(3_600_000,false))
        val now=7_200_001L
        assertEquals(listOf(0L),closedNativeStrategyCandles(cache) { it.t+3_600_000<=now }.map { it.t })
        assertTrue(upsertNativeCandleObservation(cache,candle(3_600_000,true,101.0)))
        assertEquals(2,closedNativeStrategyCandles(cache) { it.t+3_600_000<=now }.size)
    }
    @Test fun reconnectAndOutOfOrderUpdatesDoNotDuplicateBarsOrDowngradeAFinalClose() {
        val cache=mutableListOf(candle(0,true),candle(7_200_000,false))
        assertTrue(upsertNativeCandleObservation(cache,candle(3_600_000,true,101.0)))
        assertEquals(listOf(0L,3_600_000L,7_200_000L),cache.map { it.t })
        assertFalse(upsertNativeCandleObservation(cache,candle(3_600_000,false,99.0)))
        assertEquals(101.0,cache[1].c,0.0)
        assertTrue(cache[1].exchangeClosed)
        assertEquals(3,cache.size)
    }
    @Test fun malformedKlineCannotPoisonAPreviouslyGoodCache() {
        val cache=mutableListOf(candle(0,true))
        assertFalse(upsertNativeCandleObservation(cache,candle(3_600_000,true).copy(h=Double.NaN)))
        assertFalse(upsertNativeCandleObservation(cache,candle(3_600_000,true).copy(l=101.0)))
        assertEquals(1,cache.size)
    }
}
