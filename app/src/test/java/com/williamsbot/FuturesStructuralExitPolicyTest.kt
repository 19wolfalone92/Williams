package com.williamsbot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FuturesStructuralExitPolicyTest {
    @Test
    fun longExitRequiresTwoClosedBarsWithBearishAlligatorTeethAndAo() {
        val bars = listOf(
            WilliamsExitBar(close = 90.0, jaw = 100.0, teeth = 95.0, lips = 92.0, ao = -1.0),
            WilliamsExitBar(close = 89.0, jaw = 99.0, teeth = 94.0, lips = 91.0, ao = -2.0)
        )
        assertTrue(FuturesStructuralExitPolicy.shouldExit("LONG", bars))
        assertFalse(FuturesStructuralExitPolicy.shouldExit("LONG", bars.takeLast(1)))
    }

    @Test
    fun shortExitRequiresTwoClosedBarsWithBullishAlligatorTeethAndAo() {
        val bars = listOf(
            WilliamsExitBar(close = 110.0, jaw = 100.0, teeth = 105.0, lips = 108.0, ao = 1.0),
            WilliamsExitBar(close = 111.0, jaw = 101.0, teeth = 106.0, lips = 109.0, ao = 2.0)
        )
        assertTrue(FuturesStructuralExitPolicy.shouldExit("SHORT", bars))
        assertFalse(FuturesStructuralExitPolicy.shouldExit("SHORT", bars.mapIndexed { i, b ->
            if (i == 1) b.copy(ao = -1.0) else b
        }))
    }

    @Test
    fun exitFailsClosedOnNonFiniteOrWrongAlligatorOrder() {
        val bars = listOf(
            WilliamsExitBar(90.0, 100.0, 95.0, 92.0, -1.0),
            WilliamsExitBar(89.0, 99.0, 94.0, 91.0, Double.NaN)
        )
        assertFalse(FuturesStructuralExitPolicy.shouldExit("LONG", bars))
        assertFalse(FuturesStructuralExitPolicy.shouldExit("UNKNOWN", bars))
    }
}
