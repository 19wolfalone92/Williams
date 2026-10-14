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
    fun accountWideExecutionDoor_serializesMutationsAcrossSymbols() {
        val gate = ExecutionGate()
        assertTrue(gate.tryReserve(FUTURES_EXECUTION_DOOR_KEY))
        assertEquals(false, gate.tryReserve(FUTURES_EXECUTION_DOOR_KEY))
        assertEquals(1, gate.reservedCount())
        gate.release(FUTURES_EXECUTION_DOOR_KEY)
        assertEquals(true, gate.tryReserve(FUTURES_EXECUTION_DOOR_KEY))
        gate.release(FUTURES_EXECUTION_DOOR_KEY)
        assertEquals(0, gate.reservedCount())
    }

    @Test
    fun executionDoorPolicy_blocksUnsafeStatesButAllowsExplicitSafetyOverrides() {
        assertEquals(
            "Runtime is not running",
            executionDoorBlocker(false, false, false, false)
        )
        assertEquals(
            "Runtime is paused",
            executionDoorBlocker(true, true, false, false)
        )
        assertEquals(
            "RECONCILE_REQUIRED",
            executionDoorBlocker(true, false, true, false)
        )
        assertEquals(
            "KILL_SWITCH_LATCHED",
            executionDoorBlocker(true, false, false, true)
        )
        assertEquals(
            null,
            executionDoorBlocker(
                running = false,
                paused = true,
                reconcileRequired = true,
                killLatched = true,
                allowPaused = true,
                allowStopped = true,
                allowKillLatched = true,
                allowReconcileRequired = true
            )
        )
    }

    @Test
    fun differentSymbols_canReserveConcurrently() {
        val gate = ExecutionGate()
        assertTrue(gate.tryReserve("BTCUSDT"))
        assertTrue(gate.tryReserve("ETHUSDT"))
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
                if (gate.tryReserve("BTCUSDT")) {
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
}
