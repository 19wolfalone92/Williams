package com.williamsbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EquityCircuitBreakerTest {
    @Test
    fun tripsAtThreePercentDailyLoss() {
        val breaker = EquityCircuitBreaker(maxDrawdownPct = 0.03)
        val now = 1_791_878_400_000L
        assertFalse(breaker.observe(10_000.0, now).tripped)
        val snapshot = breaker.observe(9_700.0, now + 60_000L)
        assertTrue(snapshot.tripped)
        assertEquals(0.03, snapshot.drawdownPct, 1e-9)
    }

    @Test
    fun restoredBaselineTripsAtThreePercentLossAfterRestart() {
        val now = 1_791_878_400_000L
        val beforeRestart = EquityCircuitBreaker(maxDrawdownPct = 0.03)
        val initial = beforeRestart.observe(10_000.0, now)

        val afterRestart = EquityCircuitBreaker(maxDrawdownPct = 0.03)
        afterRestart.restore(
            startEquity = initial.startEquity,
            wasTripped = initial.tripped,
            nowMs = now + 60_000L
        )
        assertTrue(afterRestart.observe(9_700.0, now + 60_000L).tripped)
    }

    @Test
    fun restoredTripRemainsLatchedAfterEquityRecovers() {
        val breaker = EquityCircuitBreaker(maxDrawdownPct = 0.03)
        val now = 1_791_878_400_000L
        breaker.restore(startEquity = 10_000.0, wasTripped = true, nowMs = now)
        assertTrue(breaker.observe(9_950.0, now + 60_000L).tripped)
    }

    @Test
    fun invalidEquityFailsClosed() {
        val breaker = EquityCircuitBreaker(maxDrawdownPct = 0.03)
        val now = 1_791_878_400_000L
        breaker.observe(10_000.0, now)
        assertTrue(breaker.observe(0.0, now + 60_000L).tripped)
    }

    @Test
    fun newUtcDayEstablishesNewBaseline() {
        val breaker = EquityCircuitBreaker(maxDrawdownPct = 0.03)
        val dayOne = 1_791_878_400_000L
        breaker.reset(10_000.0, dayOne)
        assertTrue(breaker.observe(9_600.0, dayOne + 60_000L).tripped)

        val nextUtcDay = dayOne + 86_400_000L
        val next = breaker.observe(9_600.0, nextUtcDay)
        assertFalse(next.tripped)
        assertEquals(9_600.0, next.startEquity, 1e-9)
    }
}
