package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigDecimal

class BinanceTradeEvidenceTest {
    private fun trade(
        id: String = "10",
        orderId: String = "9001",
        symbol: String = "BTCUSDT",
        qty: String = "0.010",
        price: String = "100",
        quoteQty: String = "1.000",
        commission: String = "0.001",
        commissionAsset: String = "BNB"
    ) = JSONObject()
        .put("id", id)
        .put("orderId", orderId)
        .put("symbol", symbol)
        .put("qty", qty)
        .put("price", price)
        .put("quoteQty", quoteQty)
        .put("commission", commission)
        .put("commissionAsset", commissionAsset)
        .put("time", 1760000000000L)
        .put("isBuyer", true)
        .put("isMaker", false)

    private fun verify(vararg rows: JSONObject, expectedQty: String = "0.010") =
        BinanceTradeEvidence.verify(
            "BTCUSDT", "9001", BigDecimal(expectedQty), JSONArray().apply {
                rows.forEach { put(it) }
            }
        )

    @Test fun validTradeEvidenceMatchesExpectedExecutedQuantity() {
        val result = verify(trade())
        assertEquals(1, result.tradeCount)
        assertEquals(listOf("10"), result.tradeIds)
        assertEquals(0, result.executedQty.compareTo(BigDecimal("0.010")))
    }

    @Test fun zeroExecutionCanBeVerifiedOnlyAgainstEmptyTradeHistory() {
        assertEquals(0, verify(expectedQty = "0").tradeCount)
        assertThrows(IllegalArgumentException::class.java) {
            verify(trade(), expectedQty = "0")
        }
    }

    @Test fun duplicateTradeIdsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { verify(trade(), trade()) }
    }

    @Test fun mismatchedSymbolAndOrderIdAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { verify(trade(symbol = "ETHUSDT")) }
        assertThrows(IllegalArgumentException::class.java) { verify(trade(orderId = "9002")) }
    }

    @Test fun missingTradeIdAndInvalidEconomicsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { verify(trade(id = "")) }
        assertThrows(IllegalArgumentException::class.java) { verify(trade(qty = "0")) }
        assertThrows(IllegalArgumentException::class.java) { verify(trade(price = "0")) }
        assertThrows(IllegalArgumentException::class.java) { verify(trade(quoteQty = "0")) }
        assertThrows(IllegalArgumentException::class.java) { verify(trade(commission = "-0.1")) }
        assertThrows(IllegalArgumentException::class.java) {
            verify(trade(commission = "0.1", commissionAsset = ""))
        }
    }

    @Test fun quoteQuantityConflictAndMissingRequiredFieldsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { verify(trade(quoteQty = "9")) }
        val missingTime = trade().put("time", 0L)
        assertThrows(IllegalArgumentException::class.java) { verify(missingTime) }
        val missingFlags = trade()
        missingFlags.remove("isMaker")
        assertThrows(IllegalArgumentException::class.java) { verify(missingFlags) }
    }

    @Test fun incompleteOrContradictoryExecutedQuantityIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { verify(trade(), expectedQty = "0.020") }
        assertThrows(IllegalArgumentException::class.java) { verify(expectedQty = "0.010") }
    }
}
