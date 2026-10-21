package com.williamsbot

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class FuturesExternalProtectionTest {
    private fun stop(direction: String) = JSONObject().put("symbol", "BNBUSDT")
        .put("positionSide", "BOTH").put("orderType", "STOP_MARKET").put("algoStatus", "NEW")
        .put("side", if (direction == "LONG") "SELL" else "BUY")
        .put("workingType", "MARK_PRICE").put("triggerPrice", if (direction == "LONG") "740" else "750")
        .put("closePosition", true).put("algoId", "123").put("clientAlgoId", "aos_manual")
    private fun matches(order: JSONObject, direction: String) =
        isFullPositionFuturesStop(order, "BNBUSDT", direction, BigDecimal("0.64"), 745.0)

    @Test fun bothDirectionsRequireAFullClosingStopWithExplicitLiveIdentity() {
        for (direction in listOf("LONG", "SHORT")) {
            assertTrue(matches(stop(direction), direction))
            for ((key, value) in listOf("symbol" to "ETHUSDT", "positionSide" to "LONG",
                "orderType" to "TAKE_PROFIT_MARKET", "algoStatus" to "CANCELED",
                "side" to if (direction == "LONG") "BUY" else "SELL",
                "workingType" to "", "algoId" to "", "clientAlgoId" to "", "triggerPrice" to "745")) {
                assertFalse("$direction: $key=$value", matches(stop(direction).put(key, value), direction))
            }
        }
    }

    @Test fun quantityStopsMustBeReduceOnlyAndCoverTheEntirePosition() {
        for (direction in listOf("LONG", "SHORT")) {
            val order = stop(direction).put("closePosition", false).put("quantity", "0.64")
            assertFalse(matches(order, direction))
            order.put("reduceOnly", true)
            assertTrue(matches(order, direction))
            order.put("quantity", "0.639")
            assertFalse(matches(order, direction))
        }
    }
}
