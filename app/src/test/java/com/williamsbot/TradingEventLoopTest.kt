package com.williamsbot

import java.util.Collections
import org.junit.Assert.assertEquals
import org.junit.Test

class TradingEventLoopTest {
    @Test fun queuePressureIsVisibleAndNeverDropsOrReordersTradeEvents() {
        val loop = TradingEventLoop()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val values = Collections.synchronizedList(mutableListOf<Int>())
        loop.post {
            entered.countDown()
            release.await()
            values += -1
        }
        org.junit.Assert.assertTrue(entered.await(2L, java.util.concurrent.TimeUnit.SECONDS))
        repeat(90) { i -> loop.post { values += i } }
        try {
            org.junit.Assert.assertEquals(90, loop.queueDepth())
            org.junit.Assert.assertTrue(loop.peakQueueDepth() >= 90)
            org.junit.Assert.assertTrue(loop.actorActive())
        } finally {
            release.countDown()
        }
        loop.call { Unit }
        assertEquals(listOf(-1) + (0 until 90).toList(), values)
        assertEquals(0, loop.queueDepth())
        loop.close()
    }

    @Test fun fullActorQueueRejectsWithoutDroppingOrExecutingTasksOnWebSocketThread() {
        val loop = TradingEventLoop("bounded-private-inbox-test", maxQueuedTasks = 2)
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val performed = Collections.synchronizedList(mutableListOf<Int>())
        val finished = java.util.concurrent.CountDownLatch(3)
        loop.post { started.countDown(); release.await(); performed += 0; finished.countDown() }
        org.junit.Assert.assertTrue(started.await(2L, java.util.concurrent.TimeUnit.SECONDS))
        try {
            loop.post { performed += 1; finished.countDown() }
            loop.post { performed += 2; finished.countDown() }
            assertEquals(2, loop.queueDepth())
            try {
                loop.post { performed += 3 }
                throw AssertionError("Overflow must be explicit so durable inbox retry stays armed")
            } catch (_: java.util.concurrent.RejectedExecutionException) { }
            assertEquals(2, loop.queueDepth())
        } finally { release.countDown() }
        org.junit.Assert.assertTrue(finished.await(5L, java.util.concurrent.TimeUnit.SECONDS))
        loop.call { Unit }
        assertEquals(listOf(0, 1, 2), performed)
        loop.close()
    }

    @Test fun closedActorRejectsWakeupInsteadOfSilentlyLeavingScheduledLatchSet() {
        val loop = TradingEventLoop()
        loop.close()
        try {
            loop.post { throw AssertionError("Must not run") }
            throw AssertionError("A shutdown private actor must reject its wake-up explicitly")
        } catch (_: java.util.concurrent.RejectedExecutionException) { }
    }

    @Test
    fun serializes_events_in_submission_order() {
        val loop = TradingEventLoop()
        val values = Collections.synchronizedList(mutableListOf<Int>())
        repeat(100) { i -> loop.post { values += i } }
        loop.call { Unit }
        assertEquals((0 until 100).toList(), values)
        loop.close()
    }
}
