package com.williamsbot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EquityCircuitBreakerTest {
    @Test
    fun tripsAtFivePercentWithinHour() {
        val b = EquityCircuitBreaker()
        b.observe(1000.0, 1_000L)
        assertTrue(b.observe(950.0, 2_000L).tripped)
        assertTrue(b.observe(949.9, 3_000L).tripped)
    }

    @Test
    fun expiresPeakAfterOneHour() {
        val b = EquityCircuitBreaker()
        b.observe(1000.0, 1_000L)
        assertFalse(
            b.observe(
                950.0,
                1_000L + 60L * 60L * 1000L + 1L
            ).tripped
        )
    }
}
