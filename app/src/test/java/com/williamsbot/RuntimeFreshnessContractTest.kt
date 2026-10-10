package com.williamsbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeFreshnessContractTest {
    @Test
    fun unavailableOrUnknownMarketDataIsNeverFresh() {
        assertFalse(RuntimeFreshnessContract.isFresh(false, 10_000L, 10_001L, 30_000L))
        assertFalse(RuntimeFreshnessContract.isFresh(true, 0L, 10_001L, 30_000L))
    }

    @Test
    fun marketDataFreshnessHonorsInclusiveAgeBoundary() {
        assertTrue(RuntimeFreshnessContract.isFresh(true, 10_000L, 40_000L, 30_000L))
        assertFalse(RuntimeFreshnessContract.isFresh(true, 10_000L, 40_001L, 30_000L))
    }

    @Test
    fun clockMovingBeforeSuccessTimestampFailsClosed() {
        assertEquals(null, RuntimeFreshnessContract.ageMs(20_000L, 19_999L))
        assertFalse(RuntimeFreshnessContract.isFresh(true, 20_000L, 19_999L, 30_000L))
    }

    @Test
    fun scanSnapshotNeedsSupportedStateFreshTimestampAndNoError() {
        assertTrue(RuntimeFreshnessContract.isScanSnapshotFresh("READY", 50_000L, 55_000L, 60_000L, null))
        assertTrue(RuntimeFreshnessContract.isScanSnapshotFresh("RUNNING", 50_000L, 55_000L, 60_000L, ""))
        assertFalse(RuntimeFreshnessContract.isScanSnapshotFresh("NOT_RUN", 50_000L, 55_000L, 60_000L, null))
        assertFalse(RuntimeFreshnessContract.isScanSnapshotFresh("READY", 0L, 55_000L, 60_000L, null))
        assertFalse(RuntimeFreshnessContract.isScanSnapshotFresh("READY", 1L, 70_001L, 60_000L, null))
        assertFalse(RuntimeFreshnessContract.isScanSnapshotFresh("READY", 50_000L, 55_000L, 60_000L, "data unavailable"))
    }
}
