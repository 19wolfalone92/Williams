package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FuturesLeveragePolicyTest {
    private fun brackets() = JSONObject("""{"symbol":"BTCUSDT","brackets":[
        {"notionalFloor":0,"notionalCap":1000,"initialLeverage":125,"maintMarginRatio":0.005},
        {"notionalFloor":1000,"notionalCap":10000,"initialLeverage":10,"maintMarginRatio":0.01}
    ]}""")

    @Test fun settingsRoundTripAllTenSymbolsAndInheritDefault() {
        val overrides = BinanceUsdMFuturesTestnetAdapter.APPROVED_SYMBOLS.withIndex()
            .associate { it.value to it.index + 1 }
        val original = FuturesLeverageSettings(5, overrides)
        assertEquals(original, FuturesLeverageSettings.fromJson(original.toJson()))
        assertEquals(10, original.resolvedJson().length())
        assertEquals(5, FuturesLeverageSettings(5).forSymbol("BTCUSDT"))
        assertEquals(3, FuturesLeverageSettings(5, mapOf("BTCUSDT" to 3)).forSymbol("BTCUSDT"))
    }

    @Test fun malformedValuesNeverSilentlyClampOrTruncate() {
        listOf(null, 0, -1, 126, true, 2.5, "2.0", "NaN", "", "5x").forEach { value ->
            try { parseFuturesLeverage(value); fail("Must reject $value") }
            catch (_: IllegalArgumentException) { }
        }
        listOf(1, 2, 5, 10, 20, 50, 125).forEach { assertEquals(it, parseFuturesLeverage(it)) }
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownSymbolCannotBeSaved() {
        FuturesLeverageSettings(5, mapOf("OTHERUSDT" to 10))
    }

    @Test fun objectAndArrayBracketsBothWorkAndBoundariesUseTheHigherTier() {
        assertNull(futuresLeverageBlocker(brackets(), "BTCUSDT", 20, 999.0, 100.0, 99.0))
        assertNull(futuresLeverageBlocker(JSONArray().put(brackets()), "BTCUSDT", 5, 500.0, 100.0, 99.0))
        assertTrue(futuresLeverageBlocker(brackets(), "BTCUSDT", 20, 1000.0, 100.0, 99.0)!!.contains("LEVERAGE_LIMIT"))
        assertTrue(futuresLeverageBlocker(brackets(), "BTCUSDT", 20, 999.0, 100.0, 101.0)!!.contains("LEVERAGE_LIMIT"))
        assertTrue(futuresLeverageBlocker(brackets(), "BTCUSDT", 2, 10000.0, 100.0, 99.0)!!.contains("NOTIONAL_CAP"))
    }

    @Test fun excessiveLeverageCannotExhaustMarginBeforeEitherStructuralStop() {
        listOf(99.0, 101.0).forEach { stop ->
            assertNull(futuresLeverageBlocker(brackets(), "BTCUSDT", 10, 500.0, 100.0, stop))
            assertTrue(futuresLeverageBlocker(brackets(), "BTCUSDT", 125, 500.0, 100.0, stop)!!.contains("STOP_BUFFER"))
        }
    }

    @Test fun missingMalformedOrDuplicateExchangeLimitsFailClosed() {
        listOf(JSONObject(), JSONArray(), JSONArray().put(brackets()).put(brackets()),
            JSONObject("""{"symbol":"BTCUSDT","brackets":[]}"""),
            JSONObject("""{"symbol":"ETHUSDT","brackets":[]}""")
        ).forEach { assertNotNull(futuresLeverageBlocker(it, "BTCUSDT", 2, 500.0, 100.0, 99.0)) }
        val gapped = brackets().apply { getJSONArray("brackets").getJSONObject(1).put("notionalFloor", 1001) }
        assertNotNull(futuresLeverageBlocker(gapped, "BTCUSDT", 2, 500.0, 100.0, 99.0))
    }

    @Test fun leverageChangesRequiredMarginAndNeverStopRiskForLongOrShort() {
        listOf("LONG" to "99", "SHORT" to "101").forEach { (direction, stop) ->
            fun risk(leverage: Double, available: Double = 1000.0) = assessFuturesEntryRisk(
                direction, "1", "100", stop, 10000.0, available, 0.002, leverage = leverage)
            val low = risk(2.0)
            val high = risk(10.0)
            assertNull(low.blocker)
            assertNull(high.blocker)
            assertEquals(low.estimatedAllInLossUsdt, high.estimatedAllInLossUsdt, 0.0)
            assertEquals(low.initialMarginUsdt / 5, high.initialMarginUsdt, 1e-9)
            assertNotNull(risk(10.0, 1.0).blocker)
        }
    }
}
