package com.williamsbot

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

class ClosedStructureMemoTest {
    @Test fun sameKeyJoinsExistingInFlightWorkAtCapacity() {
        val memo=ClosedStructureMemo<String,String>(1)
        val entered=CountDownLatch(1)
        val release=CountDownLatch(1)
        val workers=Executors.newFixedThreadPool(2)
        try {
            val first=workers.submit<String> {
                memo.getOrCompute("BTC") {
                    entered.countDown()
                    check(release.await(5,TimeUnit.SECONDS))
                    "computed"
                }
            }
            assertTrue(entered.await(2,TimeUnit.SECONDS))
            val second=workers.submit<String> {
                memo.getOrCompute("BTC") { error("duplicate computation must not run") }
            }
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2)
            while(memo.joinedCount<1L && System.nanoTime()<deadline) Thread.sleep(5)
            assertEquals(1L,memo.startedCount)
            assertEquals(1L,memo.joinedCount)
            release.countDown()
            assertEquals("computed",first.get(2,TimeUnit.SECONDS))
            assertEquals("computed",second.get(2,TimeUnit.SECONDS))
            assertEquals(1,memo.size())
            assertEquals(0,memo.inFlightCount())
        } finally {
            release.countDown()
            workers.shutdownNow()
        }
    }

    @Test fun uniqueWorkIsRejectedWhenAllCapacitySlotsAreInFlight() {
        val memo=ClosedStructureMemo<String,String>(1)
        val entered=CountDownLatch(1)
        val release=CountDownLatch(1)
        val worker=Executors.newSingleThreadExecutor()
        try {
            val first=worker.submit<String> {
                memo.getOrCompute("BTC") {
                    entered.countDown()
                    check(release.await(5,TimeUnit.SECONDS))
                    "btc"
                }
            }
            assertTrue(entered.await(2,TimeUnit.SECONDS))
            val failure=runCatching { memo.getOrCompute("ETH") { "must-not-run" } }.exceptionOrNull()
            assertTrue(failure is RejectedExecutionException)
            assertEquals(1,memo.size())
            assertEquals(1L,memo.rejectedCount)
            release.countDown()
            assertEquals("btc",first.get(2,TimeUnit.SECONDS))
            assertEquals("eth",memo.getOrCompute("ETH") { "eth" })
            assertEquals(1,memo.size())
            assertEquals(0,memo.inFlightCount())
        } finally {
            release.countDown()
            worker.shutdownNow()
        }
    }

    @Test fun completedEntriesAreEvictedAndFailedCalculationsCanRetry() {
        val memo=ClosedStructureMemo<String,String>(1)
        assertEquals("btc",memo.getOrCompute("BTC") { "btc" })
        assertEquals("eth",memo.getOrCompute("ETH") { "eth" })
        assertEquals(1,memo.size())
        assertTrue(runCatching { memo.getOrCompute("SOL") { error("failed") } }.isFailure)
        assertEquals(0,memo.size())
        assertEquals("retry",memo.getOrCompute("SOL") { "retry" })
        assertEquals(1,memo.size())
        assertTrue(memo.failureCount>=1L)
    }
}
