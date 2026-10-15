package com.williamsbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun exchangeFillSummaryHandlesLongAddOnsAndExactRealizedPnlAndFees() {
        val trades = org.json.JSONArray()
            .put(org.json.JSONObject()
                .put("symbol", "BTCUSDT").put("time", 1000L)
                .put("buyer", true).put("positionSide", "BOTH")
                .put("qty", "1").put("price", "100")
                .put("realizedPnl", "0").put("commission", "0.1").put("commissionAsset", "USDT"))
            .put(org.json.JSONObject()
                .put("symbol", "BTCUSDT").put("time", 2000L)
                .put("buyer", true).put("positionSide", "BOTH")
                .put("qty", "1").put("price", "110")
                .put("realizedPnl", "0").put("commission", "0.11").put("commissionAsset", "USDT"))
            .put(org.json.JSONObject()
                .put("symbol", "BTCUSDT").put("time", 3000L)
                .put("buyer", false).put("positionSide", "BOTH")
                .put("qty", "2").put("price", "120")
                .put("realizedPnl", "30").put("commission", "0.24").put("commissionAsset", "USDT"))

        val summary = summarizeFuturesCampaignTrades(
            symbol = "BTCUSDT",
            direction = "LONG",
            openedAtMs = 900L,
            closedAtMs = 4000L,
            userTrades = trades,
            initialRiskUsdt = 10.0
        )!!

        assertEquals(105.0, summary.entryPrice, 1e-12)
        assertEquals(120.0, summary.exitPrice, 1e-12)
        assertEquals(2.0, summary.quantity, 0.0)
        assertEquals(30.0, summary.grossPnlUsdt, 0.0)
        assertEquals(0.45, summary.feeUsdt, 1e-12)
        assertEquals(29.55, summary.netPnlUsdt, 1e-12)
        assertEquals(2.955, summary.rMultiple, 1e-12)
        assertTrue(summary.feeKnown)
        assertEquals(3, summary.fillCount)
    }

    @Test
    fun exchangeFillSummaryHandlesShortPnlAndFlagsUnconvertibleCommission() {
        val trades = org.json.JSONArray()
            .put(org.json.JSONObject()
                .put("symbol", "ETHUSDT").put("time", 1000L)
                .put("buyer", false).put("positionSide", "BOTH")
                .put("qty", "1").put("price", "100")
                .put("realizedPnl", "0").put("commission", "0.01").put("commissionAsset", "BNB"))
            .put(org.json.JSONObject()
                .put("symbol", "ETHUSDT").put("time", 2000L)
                .put("buyer", true).put("positionSide", "BOTH")
                .put("qty", "1").put("price", "90")
                .put("realizedPnl", "10").put("commission", "0.09").put("commissionAsset", "USDT"))

        val summary = summarizeFuturesCampaignTrades(
            symbol = "ETHUSDT",
            direction = "SHORT",
            openedAtMs = 900L,
            closedAtMs = 3000L,
            userTrades = trades,
            initialRiskUsdt = 5.0
        )!!

        assertEquals(100.0, summary.entryPrice, 1e-12)
        assertEquals(90.0, summary.exitPrice, 1e-12)
        assertEquals(10.0, summary.grossPnlUsdt, 0.0)
        assertEquals(0.09, summary.feeUsdt, 1e-12)
        assertEquals(9.91, summary.netPnlUsdt, 1e-12)
        assertEquals(false, summary.feeKnown)
    }

    @Test
    fun exchangeFillSummaryRejectsMismatchedOpeningAndClosingQuantities() {
        val trades = org.json.JSONArray()
            .put(org.json.JSONObject()
                .put("symbol", "BTCUSDT").put("time", 1000L)
                .put("buyer", true).put("positionSide", "BOTH")
                .put("qty", "2").put("price", "100")
                .put("realizedPnl", "0").put("commission", "0").put("commissionAsset", "USDT"))
            .put(org.json.JSONObject()
                .put("symbol", "BTCUSDT").put("time", 2000L)
                .put("buyer", false).put("positionSide", "BOTH")
                .put("qty", "1").put("price", "105")
                .put("realizedPnl", "5").put("commission", "0").put("commissionAsset", "USDT"))

        assertEquals(null, summarizeFuturesCampaignTrades(
            "BTCUSDT", "LONG", 900L, 3000L, trades, 10.0
        ))
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
