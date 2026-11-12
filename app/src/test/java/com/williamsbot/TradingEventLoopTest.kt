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
