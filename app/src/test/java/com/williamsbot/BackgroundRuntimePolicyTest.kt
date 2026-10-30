package com.williamsbot

import org.junit.Assert.*
import org.junit.Test

class BackgroundRuntimePolicyTest {
    @Test fun repeatedForegroundServiceStartDoesNotBootstrapExistingWorker() {
        val alive=BackgroundRuntimeSnapshot(autoRunRequested=true,running=true,workerAlive=true,
            lastWorkerLoopElapsedMs=1_000L)
        assertTrue(BackgroundRuntimePolicy.alreadySupervising(alive))
        // An alive but wedged worker must be handled by liveness/execution fences,
        // never by creating a second worker sharing the same exchange account.
        assertTrue(BackgroundRuntimePolicy.monitorStale(alive,181_000L))
        assertTrue(BackgroundRuntimePolicy.alreadySupervising(alive))
        assertFalse(BackgroundRuntimePolicy.alreadySupervising(alive.copy(workerAlive=false)))
        assertFalse(BackgroundRuntimePolicy.alreadySupervising(BackgroundRuntimeSnapshot()))
        assertFalse(BackgroundRuntimePolicy.alreadySupervising(alive.copy(running=false)))
    }
    @Test fun optedInMonitoringKeepsCpuDuringRecoveryAndAllTradingGateStates() {
        // Paused, kill-latched and reconciliation-blocked trading are deliberately
        // not inputs: none of them is permission to abandon position monitoring.
        for(running in listOf(false,true)) for(alive in listOf(false,true)) {
            assertTrue(BackgroundRuntimePolicy.keepCpu(BackgroundRuntimeSnapshot(true,running,alive),false,0,1_000_000))
        }
        assertTrue(BackgroundRuntimePolicy.keepCpu(BackgroundRuntimeSnapshot(false,true,true),false,0,1_000_000))
        assertFalse(BackgroundRuntimePolicy.keepCpu(BackgroundRuntimeSnapshot(false,true,false),false,0,1_000_000))
    }
    @Test fun coldStartupLeaseIsBoundedAndCannotUseAFutureMonotonicClock() {
        val off=BackgroundRuntimeSnapshot()
        assertTrue(BackgroundRuntimePolicy.keepCpu(off,true,100,100))
        assertTrue(BackgroundRuntimePolicy.keepCpu(off,true,100,300_099))
        assertFalse(BackgroundRuntimePolicy.keepCpu(off,true,100,300_100))
        assertFalse(BackgroundRuntimePolicy.keepCpu(off,true,101,100))
        assertFalse(BackgroundRuntimePolicy.keepCpu(off,false,100,100))
        assertTrue(BackgroundRuntimePolicy.CHECK_MS<BackgroundRuntimePolicy.WAKE_TIMEOUT_MS)
    }
    @Test fun retriesRequireOptInAndCannotQueueASecondConcurrentStartup() {
        for(running in listOf(false,true)) for(alive in listOf(false,true)) {
            val s=BackgroundRuntimeSnapshot(true,running,alive)
            assertEquals(!running || !alive,BackgroundRuntimePolicy.shouldRetry(s,false,100,100))
            assertFalse(BackgroundRuntimePolicy.shouldRetry(s,true,100,100))
            assertFalse(BackgroundRuntimePolicy.shouldRetry(s,false,101,100))
            assertFalse(BackgroundRuntimePolicy.shouldRetry(s.copy(autoRunRequested=false),false,100,100))
        }
    }
    @Test fun repeatedStartupFailuresBackOffWithoutPermanentlyErasingMonitoring() {
        assertEquals(60_000L,BackgroundRuntimePolicy.retryDelay(1))
        assertEquals(120_000L,BackgroundRuntimePolicy.retryDelay(2))
        assertEquals(300_000L,BackgroundRuntimePolicy.retryDelay(3))
        assertEquals(900_000L,BackgroundRuntimePolicy.retryDelay(4))
        assertEquals(900_000L,BackgroundRuntimePolicy.retryDelay(1000))
        val stopped=BackgroundRuntimeSnapshot(autoRunRequested=true)
        assertTrue(BackgroundRuntimePolicy.shouldRetry(stopped,false,1_000,1_000))
    }
    @Test fun stalledMonitorIsVisibleButNeverStartsADuplicateWorker() {
        val current=BackgroundRuntimeSnapshot(autoRunRequested=true,running=true,
            workerAlive=true,lastWorkerLoopElapsedMs=1000L)
        assertFalse(BackgroundRuntimePolicy.monitorStale(current,180_999L))
        assertTrue(BackgroundRuntimePolicy.monitorStale(current,181_000L))
        assertFalse(BackgroundRuntimePolicy.monitorStale(current,999L))
        assertFalse(BackgroundRuntimePolicy.monitorStale(current.copy(workerAlive=false),300_000L))
        assertFalse(BackgroundRuntimePolicy.monitorStale(current.copy(running=false),300_000L))
        assertFalse(BackgroundRuntimePolicy.monitorStale(current.copy(lastWorkerLoopElapsedMs=0L),300_000L))
        assertFalse(BackgroundRuntimePolicy.shouldRetry(current,false,0L,300_000L))
        assertTrue(BackgroundRuntimePolicy.keepCpu(current.copy(autoRunRequested=false),false,0L,300_000L))
    }
    @Test fun stalledWorkerRecoveryNeverDuplicatesOrInterruptsFinancialWork() {
        val stalled=BackgroundRuntimeSnapshot(autoRunRequested=true,running=true,
            workerAlive=true,generation=42L,lastWorkerLoopElapsedMs=1_000L)
        fun eligible(s:BackgroundRuntimeSnapshot=stalled,at:Long=181_000L,
            mutation:Boolean=false,reconciliation:Boolean=false,
            management:Boolean=false,signalled:Boolean=false)=
            BackgroundRuntimePolicy.mayInterruptStaleWorker(
                s,at,mutation,reconciliation,management,signalled)
        assertTrue(eligible())
        assertFalse(eligible(at=180_999L))
        assertFalse(eligible(mutation=true))
        assertFalse(eligible(reconciliation=true))
        assertFalse(eligible(management=true))
        assertFalse(eligible(signalled=true))
        assertFalse(eligible(s=stalled.copy(autoRunRequested=false)))
        assertFalse(eligible(s=stalled.copy(workerAlive=false)))
        assertFalse(eligible(s=stalled.copy(running=false)))
        assertFalse(eligible(s=stalled.copy(lastWorkerLoopElapsedMs=0L)))
        assertFalse(BackgroundRuntimePolicy.shouldRetry(stalled,false,0L,181_000L))
    }

