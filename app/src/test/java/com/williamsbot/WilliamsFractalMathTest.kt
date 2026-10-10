package com.williamsbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class WilliamsFractalMathTest {
    @Test
    fun equalHighDoesNotCountAndConfirmationCanNeedSixBars() {
        val highs = listOf(7.0, 8.0, 10.0, 9.0, 10.0, 8.0)
        assertNull(WilliamsFractalMath.confirmUp(highs, centerIndex = 2, throughIndex = 4))
        val confirmed = WilliamsFractalMath.confirmUp(highs, centerIndex = 2)
        assertNotNull(confirmed)
        assertEquals(2, confirmed!!.centerIndex)
        assertEquals(5, confirmed.confirmationIndex)
        assertEquals(10.0, confirmed.level, 0.0)
    }

    @Test
    fun equalLowDoesNotCountAndConfirmationCanNeedSixBars() {
        val lows = listOf(10.0, 9.0, 7.0, 8.0, 7.0, 9.0)
        assertNull(WilliamsFractalMath.confirmDown(lows, centerIndex = 2, throughIndex = 4))
        val confirmed = WilliamsFractalMath.confirmDown(lows, centerIndex = 2)
        assertNotNull(confirmed)
        assertEquals(2, confirmed!!.centerIndex)
        assertEquals(5, confirmed.confirmationIndex)
        assertEquals(7.0, confirmed.level, 0.0)
    }

    @Test
    fun aNewStrictExtremeInvalidatesBeforeTwoQualifiers() {
        assertNull(WilliamsFractalMath.confirmUp(
            listOf(7.0, 8.0, 10.0, 9.0, 10.1, 8.0),
            centerIndex = 2
        ))
        assertNull(WilliamsFractalMath.confirmDown(
            listOf(10.0, 9.0, 7.0, 8.0, 6.9, 9.0),
            centerIndex = 2
        ))
    }

    @Test
    fun latestFunctionsDoNotReadBeyondSuppliedConfirmationIndex() {
        val highs = listOf(7.0, 8.0, 10.0, 9.0, 10.0, 8.0)
        assertNull(WilliamsFractalMath.latestUp(highs, throughIndex = 4))
        assertEquals(5, WilliamsFractalMath.latestUp(highs, throughIndex = 5)!!.confirmationIndex)
    }

    @Test
    fun malformedOrInsufficientInputsFailClosed() {
        assertNull(WilliamsFractalMath.confirmUp(listOf(1.0, Double.NaN, 3.0, 2.0), centerIndex = 2))
        assertNull(WilliamsFractalMath.confirmDown(listOf(1.0, 2.0, 0.0), centerIndex = 0))
        assertNull(WilliamsFractalMath.latestDown(emptyList()))
    }
}
