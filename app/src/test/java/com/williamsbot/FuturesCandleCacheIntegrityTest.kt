package com.williamsbot

import org.junit.Assert.*
import org.junit.Test

class FuturesCandleCacheIntegrityTest {
    private val m15 = 15L * 60L * 1000L
    private val t = 1795082400000L

    @Test fun uninterruptedClosedNativeHistoryCanUseCache() {
        assertTrue(futuresClosedCandleTimesContiguous(listOf(t,t+m15,t+2*m15),m15))
    }

    @Test fun missingBarCannotMasqueradeAsCompleteHistory() {
        assertFalse(futuresClosedCandleTimesContiguous(listOf(t,t+m15,t+3*m15),m15))
    }

    @Test fun duplicatedOutOfOrderOrFutureMismatchIsNotACompleteHistoryWindow() {
        assertFalse(futuresClosedCandleTimesContiguous(listOf(t,t,t+m15),m15))
        assertFalse(futuresClosedCandleTimesContiguous(listOf(t+m15,t),m15))
        assertFalse(futuresClosedCandleTimesContiguous(listOf(t,t+m15+1L),m15))
        assertFalse(futuresClosedCandleTimesContiguous(emptyList(),m15))
        assertFalse(futuresClosedCandleTimesContiguous(listOf(t),0L))
    }
}
