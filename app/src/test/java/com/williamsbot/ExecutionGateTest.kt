package com.williamsbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ExecutionGateTest {
    @Test
    fun differentSymbols_canReserveConcurrently() {
        val gate = ExecutionGate()
        assertTrue(gate.tryAdmit("BTCUSDT"))
        assertTrue(gate.tryAdmit("ETHUSDT"))
        assertEquals(2, gate.reservedCount())
        gate.release("BTCUSDT")
        gate.release("ETHUSDT")
        assertEquals(0, gate.reservedCount())
    }

    @Test
    fun concurrentDuplicateIntents_onlyOneReserves() {
        val gate = ExecutionGate()
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val accepted = AtomicInteger(0)

        repeat(2) {
            pool.execute {
                start.await()
                if (gate.tryAdmit("BTCUSDT")) {
                    accepted.incrementAndGet()
                }
                done.countDown()
            }
        }

        start.countDown()
        assertTrue(done.await(2, TimeUnit.SECONDS))
        pool.shutdownNow()

        assertEquals(1, accepted.get())
        assertTrue(gate.isReserved("BTCUSDT"))

        gate.release("BTCUSDT")
        assertEquals(0, gate.reservedCount())
    }


    @Test
    fun closedGate_rejectsAdmission_andMakesZeroReservations() {
        val gate = ExecutionGate()
        gate.close()

        assertTrue(!gate.tryAdmit("BTCUSDT"))
        assertEquals(0, gate.reservedCount())
    }

    @Test
    fun concurrentCloseAndAdmission_hasSingleLinearizationOutcome() {
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
                gate.close()
                done.countDown()
            }

            start.countDown()
            assertTrue(done.await(2, TimeUnit.SECONDS))
            pool.shutdownNow()

            assertTrue(admitted.get() == 0 || admitted.get() == 1)
            if (admitted.get() == 1) {
                assertTrue(gate.isReserved("BTCUSDT"))
            } else {
                assertEquals(0, gate.reservedCount())
            }
            assertTrue(!gate.isOpen())
        }
    }

    @Test
    fun admissionBeforeClose_remainsAnInFlightAdmission() {
        val gate = ExecutionGate()

        assertTrue(gate.tryAdmit("BTCUSDT"))
        gate.close()

        assertTrue(!gate.tryAdmit("ETHUSDT"))
        assertTrue(gate.isReserved("BTCUSDT"))
        gate.release("BTCUSDT")
        assertEquals(0, gate.reservedCount())
    }
}