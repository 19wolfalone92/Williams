package com.williamsbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionMathTest {
    @Test
    fun decimalStepDoesNotFloatOver() {
        assertEquals(0.3, ExecutionMath.floorToStep(0.3, 0.1), 0.0)
        assertEquals(2.99, ExecutionMath.floorToStep(2.999999999, 0.01), 0.0)
    }

    @Test
    fun actualOnTickPricesDoNotLoseOrGainAnExtraTick() {
        assertEquals(79524.7, ExecutionMath.floorToStep(79524.7, 0.1), 0.0)
        assertEquals(2296.99, ExecutionMath.floorToStep(2296.99, 0.01), 0.0)
        assertEquals(11.354, ExecutionMath.floorToStep(11.354, 0.001), 0.0)
        assertEquals(79524.7, ExecutionMath.ceilToStep(79524.7, 0.1), 0.0)
        assertEquals(2296.99, ExecutionMath.ceilToStep(2296.99, 0.01), 0.0)
        assertEquals(2.31, ExecutionMath.ceilToStep(2.301, 0.01), 0.0)
    }

    @Test
    fun quantityNeverRoundsUp() {
        assertEquals(1.23, ExecutionMath.quantityToStep(1.23999, 0.01), 0.0)
    }

    @Test
    fun exactMultipleDetection() {
        assertTrue(ExecutionMath.isMultiple(0.3, 0.1))
        assertFalse(ExecutionMath.isMultiple(0.31, 0.1))
    }

    @Test
    fun commonQuantityStepMustAlignToBothLotFilters() {
        assertEquals(0.01, ExecutionMath.commonQuantityStep(0.001, 0.01), 0.0)
        assertEquals(0.06, ExecutionMath.commonQuantityStep(0.02, 0.03), 0.0)
        assertEquals(0.001, ExecutionMath.commonQuantityStep(0.001, 0.0), 0.0)
        val step = ExecutionMath.commonQuantityStep(0.02, 0.03)
        assertTrue(ExecutionMath.isMultiple(step, 0.02))
        assertTrue(ExecutionMath.isMultiple(step, 0.03))
    }

    @Test
    fun precisionAndFormattingHandleScientificNotationInSmallTicks() {
        assertEquals(5, ExecutionMath.decimalPlacesForIncrement(0.00001))
        assertEquals(6, ExecutionMath.decimalPlacesForIncrement(0.000001))
        assertEquals(3, ExecutionMath.decimalPlacesForIncrement(0.001))
        assertEquals("0.12015", ExecutionMath.floorToScale(0.120159, 5))
        assertEquals("0.000000015", ExecutionMath.floorToScale(0.000000015, 9))
    }

    @Test
    fun exactFullMarketClosePreservesExchangeQuantityAndBothLotFilters() {
        assertEquals(
            "0.02",
            ExecutionMath.exactFullMarketCloseQuantity(
                positionAmount = "-0.020",
                lotMinQty = "0.001",
                lotMaxQty = "1000",
                lotStep = "0.001",
                marketMinQty = "0.005",
                marketMaxQty = "100",
                marketStep = "0.005"
            )
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun exactFullMarketCloseRefusesToRoundOffStepPositionIntoPartialClose() {
        ExecutionMath.exactFullMarketCloseQuantity(
            positionAmount = "0.012",
            lotMinQty = "0.001",
            lotMaxQty = "1000",
            lotStep = "0.001",
            marketMinQty = "0.005",
            marketMaxQty = "100",
            marketStep = "0.005"
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun exactFullMarketCloseRespectsMarketMinimum() {
        ExecutionMath.exactFullMarketCloseQuantity(
            positionAmount = "0.003",
            lotMinQty = "0.001",
            lotMaxQty = "1000",
            lotStep = "0.001",
            marketMinQty = "0.005",
            marketMaxQty = "100",
            marketStep = "0.001"
        )
    }
}
