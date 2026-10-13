package com.williamsbot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EquityCircuitBreakerTest {
    @Test
    fun tripsAtThreePercentWithinHour() {
        val b = EquityCircuitBreaker()
        b.observe(1000.0, 1_000L)
        assertTrue(b.observe(970.0, 2_000L).tripped)
        assertTrue(b.observe(969.9, 3_000L).tripped)
    }

    @Test
    fun remainsTrippedAfterOneHourWithinSameUtcDay() {
        val b = EquityCircuitBreaker()
        b.observe(1000.0, 1_000L)
        assertTrue(
            b.observe(
                950.0,
                1_000L + 60L * 60L * 1000L + 1L
            ).tripped
        )
    }

    @Test
    fun resetsAtUtcDayBoundary() {
        val b = EquityCircuitBreaker()
        b.observe(1000.0, 1_000L)
        assertTrue(b.observe(970.0, 2_000L).tripped)

        val nextUtcDay = 86_400_000L + 1_000L
        assertFalse(b.observe(950.0, nextUtcDay).tripped)
        assertTrue(b.observe(921.5, nextUtcDay + 1_000L).tripped)
    }



    @Test
    fun dailyBaselineAndTripLatchSurviveRuntimeRecreation() {
        var saved: EquityCircuitBreaker.PersistedState? = null
        val first = EquityCircuitBreaker(
            maxDrawdownPct = 0.03,
            loadState = { saved ?: EquityCircuitBreaker.PersistedState("", 0.0, false) },
            saveState = { state -> saved = state; true }
        )
        first.observe(1000.0, 1_000L)
        assertTrue(first.observe(970.0, 2_000L).tripped)

        val restarted = EquityCircuitBreaker(
            maxDrawdownPct = 0.03,
            loadState = { saved ?: error("state not persisted") },
            saveState = { state -> saved = state; true }
        )
        assertTrue(restarted.observe(995.0, 3_000L).tripped)
        assertTrue(saved!!.tripped)
        assertTrue(saved!!.startEquity == 1000.0)
    }

    @Test
    fun persistenceFailureTripsBreakerAndThrows() {
        val breaker = EquityCircuitBreaker(
            maxDrawdownPct = 0.03,
            loadState = { EquityCircuitBreaker.PersistedState("", 0.0, false) },
            saveState = { false }
        )
        try {
            breaker.observe(1000.0, 1_000L)
            throw AssertionError("Expected persistence failure")
        } catch (expected: IllegalStateException) {
            assertTrue(breaker.isTripped())
        }
    }

    @Test
    fun invalidEquityFailsClosed() {
        val breaker = EquityCircuitBreaker()
        assertTrue(breaker.observe(Double.NaN, 1_000L).tripped)
        assertTrue(breaker.isTripped())
    }
}
