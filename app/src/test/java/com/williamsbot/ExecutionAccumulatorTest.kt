package com.williamsbot

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionAccumulatorTest {
    @Test
    fun accumulatesPartialFillsAndComputesVwapExactly() {
        val a = ExecutionAccumulator()
        a.accept(JSONObject().put("e","executionReport").put("E",1L).put("s","BTCUSDT").put("i","7").put("t","1").put("x","TRADE").put("l","0.001").put("L","100.00").put("n","0.000001").put("N","BTC"))
        val s = a.accept(JSONObject().put("e","executionReport").put("E",2L).put("s","BTCUSDT").put("i","7").put("t","2").put("x","TRADE").put("l","0.002").put("L","110.00").put("n","0.000002").put("N","BTC"))!!
        assertEquals("0.003", s.executedQty.toPlainString())
        assertEquals("0.32", s.executedQuote.setScale(2).toPlainString())
        assertEquals("106.666666666666666667", s.vwap.toPlainString())
        assertEquals(2, s.fills)
        assertTrue(s.feeKnown)
    }

    @Test
    fun ignoresDuplicateExecutionEvent() {
        val a = ExecutionAccumulator()
        val event = JSONObject().put("e","executionReport").put("E",10L).put("s","ETHUSDT").put("i","9").put("t","4").put("x","TRADE").put("l","1").put("L","2000").put("n","0.1").put("N","USDT")
        a.accept(event)
        val s = a.accept(event)!!
        assertEquals("1", s.executedQty.toPlainString())
        assertEquals(1, s.fills)
        assertEquals("0.1", s.feeUsdt.toPlainString())
    }
}
