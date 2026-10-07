package com.williamsbot

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionGateTest {
    @Test
    fun recoveryAdmission_cannotEscalateToNormalMutation() {
        val gate = ExecutionGate()

        val result = gate.executeWithAdmission(
            symbol = "BTCUSDT",
            scope = AdmissionScope.PROTECTIVE_RECOVERY
        ) {
            gate.executeWithAdmission(
                symbol = "BTCUSDT",
                scope = AdmissionScope.NORMAL_EXECUTION
            ) {
                true
            }
        }

        assertTrue(result is AdmissionResult.Admitted)
        val nested = (result as AdmissionResult.Admitted).value
        assertTrue(nested is AdmissionResult.Rejected)
    }

    @Test
    fun differentSymbols_canAdmitConcurrently() {
        val gate = ExecutionGate()
        assertTrue(gate.tryAdmit("BTCUSDT"))
        assertTrue(gate.tryAdmit("ETHUSDT"))
        assertEquals(2, gate.reservedCount())
        gate.release("BTCUSDT")
        gate.release("ETHUSDT")
        assertEquals(0, gate.reservedCount())
    }

    @Test
    fun sameSymbol_onlyOneConcurrentAdmission() {
        val gate = ExecutionGate()
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val accepted = AtomicInteger(0)

        repeat(2) {
            pool.execute {
                start.await()
                if (gate.tryAdmit("BTCUSDT")) accepted.incrementAndGet()
                done.countDown()
            }
        }

        start.countDown()
        assertTrue(done.await(2, TimeUnit.SECONDS))
        pool.shutdownNow()
        assertEquals(1, accepted.get())
        assertEquals(1, gate.reservedCount())
    }

    @Test
    fun closeNormal_blocksNewNormalAdmission_butKeepsRecoveryAvailable() {
        val gate = ExecutionGate()
        gate.closeNormalExecution(GateCloseReason.RECONCILE_REQUIRED)

        assertFalse(gate.tryAdmit("BTCUSDT", AdmissionScope.NORMAL_EXECUTION))
        assertTrue(gate.tryAdmit("BTCUSDT", AdmissionScope.PROTECTIVE_RECOVERY))
        assertFalse(gate.isNormalOpen())
        assertTrue(gate.isRecoveryOpen())

        gate.release("BTCUSDT")
    }

    @Test
    fun closeAll_blocksBothScopes() {
        val gate = ExecutionGate()
        gate.closeAll(GateCloseReason.KILL_SWITCH)

        assertFalse(gate.tryAdmit("BTCUSDT", AdmissionScope.NORMAL_EXECUTION))
        assertFalse(gate.tryAdmit("BTCUSDT", AdmissionScope.PROTECTIVE_RECOVERY))
    }

    @Test
    fun admissionHoldsReservation_forEntireMutation() {
        val gate = ExecutionGate()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val second = AtomicInteger(0)

        val first = Thread {
            gate.executeWithAdmission("BTCUSDT") {
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
            }
        }
        first.start()

        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val other = Thread {
            if (gate.tryAdmit("BTCUSDT")) second.incrementAndGet()
        }
        other.start()
        other.join(1000)

        assertEquals(0, second.get())
        release.countDown()
        first.join(2000)
        assertEquals(0, gate.reservedCount())
    }

    @Test
    fun closeVsAdmission_hasSingleLinearizationOutcome() {
        repeat(100) {
            val gate = ExecutionGate()
            val pool = Executors.newFixedThreadPool(2)
            val start = CountDownLatch(1)
            val done = CountDownLatch(2)
            val admitted = AtomicInteger(0)

            pool.execute {
                start.await()
                if (gate.tryAdmit("BTCUSDT")) admitted.incrementAndGet()
                done.countDown()
            }
            pool.execute {
                start.await()
                gate.closeNormalExecution(GateCloseReason.RECONCILE_REQUIRED)
                done.countDown()
            }

            start.countDown()
            assertTrue(done.await(2, TimeUnit.SECONDS))
            pool.shutdownNow()

            assertTrue(admitted.get() == 0 || admitted.get() == 1)
            assertFalse(gate.isNormalOpen())
            if (admitted.get() == 1) {
                assertTrue(gate.isReserved("BTCUSDT"))
                gate.release("BTCUSDT")
            } else {
                assertEquals(0, gate.reservedCount())
            }
        }
    }
}
