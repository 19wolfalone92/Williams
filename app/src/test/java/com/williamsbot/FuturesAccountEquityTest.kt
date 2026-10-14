package com.williamsbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun zeroIsAValidKnownEmptyDemoBalanceNotAnUnknownAccount() {
        assertEquals(0.0, futuresAccountEquity(0.0, 0.0, 0.0)!!, 0.0)
        assertEquals(0.0, futuresAccountEquity(null, 0.0, 0.0)!!, 0.0)
    }

    @Test
    fun entryFundingRequiresPositiveFiniteUsdtAndEquity() {
        assertTrue(futuresAccountFundedForEntry(100.0, 100.0))
        assertFalse(futuresAccountFundedForEntry(0.0, 100.0))
        assertFalse(futuresAccountFundedForEntry(100.0, 0.0))
        assertFalse(futuresAccountFundedForEntry(-1.0, 100.0))
        assertFalse(futuresAccountFundedForEntry(Double.NaN, 100.0))
        assertFalse(futuresAccountFundedForEntry(100.0, Double.POSITIVE_INFINITY))
        assertFalse(futuresAccountFundedForEntry(null, null))
    }

    @Test
    fun rejectsMissingNonFiniteOrNegativeEquity() {
        assertNull(futuresAccountEquity(null, 1000.0, null))
        assertNull(futuresAccountEquity(Double.NaN, 1000.0, -5.0))
        assertNull(futuresAccountEquity(-1.0, 0.0, 0.0))
        assertNull(futuresAccountEquity(null, 1000.0, Double.POSITIVE_INFINITY))
    }
}
