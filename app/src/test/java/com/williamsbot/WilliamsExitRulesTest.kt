package com.williamsbot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WilliamsExitRulesTest {
    @Test
    fun fiveBarExitUsesPriceBarColorNotOscillatorDirection() {
        assertTrue(isBullishWilliamsPriceBar(100.0, 101.0))
        assertFalse(isBullishWilliamsPriceBar(101.0, 100.0))
        assertFalse(isBullishWilliamsPriceBar(100.0, 100.0))
        assertFalse(isBullishWilliamsPriceBar(Double.NaN, 101.0))
        assertFalse(isBullishWilliamsPriceBar(100.0, Double.POSITIVE_INFINITY))
    }
}
