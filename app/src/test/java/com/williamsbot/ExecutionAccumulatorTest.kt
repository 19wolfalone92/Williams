package com.williamsbot

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun duplicateTradeIdWithDifferentEventTimeIsStillOneFill() {
        val a = ExecutionAccumulator()
        a.acceptFill("12", "BTCUSDT", "99", 100, BigDecimal("0.01"), BigDecimal("100"), BigDecimal("0.001"), "USDT")
        val replay = a.acceptFill("12", "BTCUSDT", "99", 101, BigDecimal("0.01"), BigDecimal("100"), BigDecimal("0.001"), "USDT")!!
        assertEquals("0.01", replay.executedQty.toPlainString())
        assertEquals(1, replay.fills)
        assertEquals("0.001", replay.feeUsdt.toPlainString())
    }

    @Test
    fun missingCommissionAssetMarksAccountingUnknownInsteadOfZeroFee() {
        val a = ExecutionAccumulator()
        val snapshot = a.acceptFill(
            "13", "BTCUSDT", "100", 100,
            BigDecimal("0.01"), BigDecimal("100"),
            BigDecimal("0.001"), ""
        )!!
        assertEquals("0.01", snapshot.executedQty.toPlainString())
        assertFalse(snapshot.feeKnown)
    }

    @Test
    fun thirdAssetCommissionWithoutHistoricalConversionRemainsUnknown() {
        val a = ExecutionAccumulator()
        val snapshot = a.acceptFill(
            "14", "BTCUSDT", "101", 100,
            BigDecimal("0.01"), BigDecimal("100"),
            BigDecimal("0.001"), "BNB"
        )!!
        assertFalse(snapshot.feeKnown)
        assertEquals("0", snapshot.feeUsdt.toPlainString())
    }

    @Test
    fun invalidFillDoesNotIncreasePositionAccounting() {
        val a = ExecutionAccumulator()
        val snapshot = a.acceptFill(
            "15", "BTCUSDT", "102", 100,
            BigDecimal.ZERO, BigDecimal("100"),
            BigDecimal.ZERO, "USDT"
        )!!
        assertEquals("0", snapshot.executedQty.toPlainString())
        assertEquals(0, snapshot.fills)
        assertFalse(snapshot.feeKnown)
    }


    @Test
    fun sameOrderIdAcrossSymbolsDoesNotMergeFills() {
        val a = ExecutionAccumulator()
        a.acceptFill("7", "BTCUSDT", "1", 1, BigDecimal("0.01"), BigDecimal("100"), BigDecimal.ZERO, "USDT")
        a.acceptFill("7", "ETHUSDT", "1", 1, BigDecimal("0.02"), BigDecimal("2000"), BigDecimal.ZERO, "USDT")
        val btc = a.snapshot("BTCUSDT", "7")!!
        val eth = a.snapshot("ETHUSDT", "7")!!
        assertEquals("0.01", btc.executedQty.toPlainString())
        assertEquals("0.02", eth.executedQty.toPlainString())
        assertEquals(1, btc.fills)
        assertEquals(1, eth.fills)
    }
}
