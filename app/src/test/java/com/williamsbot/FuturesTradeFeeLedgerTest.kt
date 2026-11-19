package com.williamsbot

import org.junit.Assert.*
import org.junit.Test

class FuturesTradeFeeLedgerTest {
    @Test fun noTradeEvidenceIsNotCertifiedZeroCommission() {
        val ledger=FuturesTradeFeeLedger("BTC",100.0)
        assertEquals(0.0,ledger.result().first,0.0)
        assertFalse(ledger.result().second)
        ledger.add("1","2","NEW",10.0,"USDT",100.0)
        assertFalse(ledger.result().second)
    }
    @Test fun historicalDuplicateWsTradeIdsAreCountedOnceAcrossChangedTimestamps() {
        val ledger=FuturesTradeFeeLedger("BTC",100.0)
        ledger.add("10","101","TRADE",0.01,"USDT",100.0)
        ledger.add("10","101","TRADE",0.01,"USDT",100.0)
        ledger.add("10","102","TRADE",0.0002,"BTC",105.0)
        assertEquals(0.031,ledger.result().first,1e-10)
        assertTrue(ledger.result().second)
    }
    @Test fun contradictoryDuplicateFeeIsUnverifiedNotDoubleCounted() {
        val ledger=FuturesTradeFeeLedger("BTC",100.0)
        ledger.add("10","101","TRADE",0.01,"USDT",100.0)
        ledger.add("10","101","TRADE",0.02,"USDT",100.0)
        assertEquals(0.01,ledger.result().first,0.0)
        assertFalse(ledger.result().second)
    }
    @Test fun positiveCommissionWithMissingAssetOrUnknownCurrencyIsNotKnown() {
        for(asset in listOf("","BNB")) {
            val ledger=FuturesTradeFeeLedger("BTC",100.0)
            ledger.add("10","101","TRADE",0.01,asset,100.0)
            assertFalse(ledger.result().second)
            assertEquals(0.0,ledger.result().first,0.0)
        }
    }
    @Test fun invalidAmountsAndMissingFillPriceCannotCertifyExactFees() {
        for(amount in listOf(Double.NaN,Double.POSITIVE_INFINITY,-0.2)) {
            val ledger=FuturesTradeFeeLedger("BTC",100.0)
            ledger.add("10","101","TRADE",amount,"USDT",100.0)
            assertFalse(ledger.result().second)
            assertEquals(0.0,ledger.result().first,0.0)
        }
        val approximate=FuturesTradeFeeLedger("BTC",100.0)
        approximate.add("10","101","TRADE",0.01,"BTC",0.0)
        assertEquals(1.0,approximate.result().first,0.0)
        assertFalse("Exit-price conversion is only an estimate",approximate.result().second)
        val unknownTrade=FuturesTradeFeeLedger("BTC",100.0)
        unknownTrade.add("10","-1","TRADE",0.1,"USDT",100.0)
        assertFalse(unknownTrade.result().second)
    }
    @Test fun rawExchangeReceiptWithMissingCommissionCannotProveHistoricalZero() {
        fun event(fee:String?,asset:String="USDT") = org.json.JSONObject()
            .put("e","ORDER_TRADE_UPDATE").put("E",100L).put("T",99L)
            .put("o",org.json.JSONObject()
                .put("s","BTCUSDT").put("i","77").put("t","111")
                .put("x","TRADE").put("T",99L)
                .put("l","0.05").put("L","100")
                .put("N",asset).apply { if(fee!=null) put("n",fee) }).toString()
        assertTrue(futuresTradeFeeHasCompleteRawEvidence(
            event("0"),"BTCUSDT","77","111"))
        assertTrue(futuresTradeFeeHasCompleteRawEvidence(
            event("0.01"),"BTCUSDT","77","111"))
        for(fee in listOf(null,"not_numeric","NaN","-1","Infinity")) {
            assertFalse("$fee should fail-closed",futuresTradeFeeHasCompleteRawEvidence(
                event(fee),"BTCUSDT","77","111"))
        }
        assertFalse(futuresTradeFeeHasCompleteRawEvidence(
            event("0",""),"BTCUSDT","77","111"))
        assertFalse(futuresTradeFeeHasCompleteRawEvidence(
            event("0.01"),"ETHUSDT","77","111"))
        assertFalse(futuresTradeFeeHasCompleteRawEvidence(
            event("0.01"),"BTCUSDT","different","111"))
        val historical=FuturesTradeFeeLedger("BTC",100.0)
        historical.add("77","111","TRADE",0.0,"USDT",100.0,
            signedRawEvidenceValid=false)
        assertFalse(historical.result().second)
    }

    @Test fun duplicateTradeIdInDifferentOrdersIsIndependent() {
        val ledger=FuturesTradeFeeLedger("BTC",100.0)
        ledger.add("10","42","TRADE",0.2,"USDT",100.0)
        ledger.add("11","42","TRADE",0.3,"USDT",100.0)
        assertEquals(0.5,ledger.result().first,1e-10)
        assertTrue(ledger.result().second)
    }
}
