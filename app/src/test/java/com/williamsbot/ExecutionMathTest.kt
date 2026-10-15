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
}
