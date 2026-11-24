package com.williamsbot

import org.junit.Assert.*
import org.junit.Test

/**
 * Regression: native Binance USD-M OHLCV open times are anchored to UTC.
 * A perfectly continuous but 15-minute-shifted H1/H4 series is NOT an
 * exchange-native closed-bar prefix. Such candles must not enter book
 * evidence, stop review or causal historical replay.
 */
class WilliamsCausalCandleGridTest {
    private val hour = 3_600_000L
    private val minute = 60_000L

    private fun rows(frameMs: Long, count: Int, origin: Long = 0L) =
        (0 until count).map { i ->
            WilliamsWaveBar(origin + i * frameMs,
                o = 101.0, h = 104.0, l = 98.0, c = 102.0, v = 1.0)
        }

    private fun rejectsShiftedSeries(frame: String, step: Long, origin: Long) {
        val shifted = rows(step, 48, origin + 15 * minute)
        try {
            closedWilliamsWaveBars(shifted, frame, origin + 48 * step + 15 * minute)
            fail("Off-grid $frame history was accepted as signed Binance candle evidence")
        } catch (expected: IllegalArgumentException) {
            assertEquals("OFF_UTC_INTERVAL_GRID", expected.message)
        }
    }

    @Test fun alignedH1H4AndD1PassButFifteenMinuteShiftsAreRejected() {
        for ((frame, step) in listOf(
            "1h" to hour,
            "4h" to 4 * hour,
            "1d" to 24 * hour
        )) {
            assertEquals(48, closedWilliamsWaveBars(rows(step,48),frame,48*step).size)
            rejectsShiftedSeries(frame,step,0L)
        }
    }

    @Test fun weeklyOriginIsMondayRatherThanUnixEpochThursday() {
        // Binance USD-M 1w klines start Monday 00:00 UTC, Jan 5 1970.
        val monday = 4L * 24 * hour
        val week = 7L * 24 * hour
        assertEquals(48,closedWilliamsWaveBars(
            rows(week,48,monday),"1w",monday+48*week).size)
        rejectsShiftedSeries("1w",week,monday)
    }

    @Test fun stopReviewNeverPromotesShiftedYetContiguousH1IntoProtectionProposal() {
        val h1 = rows(hour,48,15*minute)
        val h4 = rows(4*hour,48)
        val result=reviewWilliamsTc2StopTightening(
            direction="LONG",oldStop=90.0,entry=100.0,initialStop=90.0,
            mark=105.0,tick=0.01,openedAtMs=43*hour,asOfMs=48*hour+15*minute,
            h1Input=h1,h4Input=h4)
        assertNull(result.proposal)
        assertEquals("CLOSED_H1_HISTORY_NOT_VERIFIED",result.blocker)
        assertTrue(result.evidence.getBoolean("existing_exchange_stop_retained_if_blocked"))
    }
}
