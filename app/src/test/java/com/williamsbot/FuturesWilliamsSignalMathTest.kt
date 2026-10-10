package com.williamsbot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FuturesWilliamsSignalMathTest {
    @Test
    fun superAoRequiresThreeConsecutiveGreenBars() {
        assertTrue(hasThreeSameColorAo(listOf(1.0, 1.1, 1.2, 1.3), "LONG"))
        assertFalse(hasThreeSameColorAo(listOf(1.0, 1.1, 1.2), "LONG"))
        assertFalse(hasThreeSameColorAo(listOf(1.0, 1.2, 1.1, 1.3), "LONG"))
    }

    @Test
    fun superAoRequiresThreeConsecutiveRedBars() {
        assertTrue(hasThreeSameColorAo(listOf(1.3, 1.2, 1.1, 1.0), "SHORT"))
        assertFalse(hasThreeSameColorAo(listOf(1.3, 1.2, 1.1), "SHORT"))
        assertFalse(hasThreeSameColorAo(listOf(1.3, 1.1, 1.2, 1.0), "SHORT"))
    }

    @Test
    fun superAoRejectsUnknownDirectionAndNonFiniteValues() {
        assertFalse(hasThreeSameColorAo(listOf(1.0, 1.1, 1.2, 1.3), "BUY"))
        assertFalse(hasThreeSameColorAo(listOf(1.0, Double.NaN, 1.2, 1.3), "LONG"))
    }
}
