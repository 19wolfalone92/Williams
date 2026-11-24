package com.williamsbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ClosedCandleFingerprintTest {
    private val first = CandleN(3600000L, 10.0, 12.0, 9.0, 11.0, 5.0)
    private val second = first.copy(t = 7200000L)

    @Test fun equivalentIndependentListsShareFingerprint() {
        assertEquals(closedCandleFingerprint(listOf(first, second)),
            closedCandleFingerprint(listOf(first.copy(), second.copy())))
    }

    @Test fun correctedPricesAndVolumeChangeFingerprintWithoutTimestampChange() {
        val original = closedCandleFingerprint(listOf(first, second))
        for (corrected in listOf(first.copy(o=10.5), first.copy(h=13.0),
            first.copy(l=8.0), first.copy(c=10.5), first.copy(v=6.0))) {
            assertNotEquals(original, closedCandleFingerprint(listOf(corrected, second)))
        }
    }

    @Test fun orderAndWindowArePartOfIdentity() {
        val original = closedCandleFingerprint(listOf(first, second))
        assertNotEquals(original, closedCandleFingerprint(listOf(second, first)))
        assertNotEquals(original, closedCandleFingerprint(listOf(second)))
        assertEquals(64, original.length)
    }
}
