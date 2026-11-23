package com.williamsbot

import org.junit.Assert.*
import org.junit.Test

/** Exchange-native candles must be on the UTC interval grid at every runtime gate.
 * This protects even a contiguous 160-bar SQLite cache whose opening minute
 * was shifted during a reconnect or from an incorrect data source. */
class FuturesNativeUtcCandleGridRegressionTest {
    private val hour=3_600_000L
    private val day=86_400_000L
    private val week=7L*day
    private val jan2026=1_767_225_600_000L

    @Test fun contiguousButShiftedCacheIsRejectedForH1H4AndM15() {
        val market=BinanceUsdMFuturesMarketData()
        for((interval,step) in listOf("1h" to hour,"4h" to 4*hour,"15m" to 900_000L)) {
            val aligned=(0 until 48).map { jan2026 + it*step }
            val shifted=aligned.map { it+15*60_000L }
            assertTrue(interval, futuresClosedCandleTimesContiguous(aligned,step))
            assertTrue(interval,market.nativeCandleTimelineContiguous(aligned,interval))
            assertFalse(interval, futuresClosedCandleTimesContiguous(shifted,step))
            assertFalse(interval,market.nativeCandleTimelineContiguous(shifted,interval))
            assertFalse(interval,market.nativeKlineOpenAligned(shifted[0],interval))
        }
    }

    @Test fun weeklyBarsAnchorToMondayUtcRatherThanEpochThursday() {
        val market=BinanceUsdMFuturesMarketData()
        val monday=4L*day
        val mondays=(0 until 48).map { monday+it*week }
        assertTrue(futuresClosedCandleTimesContiguous(mondays,week))
        assertTrue(market.nativeCandleTimelineContiguous(mondays,"1w"))
        assertTrue(market.nativeKlineOpenAligned(monday,"1w"))
        assertFalse(market.nativeKlineOpenAligned(monday+day,"1w"))
        assertFalse(futuresClosedCandleTimesContiguous(mondays.map { it+day },week))
        assertFalse(market.nativeCandleTimelineContiguous(mondays.map { it+day },"1w"))
    }

    @Test fun monthlyCacheNeedsFirstCalendarDayNotMerelySameDayNumber() {
        val market=BinanceUsdMFuturesMarketData()
        val feb2026=1_769_904_000_000L
        assertTrue(market.nativeKlineOpenAligned(jan2026,"1M"))
        assertTrue(market.nativeKlineOpenAligned(feb2026,"1M"))
        assertFalse(market.nativeKlineOpenAligned(jan2026+hour,"1M"))
        assertFalse(market.nativeKlineOpenAligned(jan2026+day,"1M"))
        assertFalse(market.nativeCandleTimelineContiguous(listOf(jan2026+hour,feb2026+hour),"1M"))
        assertFalse(market.isLastClosedCandleCurrent(jan2026+hour,"1M",feb2026+day))
        assertFalse(market.isKlineOpenTimeClosed(jan2026+hour,"1M",feb2026))
    }
}
