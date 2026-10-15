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
    fun chartIntervalContractIncludesAllSupportedUsdMFuturesPeriods() {
        assertEquals(
            listOf(
                "1m", "3m", "5m", "15m", "30m", "1h", "2h", "4h",
                "6h", "8h", "12h", "1d", "3d", "1w", "1M"
            ),
            APPROVED_FUTURES_KLINE_INTERVALS
        )
        // Both chart reads and account/coordinator reads consume this canonical
        // allow-list, so weekly/monthly views cannot silently fail in one path.
        assertTrue(APPROVED_FUTURES_KLINE_INTERVALS.containsAll(listOf("3d", "1w", "1M")))
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
