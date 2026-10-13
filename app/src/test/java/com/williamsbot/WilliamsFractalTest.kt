package com.williamsbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WilliamsFractalTest {
    @Test
    fun equalHighGroupUsesLastEqualBarAndLaterConfirmation() {
        val highs = listOf(4.0, 5.0, 7.0, 7.0, 6.0, 5.0, 4.0)
        val fractal = WilliamsFractal.latestUp(highs, throughIndex = 5)
        assertEquals(3, fractal?.centerIndex)
        assertEquals(5, fractal?.confirmationIndex)
        assertEquals(7.0, fractal?.level ?: 0.0, 1e-9)
    }

    @Test
    fun equalHighGroupIsNotExposedBeforeTwoRightBarsConfirmIt() {
        val highs = listOf(4.0, 5.0, 7.0, 7.0, 6.0, 5.0, 4.0)
        assertNull(WilliamsFractal.latestUp(highs, throughIndex = 4))
    }

    @Test
    fun unconfirmedEqualHighGroupDoesNotRepaintIntoSignal() {
        val highs = listOf(4.0, 5.0, 7.0, 7.0, 6.0, 7.0)
        assertNull(WilliamsFractal.latestUp(highs, throughIndex = 5))
    }

    @Test
    fun equalLowGroupUsesSymmetricConfirmation() {
        val lows = listOf(10.0, 9.0, 7.0, 7.0, 8.0, 9.0, 10.0)
        val fractal = WilliamsFractal.latestDown(lows, throughIndex = 5)
        assertEquals(3, fractal?.centerIndex)
        assertEquals(5, fractal?.confirmationIndex)
        assertEquals(7.0, fractal?.level ?: 0.0, 1e-9)
    }
}
