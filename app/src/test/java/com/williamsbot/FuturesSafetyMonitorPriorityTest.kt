package com.williamsbot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A read-only H1 scanner can never starve signed protective REST supervision. */
class FuturesSafetyMonitorPriorityTest {
    private val now=1_800_000_000_000L
    private fun due(
        scanning:Boolean,
        lastProofAgeMs:Long,
        coordinatorState:String,
        reconciliationActive:Boolean=false,
        safetyLatchUnresolved:Boolean=false,
        lastAttemptAgeMs:Long=60_000L
    ):Boolean = shouldScheduleIndependentFuturesMonitor(
        nowMs=now,
        lastSuccessfulMs=now-lastProofAgeMs,
        lastAttemptMs=now-lastAttemptAgeMs,
        coordinatorState=coordinatorState,
        scanning=scanning,
        reconciliationActive=reconciliationActive,
        safetyLatchUnresolved=safetyLatchUnresolved
    )

    @Test fun openProtectedPositionIsSupervisedEvenDuringLongRunningScanner() {
        assertTrue(due(scanning=true,lastProofAgeMs=4_000L,coordinatorState="OPEN_PROTECTED"))
        assertTrue(due(scanning=true,lastProofAgeMs=10_000L,coordinatorState="POSITION_UNPROTECTED"))
        assertTrue(due(scanning=true,lastProofAgeMs=10_000L,coordinatorState="ENTRY_PENDING"))
        assertTrue(due(scanning=true,lastProofAgeMs=60_000L,coordinatorState="RECONCILE_REQUIRED"))
    }

    @Test fun flatAccountRenewsStaleSignedRestProofEvenDuringScanner() {
        assertTrue(due(scanning=true,lastProofAgeMs=90_000L,coordinatorState="READY"))
        assertFalse(due(scanning=true,lastProofAgeMs=40_000L,coordinatorState="READY"))
        assertTrue(due(scanning=true,lastProofAgeMs=45_000L,coordinatorState="READY"))
        assertTrue(due(scanning=true,lastProofAgeMs=2_600L,coordinatorState="READY",safetyLatchUnresolved=true))
    }

    @Test fun existingReconciliationAndCadenceStillBlockDuplicateSignedMutations() {
        assertFalse(due(scanning=true,lastProofAgeMs=120_000L,coordinatorState="READY",
            reconciliationActive=true))
        assertFalse(due(scanning=false,lastProofAgeMs=120_000L,coordinatorState="READY",
            reconciliationActive=true))
        assertFalse(due(scanning=true,lastProofAgeMs=10_000L,coordinatorState="OPEN_PROTECTED",
            lastAttemptAgeMs=1_000L))
        assertFalse(due(scanning=true,lastProofAgeMs=44_999L,coordinatorState="READY"))
    }
}
