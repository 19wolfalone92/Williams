package com.williamsbot

import org.junit.Assert.*
import org.junit.Test

class CockpitChartRefreshCadenceTest {
    private val h1 = 3_600_000L

    @Test fun h1BetweenClosesDoesNotRebuildSixThousandBarsEveryThirtySeconds() {
        // Last closed bar opened at 10:00; on-screen at 11:25.
        // The next bar (11:00–12:00) cannot close until 12:00 UTC.
        val at=11*h1+25*60_000L
        val delay=cockpitChartNextRefreshDelayMs(10*h1,"1h",at)
        assertTrue(delay>30_000L)
        assertTrue(delay<=300_000L) // bounded UI probe, not sleep until 12:00
    }

    @Test fun minuteChartAndSoonClosingH1KeepInteractiveCadence() {
        val now=11*h1+59*60_000L+45_000L
        assertEquals(30_000L,cockpitChartNextRefreshDelayMs(10*h1,"1h",now))
        assertEquals(30_000L,cockpitChartNextRefreshDelayMs(11*h1+58*60_000L,
            "1m",11*h1+59*60_000L+45_000L))
    }

    @Test fun missingHistoryUnsupportedFrameAndPastCloseRetry() {
        assertEquals(30_000L,cockpitChartNextRefreshDelayMs(0L,"1h",11*h1))
        assertEquals(30_000L,cockpitChartNextRefreshDelayMs(9*h1,"unknown",11*h1))
        assertEquals(30_000L,cockpitChartNextRefreshDelayMs(9*h1,"1h",12*h1))
    }

    @Test fun fourHourAndDailyChartNeedNoSubMinuteRegeneration() {
        val now=13*h1
        assertEquals(300_000L,cockpitChartNextRefreshDelayMs(8*h1,"4h",now))
        assertEquals(300_000L,cockpitChartNextRefreshDelayMs(0L+86_400_000L,
            "1d",86_400_000L+2*h1))
    }
}
