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
    fun accumulatesNativeUsdMFuturesOrderTradeUpdateWithNestedFillFields() {
        val a = ExecutionAccumulator()
        fun futuresEvent(
            orderId: Long,
            tradeId: Long,
            transactionTime: Long,
            qty: String,
            price: String,
            commission: String
        ) = org.json.JSONObject()
            .put("e", "ORDER_TRADE_UPDATE")
            .put("E", transactionTime + 10L)
            .put("T", transactionTime)
            .put("o", org.json.JSONObject()
                .put("s", "BTCUSDT")
                .put("i", orderId)
                .put("t", tradeId)
                .put("x", "TRADE")
                .put("X", "PARTIALLY_FILLED")
                .put("l", qty)
                .put("L", price)
                .put("n", commission)
                .put("N", "USDT")
                .put("T", transactionTime))

        val first = futuresEvent(77, 1001, 1000, "0.01", "100", "0.04")
        val second = futuresEvent(77, 1002, 2000, "0.02", "110", "0.05")
        val s1 = a.accept(first)!!
        val duplicated = a.accept(first)!!
        val s2 = a.accept(second)!!

        assertEquals("0.01", s1.executedQty.toPlainString())
        assertEquals("0.01", duplicated.executedQty.toPlainString())
        assertEquals("0.03", s2.executedQty.stripTrailingZeros().toPlainString())
        assertEquals("3.2", s2.executedQuote.stripTrailingZeros().toPlainString())
        assertEquals("106.666666666666666667", s2.vwap.toPlainString())
        assertEquals("0.09", s2.feeUsdt.stripTrailingZeros().toPlainString())
        assertEquals(2, s2.fills)
        assertTrue(s2.feeKnown)
    }

    @Test
    fun futuresNormalizerPreservesOuterEventTimeAndRejectsMalformedNestedPayload() {
        val normalized = normalizedExecutionPayload(
            org.json.JSONObject()
                .put("e", "ORDER_TRADE_UPDATE")
                .put("E", 1234L)
                .put("o", org.json.JSONObject()
                    .put("i", 12)
                    .put("x", "TRADE")
                    .put("t", 3)
                    .put("T", 1200L))
        )!!
        assertEquals(1234L, normalized.optLong("E"))
        assertEquals(1200L, normalized.optLong("T"))
        assertEquals(null, normalizedExecutionPayload(
            org.json.JSONObject().put("e", "ORDER_TRADE_UPDATE").put("o", "bad")
        ))
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
