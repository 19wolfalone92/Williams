package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FuturesContractRulesTest {
    private fun exchangeInfo(
        minQty: String = "0.001",
        maxQty: String = "1000",
        stepSize: String = "0.001",
        tickSize: String = "0.1",
        minNotional: String = "5"
    ) = JSONObject()
        .put("symbols", JSONArray().put(
            JSONObject()
                .put("symbol", "BTCUSDT")
                .put("status", "TRADING")
                .put("filters", JSONArray()
                    .put(JSONObject().put("filterType", "LOT_SIZE")
                        .put("minQty", minQty).put("maxQty", maxQty).put("stepSize", stepSize))
                    .put(JSONObject().put("filterType", "PRICE_FILTER")
                        .put("minPrice", "0.1").put("maxPrice", "1000000").put("tickSize", tickSize))
                    .put(JSONObject().put("filterType", "MIN_NOTIONAL")
                        .put("notional", minNotional))
                )
        ))

    @Test
    fun acceptsQuantityAndPricesAlignedToExchangeFilters() {
        assertNull(futuresContractRuleBlocker(
            exchangeInfo(), "BTCUSDT", "0.010", "65000.1", "64900.2"
        ))
    }

    @Test
    fun rejectsQuantityThatDoesNotMatchStepSize() {
        assertNotNull(futuresContractRuleBlocker(
            exchangeInfo(), "BTCUSDT", "0.0105", "65000.1", "64900.2"
        ))
    }

    @Test
    fun rejectsEntryOrStopOffPriceTick() {
        assertNotNull(futuresContractRuleBlocker(
            exchangeInfo(), "BTCUSDT", "0.010", "65000.15", "64900.2"
        ))
        assertNotNull(futuresContractRuleBlocker(
            exchangeInfo(), "BTCUSDT", "0.010", "65000.1", "64900.25"
        ))
    }

    @Test
    fun rejectsBelowMinimumNotionalAndUnapprovedContract() {
        assertNotNull(futuresContractRuleBlocker(
            exchangeInfo(minNotional = "1000"), "BTCUSDT", "0.010", "65000.1", "64900.2"
        ))
        assertNotNull(futuresContractRuleBlocker(
            exchangeInfo(), "ETHUSDT", "0.010", "65000.1", "64900.2"
        ))
    }

    @Test
    fun rejectsInactiveContractsAndQuantityOutsideLotLimits() {
        val inactive = exchangeInfo().put("symbols", JSONArray().put(
            JSONObject()
                .put("symbol", "BTCUSDT")
                .put("status", "HALT")
                .put("filters", exchangeInfo().getJSONArray("symbols").getJSONObject(0).getJSONArray("filters"))
        ))
        assertNotNull(futuresContractRuleBlocker(inactive, "BTCUSDT", "0.010", "65000.1", "64900.2"))
        assertNotNull(futuresContractRuleBlocker(exchangeInfo(minQty = "0.02"), "BTCUSDT", "0.010", "65000.1", "64900.2"))
        assertNotNull(futuresContractRuleBlocker(exchangeInfo(maxQty = "0.005"), "BTCUSDT", "0.010", "65000.1", "64900.2"))
    }

    @Test
    fun enforcesOptionalMaximumNotionalWhenExchangePublishesIt() {
        val info = exchangeInfo()
        val filters = info.getJSONArray("symbols").getJSONObject(0).getJSONArray("filters")
        filters.getJSONObject(2).put("maxNotional", "100")
        assertNotNull(futuresContractRuleBlocker(info, "BTCUSDT", "0.010", "65000.1", "64900.2"))
    }

    @Test
    fun missingFiltersFailClosed() {
        assertNotNull(futuresContractRuleBlocker(
            JSONObject().put("symbols", JSONArray().put(
                JSONObject().put("symbol", "BTCUSDT").put("status", "TRADING")
            )),
            "BTCUSDT", "0.010", "65000.1", "64900.2"
        ))
    }
}
