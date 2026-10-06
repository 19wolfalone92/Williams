package com.williamsbot

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionAccumulatorTest {
    @Test
    fun accumulatesPartialFillsAndComputesVwapExactly() {
        val a = ExecutionAccumulator()
        a.acceptFill("7","BTCUSDT","1",1,BigDecimal("0.001"),BigDecimal("100.00"),BigDecimal("0.000001"),"BTC")
        val s = a.acceptFill("7","BTCUSDT","2",2,BigDecimal("0.002"),BigDecimal("110.00"),BigDecimal("0.000002"),"BTC")!!
        assertEquals("0.003", s.executedQty.toPlainString())
        assertEquals("0.32", s.executedQuote.setScale(2).toPlainString())
        assertEquals("106.666666666666666667", s.vwap.toPlainString())
        assertEquals(2, s.fills)
        assertTrue(s.feeKnown)
    }

    @Test
    fun ignoresDuplicateExecutionEvent() {
        val a = ExecutionAccumulator()
        a.acceptFill("9","ETHUSDT","4",10,BigDecimal("1"),BigDecimal("2000"),BigDecimal("0.1"),"USDT")
        val s = a.acceptFill("9","ETHUSDT","4",10,BigDecimal("1"),BigDecimal("2000"),BigDecimal("0.1"),"USDT")!!
        assertEquals("1", s.executedQty.toPlainString())
        assertEquals(1, s.fills)
        assertEquals("0.1", s.feeUsdt.toPlainString())
    }
}
