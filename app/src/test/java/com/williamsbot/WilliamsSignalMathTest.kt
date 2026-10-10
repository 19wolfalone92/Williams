package com.williamsbot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WilliamsSignalMathTest {
    @Test
    fun signalRemainsActionableOnlyBeforeTriggerAndOppositeExtremeAreTouched() {
        val highs = listOf(10.0, 11.0, 10.5, 9.8)
        val lows = listOf(8.0, 8.2, 8.1, 8.3)
        assertTrue(isLongSignalStillActionable(0, 3, highs, lows, triggerPrice = 10.1))
        assertFalse(isLongSignalStillActionable(0, 3, highs, lows, triggerPrice = 10.0))
        assertFalse(isLongSignalStillActionable(0, 3, highs, listOf(8.0, 8.2, 7.9, 8.3), triggerPrice = 10.1))
    }

    @Test
    fun sourceCandleIsNotTreatedAsALaterTriggerTouch() {
        assertTrue(
            isLongSignalStillActionable(
                signalIndex = 1,
                currentIndex = 1,
                highs = listOf(9.0, 10.0),
                lows = listOf(8.0, 8.0),
                triggerPrice = 10.1
            )
        )
    }

    @Test
    fun malformedIndicesOrPricesFailClosed() {
        assertFalse(isLongSignalStillActionable(-1, 1, listOf(9.0, 10.0), listOf(8.0, 8.0), 10.1))
        assertFalse(isLongSignalStillActionable(1, 0, listOf(9.0, 10.0), listOf(8.0, 8.0), 10.1))
        assertFalse(isLongSignalStillActionable(0, 1, listOf(9.0), listOf(8.0, 8.0), 10.1))
        assertFalse(isLongSignalStillActionable(0, 1, listOf(9.0, Double.NaN), listOf(8.0, 8.0), 10.1))
    }
}
