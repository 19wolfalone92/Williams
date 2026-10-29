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
    @Test fun historicalForensicsNeverDropFlatBnbOrLtc() {
        val symbols=FuturesSignedTradeForensics.auditSymbols(listOf("ETHUSDT","BTCUSDT","BNBUSDT"),8)
        assertEquals(listOf("BNBUSDT","LTCUSDT","ETHUSDT","BTCUSDT"),symbols)
        assertEquals(2,FuturesSignedTradeForensics.auditSymbols(emptyList(),2).size)
    }

    @Test fun signedFundingIsSeparateFromCampaignPnl() {
        val start=now-86_400_000L
        val income=JSONObject().put("symbol","LTCUSDT")
            .put("incomeType","FUNDING_FEE").put("income","-0.15")
            .put("asset","USDT").put("time",now-1000)
            .put("tranId","90021")
        val out=FuturesSignedTradeForensics.summarizeFunding("LTCUSDT",listOf(income),start,now)
        assertEquals("WINDOW_FUNDING_OBSERVED_NOT_CAMPAIGN_BOUND",out.getString("status"))
        assertEquals("-0.15",out.getString("funding_usdt"))
        assertFalse(out.getBoolean("full_campaign_pnl_verified"))
        assertFalse(out.getBoolean("can_submit_order"))
        assertFalse(out.getBoolean("can_change_stop"))
        assertTrue(out.getInt("funding_events")==1)
    }

    @Test fun missingMalformedAndDuplicateFundingFailClosed() {
        val start=now-86_400_000L
        val row=JSONObject().put("symbol","BNBUSDT")
            .put("incomeType","FUNDING_FEE").put("income","0.07")
            .put("asset","USDT").put("time",now-1000).put("tranId","21")
        assertEquals("SIGNED_FUNDING_READ_INCOMPLETE",
            FuturesSignedTradeForensics.summarizeFunding("BNBUSDT",null,start,now).getString("status"))
        assertEquals("SIGNED_FUNDING_SHAPE_UNVERIFIED",
            FuturesSignedTradeForensics.summarizeFunding("BNBUSDT",listOf(row,row),start,now).getString("status"))
        val other=JSONObject(row.toString()).put("asset","BNB")
        val malformed=FuturesSignedTradeForensics.summarizeFunding("BNBUSDT",listOf(other),start,now)
        assertTrue(malformed.isNull("funding_usdt"))
        assertEquals("SIGNED_FUNDING_SHAPE_UNVERIFIED",malformed.getString("status"))
    }

}