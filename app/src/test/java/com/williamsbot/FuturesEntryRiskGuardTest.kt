package com.williamsbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FuturesEntryRiskGuardTest {
    @Test
    fun acceptsNormalizedOrderInsideRiskAndNotionalCaps() {
        assertNull(
            FuturesEntryRiskGuard.violation(
                quantity = 1.0,
                triggerPrice = 100.0,
                stopPrice = 95.0,
                feeBufferPerSideFraction = 0.001,
                slippageBufferFraction = 0.001,
                riskBudget = 6.0,
                equity = 1_000.0,
            )
        )
    }

    @Test
    fun rejectsRiskWidenedByTickNormalization() {
        val reason = FuturesEntryRiskGuard.violation(
            quantity = 1.0,
            triggerPrice = 100.0,
            stopPrice = 90.0,
            feeBufferPerSideFraction = 0.001,
            slippageBufferFraction = 0.001,
            riskBudget = 6.0,
            equity = 1_000.0,
        )
        assertEquals("normalized loss estimate exceeds risk budget", reason)
    }

    @Test
    fun rejectsNormalizedNotionalAboveEquityCap() {
        val reason = FuturesEntryRiskGuard.violation(
            quantity = 3.0,
            triggerPrice = 100.0,
            stopPrice = 99.0,
            feeBufferPerSideFraction = 0.001,
            slippageBufferFraction = 0.001,
            riskBudget = 20.0,
            equity = 1_000.0,
        )
        assertEquals("normalized notional exceeds equity cap", reason)
    }

    @Test
    fun rejectsNonFiniteInputs() {
        val reason = FuturesEntryRiskGuard.violation(
            quantity = Double.NaN,
            triggerPrice = 100.0,
            stopPrice = 95.0,
            feeBufferPerSideFraction = 0.001,
            slippageBufferFraction = 0.001,
            riskBudget = 10.0,
            equity = 1_000.0,
        )
        assertEquals("non-finite normalized risk input", reason)
    }
}
