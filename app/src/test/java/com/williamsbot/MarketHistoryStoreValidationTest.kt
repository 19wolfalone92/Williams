package com.williamsbot

import com.williamsbot.MarketHistoryStore.Candle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketHistoryStoreValidationTest {
    private val intervalMs = 60_000L
    private val start = 1_700_000_000_000L
    private val expected = start + 3 * intervalMs

    private fun candle(openTime: Long, closeTime: Long = openTime + intervalMs - 1L) =
        Candle(
            openTime = openTime,
            open = 1.0,
            high = 2.0,
            low = 0.5,
            close = 1.5,
            volume = 10.0,
            closeTime = closeTime
        )

    @Test
    fun validClosedContiguousGap_isAccepted() {
        val candles = listOf(
            candle(start),
            candle(start + intervalMs),
            candle(start + 2 * intervalMs)
        )

        assertTrue(
            MarketHistoryStore.validateFetchedGap(
                candles, start, expected, intervalMs
            )
        )
    }

    @Test
    fun gapMustStartExactlyAtGapStart() {
        val candles = listOf(
            candle(start + intervalMs),
            candle(start + 2 * intervalMs)
        )

        assertFalse(
            MarketHistoryStore.validateFetchedGap(
                candles, start, expected, intervalMs
            )
        )
    }

    @Test
    fun internalHole_isRejected() {
        val candles = listOf(
            candle(start),
            candle(start + 2 * intervalMs),
            candle(start + 3 * intervalMs)
        )

        assertFalse(
            MarketHistoryStore.validateFetchedGap(
                candles, start, start + 4 * intervalMs, intervalMs
            )
        )
    }

    @Test
    fun finalCandleMustEndExactlyAtExpectedTimestamp() {
        val candles = listOf(
            candle(start),
            candle(start + intervalMs),
            candle(start + 2 * intervalMs)
        )

        assertFalse(
            MarketHistoryStore.validateFetchedGap(
                candles, start, expected + intervalMs, intervalMs
            )
        )
    }

    @Test
    fun openCandle_isRejected() {
        val candles = listOf(
            candle(start),
            candle(start + intervalMs),
            candle(start + 2 * intervalMs, closeTime = expected)
        )

        assertFalse(
            MarketHistoryStore.validateFetchedGap(
                candles, start, expected, intervalMs
            )
        )
    }

    @Test
    fun candleOutsideBound_isRejected() {
        val candles = listOf(
            candle(start),
            candle(start + intervalMs),
            candle(start + 2 * intervalMs),
            candle(start + 3 * intervalMs)
        )

        assertFalse(
            MarketHistoryStore.validateFetchedGap(
                candles, start, expected, intervalMs
            )
        )
    }
}
