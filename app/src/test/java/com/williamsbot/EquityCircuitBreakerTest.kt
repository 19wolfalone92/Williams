package com.williamsbot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EquityCircuitBreakerTest {
    @Test
    fun tripsAtOnePercentWithinHour() {
        val b = EquityCircuitBreaker()
        b.observe(1000.0, 1_000L)
        assertTrue(b.observe(990.0, 2_000L).tripped)
        assertTrue(b.observe(989.9, 3_000L).tripped)
    }

    @Test
    fun remainsTrippedAfterOneHourWithinSameUtcDay() {
        val b = EquityCircuitBreaker()
        b.observe(1000.0, 1_000L)
        assertTrue(
            b.observe(
                990.0,
                1_000L + 60L * 60L * 1000L + 1L
            ).tripped
        )
    }

    @Test
    fun resetsAtUtcDayBoundary() {
        val b = EquityCircuitBreaker()
        b.observe(1000.0, 1_000L)
        assertTrue(b.observe(990.0, 2_000L).tripped)

        val nextUtcDay = 86_400_000L + 1_000L
        assertFalse(b.observe(990.0, nextUtcDay).tripped)
        assertTrue(b.observe(980.0, nextUtcDay + 1_000L).tripped)
    }


}