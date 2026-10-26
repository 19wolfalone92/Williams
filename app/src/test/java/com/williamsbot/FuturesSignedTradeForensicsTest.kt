package com.williamsbot

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FuturesSignedTradeForensicsTest {
    private val now=1_800_000_000_000L
    private fun fill(symbol:String="BNBUSDT",side:String="BUY",quantity:String="0.18",
                     commissionAsset:String="USDT"):JSONObject =
        JSONObject().put("symbol",symbol).put("orderId","123456").put("side",side)
            .put("price","739.0").put("qty",quantity).put("realizedPnl","-0.50")
            .put("commission","0.020").put("commissionAsset",commissionAsset)
            .put("time",now-1000)

    @Test fun readOnlyBnbBuyFillCannotProvePreviousShortWasClosed() {
        val result=FuturesSignedTradeForensics.summarize("BNBUSDT",listOf(fill()),now)
        assertEquals("RECENT_SIGNED_FILLS_NO_CAMPAIGN_BINDING",result.getString("status"))
        assertEquals("0.18",result.getString("buy_qty"))
        assertEquals("-0.5",result.getString("window_realized_pnl_usdt"))
        assertTrue(result.getBoolean("window_usdt_commission_known"))
        assertEquals("0.02",result.getString("window_usdt_commission"))
        assertFalse(result.getBoolean("ownership_verified"))
        assertFalse(result.getBoolean("closed_position_proven"))
        assertFalse(result.getBoolean("full_campaign_pnl_verified"))
        assertFalse(result.getBoolean("can_change_stop"))
        assertFalse(result.getBoolean("can_submit_order"))
    }
    @Test fun absentOrMalformedSignedHistoryNeverInferred() {
        assertEquals("SIGNED_TRADE_READ_INCOMPLETE",
            FuturesSignedTradeForensics.summarize("BNBUSDT",null,now).getString("status"))
        assertEquals("NO_RECENT_SIGNED_FILLS",
            FuturesSignedTradeForensics.summarize("BNBUSDT",emptyList(),now).getString("status"))
        assertEquals("SIGNED_TRADE_SHAPE_UNVERIFIED",
            FuturesSignedTradeForensics.summarize("BNBUSDT",listOf(fill("BTCUSDT")),now).getString("status"))
        assertEquals("SIGNED_TRADE_SHAPE_UNVERIFIED",
            FuturesSignedTradeForensics.summarize("BNBUSDT",listOf(fill(quantity="0")),now).getString("status"))
    }
    @Test fun feesInNonUsdtAssetAreNotMisrepresentedAsNetPnl() {
        val result=FuturesSignedTradeForensics.summarize("BNBUSDT",
            listOf(fill(commissionAsset="BNB")),now)
        assertFalse(result.getBoolean("window_usdt_commission_known"))
        assertTrue(result.isNull("window_usdt_commission"))
    }
}