package com.williamsbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WilliamsAlligatorMathTest {
    @Test
    fun usesMedianPricesAndCanonicalJawTeethLipsDisplayShifts() {
        val highs = (0..60).map { 110.0 + it * 2.0 }
        val lows = (0..60).map { 90.0 + it * 2.0 }
        val lines = WilliamsAlligatorMath.calculate(highs, lows)

        // First median is 100; before each display shift there is no prior line.
        assertTrue(lines.jaw.take(8).all { !it.isFinite() })
        assertTrue(lines.teeth.take(5).all { !it.isFinite() })
        assertTrue(lines.lips.take(3).all { !it.isFinite() })
        assertEquals(100.0, lines.jaw[8], 0.0)
        assertEquals(100.0, lines.teeth[5], 0.0)
        assertEquals(100.0, lines.lips[3], 0.0)
        assertTrue(lines.validAt(20))
    }

    @Test
    fun malformedOrMismatchedPriceInputsFailClosed() {
        val mismatch = WilliamsAlligatorMath.calculate(
            highs = listOf(101.0, 102.0),
            lows = listOf(99.0)
        )
        assertTrue(mismatch.jaw.all { !it.isFinite() })
        assertTrue(mismatch.teeth.all { !it.isFinite() })
        assertTrue(mismatch.lips.all { !it.isFinite() })

        val invalid = WilliamsAlligatorMath.calculateFromMedianPrices(
            listOf(100.0, 101.0, Double.NaN, 103.0, 104.0, 105.0, 106.0,
                107.0, 108.0, 109.0, 110.0, 111.0, 112.0, 113.0, 114.0, 115.0,
                116.0, 117.0, 118.0, 119.0, 120.0)
        )
        assertFalse(invalid.validAt(invalid.jaw.lastIndex))
    }
}
