package com.williamsbot

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionAccumulatorTest {
    @Test
    fun accumulatesPartialFillsAndComputesVwapExactly() {
        val a = ExecutionAccumulator()
        a.accept(JSONObject("""{"e":"executionReport","E":1,"s":"BTCUSDT","i":7,"t":1,"x":"TRADE","l":"0.001","L":"100.00","n":"0.000001","N":"BTC"}"""))
        val s = a.accept(JSONObject("""{"e":"executionReport","E":2,"s":"BTCUSDT","i":7,"t":2,"x":"TRADE","l":"0.002","L":"110.00","n":"0.000002","N":"BTC"}"""))!!
        assertEquals("0.003", s.executedQty.toPlainString())
        assertEquals("0.32", s.executedQuote.setScale(2).toPlainString())
        assertEquals("106.666666666666666667", s.vwap.toPlainString())
        assertEquals(2, s.fills)
        assertTrue(s.feeKnown)
    }

    @Test
    fun ignoresDuplicateExecutionEvent() {
        val a = ExecutionAccumulator()
        val event = JSONObject("""{"e":"executionReport","E":10,"s":"ETHUSDT","i":9,"t":4,"x":"TRADE","l":"1","L":"2000","n":"0.1","N":"USDT"}""")
        a.accept(event)
        val s = a.accept(event)!!
        assertEquals("1", s.executedQty.toPlainString())
        assertEquals(1, s.fills)
        assertEquals("0.1", s.feeUsdt.toPlainString())
    }
}
