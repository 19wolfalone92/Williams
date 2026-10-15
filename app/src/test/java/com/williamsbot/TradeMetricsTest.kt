package com.williamsbot

import org.junit.Assert.assertEquals
import org.junit.Test

class TradeMetricsTest {
    @Test
    fun shortTradeProfitUsesEntryMinusExitAndStoresSellSide() {
        val metrics = calculateDirectionalTradeMetrics(
            direction = "SHORT",
            entryPrice = 100.0,
            exitPrice = 90.0,
            quantity = 2.0,
            feeUsdt = 1.0,
            initialRiskUsdt = 20.0
        )
        assertEquals("SELL", metrics.entrySide)
        assertEquals(20.0, metrics.grossPnlUsdt, 0.0)
        assertEquals(19.0, metrics.netPnlUsdt, 0.0)
        assertEquals(0.95, metrics.rMultiple, 1e-12)
        assertEquals("WIN", metrics.outcome)
    }

    @Test
    fun shortLossAndLongProfitUseOppositePriceMoveSigns() {
        val shortLoss = calculateDirectionalTradeMetrics(
            "SHORT", 100.0, 105.0, 2.0, 1.0, 10.0
        )
        val longProfit = calculateDirectionalTradeMetrics(
            "LONG", 100.0, 105.0, 2.0, 1.0, 10.0
        )
        assertEquals("LOSS", shortLoss.outcome)
        assertEquals(-11.0, shortLoss.netPnlUsdt, 0.0)
        assertEquals("BUY", longProfit.entrySide)
        assertEquals("WIN", longProfit.outcome)
        assertEquals(9.0, longProfit.netPnlUsdt, 0.0)
    }
}