    @Test fun rebootAutostartMustConfirmARealWorkerAfterEncryptedOptInRead() {
        // The cold nonblocking snapshot is false because encrypted preferences
        // cannot be read on Android's service/main thread. The service must
        // BOOTSTRAP the secure runtime before consulting this guard.
        assertTrue(BackgroundRuntimePolicy.autostartComplete(BackgroundRuntimeSnapshot()))
        assertTrue(BackgroundRuntimePolicy.autostartComplete(
            BackgroundRuntimeSnapshot(autoRunRequested=false,running=false,workerAlive=false)))
        assertFalse(BackgroundRuntimePolicy.autostartComplete(
            BackgroundRuntimeSnapshot(autoRunRequested=true,running=false,workerAlive=false)))
        assertFalse(BackgroundRuntimePolicy.autostartComplete(
            BackgroundRuntimeSnapshot(autoRunRequested=true,running=true,workerAlive=false)))
        assertFalse(BackgroundRuntimePolicy.autostartComplete(
            BackgroundRuntimeSnapshot(autoRunRequested=true,running=false,workerAlive=true)))
        assertTrue(BackgroundRuntimePolicy.autostartComplete(
            BackgroundRuntimeSnapshot(autoRunRequested=true,running=true,workerAlive=true)))
    }
    @Test fun processExitCategoriesDistinguishCrashesAnrsMemoryAndUserStops() {
        assertEquals("CRASH",BackgroundRuntimePolicy.exitLabel(4))
        assertEquals("CRASH_NATIVE",BackgroundRuntimePolicy.exitLabel(5))
        assertEquals("ANR",BackgroundRuntimePolicy.exitLabel(6))
        assertEquals("LOW_MEMORY",BackgroundRuntimePolicy.exitLabel(3))
        assertEquals("USER_REQUESTED",BackgroundRuntimePolicy.exitLabel(10))
        assertEquals("USER_STOPPED",BackgroundRuntimePolicy.exitLabel(11))
        assertEquals("UNKNOWN",BackgroundRuntimePolicy.exitLabel(-1))
    }
}
