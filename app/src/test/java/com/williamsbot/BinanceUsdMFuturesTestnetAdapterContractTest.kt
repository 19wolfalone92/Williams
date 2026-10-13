package com.williamsbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BinanceUsdMFuturesTestnetAdapterContractTest {
    @Test
    fun approvedUniverseIsExactlyTheRequiredTenUsdtContracts() {
        assertEquals(
            setOf(
                "BTCUSDT", "ETHUSDT", "BNBUSDT", "SOLUSDT", "XRPUSDT",
                "DOGEUSDT", "ADAUSDT", "LINKUSDT", "AVAXUSDT", "LTCUSDT"
            ),
            BinanceUsdMFuturesTestnetAdapter.APPROVED_SYMBOLS
        )
    }

    @Test
    fun universeContainsOnlyUsdtMarginedContracts() {
        assertTrue(
            BinanceUsdMFuturesTestnetAdapter.APPROVED_SYMBOLS.all {
                it.endsWith("USDT") && it.matches(Regex("[A-Z0-9]{5,20}"))
            }
        )
    }
    @Test
    fun longAndShortEntryAndExitSidesAreOppositeAndNeverAmbiguous() {
        assertEquals("BUY", BinanceUsdMFuturesTestnetAdapter.entrySide("LONG"))
        assertEquals("SELL", BinanceUsdMFuturesTestnetAdapter.entrySide("SHORT"))
        assertEquals("SELL", BinanceUsdMFuturesTestnetAdapter.closeSide("LONG"))
        assertEquals("BUY", BinanceUsdMFuturesTestnetAdapter.closeSide("SHORT"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidDirectionCannotReachOrderSideMapping() {
        BinanceUsdMFuturesTestnetAdapter.entrySide("FLAT")
    }

}
