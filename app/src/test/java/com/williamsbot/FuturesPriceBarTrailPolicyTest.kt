package com.williamsbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FuturesPriceBarTrailPolicyTest {
    @Test
    fun longTrailUsesLowestLowOfLastThreeClosedBarsAndPlacesStopOneTickBelow() {
        val proposed = FuturesPriceBarTrailPolicy.propose(
            direction = "LONG",
            lows = listOf(90.0, 91.0, 100.0, 101.0, 99.5),
            highs = listOf(92.0, 93.0, 103.0, 104.0, 102.0),
            tickSize = 0.1,
            trailingBars = 3
        )
        assertEquals(99.5, proposed.structuralExtreme, 1e-10)
        assertEquals(99.4, proposed.rawStopPrice, 1e-10)
    }

    @Test
    fun shortTrailUsesHighestHighOfLastFiveClosedBarsAndPlacesStopOneTickAbove() {
        val proposed = FuturesPriceBarTrailPolicy.propose(
            direction = "SHORT",
            lows = listOf(90.0, 91.0, 92.0, 93.0, 94.0),
            highs = listOf(92.0, 95.0, 94.0, 96.0, 93.0),
            tickSize = 0.1,
            trailingBars = 5
        )
        assertEquals(96.0, proposed.structuralExtreme, 1e-10)
        assertEquals(96.1, proposed.rawStopPrice, 1e-10)
    }

    @Test
    fun trailRejectsUnknownDirectionBadWindowAndMalformedPriceData() {
        assertThrows(IllegalArgumentException::class.java) {
            FuturesPriceBarTrailPolicy.propose(
                "FLAT", listOf(1.0, 2.0, 3.0), listOf(2.0, 3.0, 4.0), 0.1, 3
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            FuturesPriceBarTrailPolicy.propose(
                "LONG", listOf(1.0, 2.0), listOf(2.0, 3.0), 0.1, 3
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            FuturesPriceBarTrailPolicy.propose(
                "SHORT", listOf(1.0, Double.NaN, 3.0), listOf(2.0, 3.0, 4.0), 0.1, 3
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            FuturesPriceBarTrailPolicy.propose(
                "LONG", listOf(1.0, 2.0, 3.0), listOf(2.0, 3.0, 4.0), 0.0, 3
            )
        }
    }
}
