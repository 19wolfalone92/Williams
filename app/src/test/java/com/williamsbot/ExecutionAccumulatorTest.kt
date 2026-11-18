package com.williamsbot

import java.math.BigDecimal
import org.junit.Assert.*
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
    fun stableBinanceTradeIdentityDeduplicatesChangedWsEventTime() {
        val a=ExecutionAccumulator()
        val first=a.acceptFill("701","BTCUSDT","42",1_000L,BigDecimal("0.1"),
            BigDecimal("100"),BigDecimal("0.01"),"USDT")!!
        val replay=a.acceptFill("701","BTCUSDT","42",2_000L,BigDecimal("0.1"),
            BigDecimal("100"),BigDecimal("0.01"),"USDT")!!
        assertEquals(first.executedQty,replay.executedQty)
        assertEquals(first.feeUsdt,replay.feeUsdt)
        assertEquals(1,replay.fills)
        val second=a.acceptFill("701","BTCUSDT","43",3_000L,BigDecimal("0.2"),
            BigDecimal("101"),BigDecimal("0.02"),"USDT")!!
        assertEquals(2,second.fills)
        assertEquals("0.3",second.executedQty.stripTrailingZeros().toPlainString())
    }

    @Test
    fun invalidZeroOrNegativeWsTradeCannotCorruptFillAccumulator() {
        val a=ExecutionAccumulator()
        assertEquals(null,a.acceptFill("91","BTCUSDT","1",1_000L,
            BigDecimal("-1"),BigDecimal("100")))
        assertEquals(null,a.acceptFill("91","BTCUSDT","1",1_000L,
            BigDecimal("1"),BigDecimal("0")))
        assertEquals(null,a.acceptFill("91","BTCUSDT","1",0L,
            BigDecimal("1"),BigDecimal("100")))
        val valid=a.acceptFill("91","BTCUSDT","1",1_000L,
            BigDecimal("1"),BigDecimal("100"))!!
        assertEquals(1,valid.fills)
        assertEquals("1",valid.executedQty.toPlainString())
    }

    @Test
    fun sameNumericOrderIdAcrossSymbolsMustNeverBlendQtyVwapOrFees() {
        val a=ExecutionAccumulator()
        a.acceptFill("1001","BTCUSDT","31",10L,BigDecimal("0.1"),
            BigDecimal("100"),BigDecimal("0.001"),"USDT")
        a.acceptFill("1001","ETHUSDT","31",11L,BigDecimal("2"),
            BigDecimal("2000"),BigDecimal("0.2"),"USDT")
        assertNull("Unscoped order id is ambiguous",a.snapshot("1001"))
        val btc=a.snapshot("1001","BTCUSDT")!!
        val eth=a.snapshot("1001","ETHUSDT")!!
        assertEquals("BTCUSDT",btc.symbol)
        assertEquals("ETHUSDT",eth.symbol)
        assertEquals("0.1",btc.executedQty.toPlainString())
        assertEquals("2",eth.executedQty.toPlainString())
        assertEquals("0.001",btc.feeUsdt.toPlainString())
        assertEquals("0.2",eth.feeUsdt.toPlainString())
        assertEquals(1,btc.fills)
        assertEquals(1,eth.fills)
        // A legacy ambiguous clear MUST NOT erase multiple symbols.
        a.clear("1001")
        assertNotNull(a.snapshot("1001","BTCUSDT"))
        assertNotNull(a.snapshot("1001","ETHUSDT"))
        a.clear("1001","BTCUSDT")
        assertNull(a.snapshot("1001","BTCUSDT"))
        assertEquals("ETHUSDT",a.snapshot("1001")!!.symbol)
        assertNull(a.acceptFill("1001","","99",12L,BigDecimal("10"),BigDecimal("5")))
    }

    @Test
    fun restoreRejectsMixedSymbolFillHistoryWithoutWipingPriorPosition() {
        fun trade(symbol:String,id:String,qty:String) = org.json.JSONObject()
            .put("e","ORDER_TRADE_UPDATE")
            .put("T",1_000)
            .put("o",org.json.JSONObject().put("s",symbol).put("i","41")
                .put("x","TRADE").put("t",id).put("T",1_000)
                .put("l",qty).put("L","100").put("n","0").put("N","USDT"))
        val a=ExecutionAccumulator()
        a.accept(trade("BTCUSDT","11","1"))
        assertNull(a.restore("41",listOf(
            trade("BTCUSDT","12","2"),trade("ETHUSDT","12","3"))))
        assertEquals("1",a.snapshot("41","BTCUSDT")!!.executedQty.toPlainString())
        val replay=a.restore("41",listOf(
            trade("BTCUSDT","11","1"),trade("BTCUSDT","12","2")))!!
        assertEquals("3",replay.executedQty.toPlainString())
        assertNull(a.snapshot("41","ETHUSDT"))
    }

    @Test
    fun positiveFeeWithoutAssetMustNeverBeReportedKnown() {
        val a=ExecutionAccumulator()
        val first=a.acceptFill("9","BTCUSDT","17",10L,BigDecimal("1"),
            BigDecimal("100"),BigDecimal("0.1"),"")!!
        assertFalse(first.feeKnown)
        assertEquals("0",first.feeUsdt.toPlainString())
        val second=a.acceptFill("9","BTCUSDT","18",11L,BigDecimal("1"),
            BigDecimal("110"),BigDecimal("0.1"),"USDT")!!
        assertFalse("UNKNOWN historical fee must remain sticky",second.feeKnown)
        assertEquals("0.1",second.feeUsdt.toPlainString())
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
