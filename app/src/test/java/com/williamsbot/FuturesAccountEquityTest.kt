package com.williamsbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FuturesAccountEquityTest {
    @Test
    fun prefersAuthoritativeTotalMarginBalance() {
        assertEquals(990.0, futuresAccountEquity(990.0, 1000.0, -5.0)!!, 0.0)
    }

    @Test
    fun fallsBackToWalletBalancePlusUnrealizedPnlWhenMarginBalanceMissing() {
        assertEquals(995.0, futuresAccountEquity(null, 1000.0, -5.0)!!, 0.0)
    }

    @Test
    fun rejectsMissingNonFiniteOrNonPositiveEquity() {
        assertNull(futuresAccountEquity(null, 1000.0, null))
        assertNull(futuresAccountEquity(Double.NaN, 1000.0, -5.0))
        assertNull(futuresAccountEquity(0.0, 0.0, 0.0))
        assertNull(futuresAccountEquity(null, 1000.0, Double.POSITIVE_INFINITY))
    }
}
