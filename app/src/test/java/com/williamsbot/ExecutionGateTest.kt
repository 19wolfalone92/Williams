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
