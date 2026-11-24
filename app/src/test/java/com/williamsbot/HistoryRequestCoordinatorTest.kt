package com.williamsbot

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HistoryRequestCoordinatorTest {
    @Test fun identicalOverlappingRequestsShareOneFetchButLaterReadIsFresh() {
        val coordinator=HistoryRequestCoordinator<String,String>(2)
        val entered=CountDownLatch(1)
        val release=CountDownLatch(1)
        val workers=Executors.newFixedThreadPool(6)
        try {
            val futures=(1..6).map {
                workers.submit<String> {
                    coordinator.read("BTC:1h:range") {
                        entered.countDown()
                        check(release.await(5,TimeUnit.SECONDS))
                        "closed"
                    }
                }
            }
            assertTrue(entered.await(2,TimeUnit.SECONDS))
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2)
            while(coordinator.joined.get()<5L && System.nanoTime()<deadline) Thread.sleep(5)
            assertEquals(5L,coordinator.joined.get())
            release.countDown()
            futures.forEach { assertEquals("closed",it.get(2,TimeUnit.SECONDS)) }
            assertEquals(1L,coordinator.started.get())
            assertEquals(0,coordinator.activeCount())
            assertEquals("corrected",coordinator.read("BTC:1h:range") { "corrected" })
            assertEquals(2L,coordinator.started.get())
        } finally {
            release.countDown()
            workers.shutdownNow()
        }
    }

    @Test fun differentRangeRespectsCapacityAndFailedFetchCanRetry() {
        val coordinator=HistoryRequestCoordinator<String,String>(1)
        val entered=CountDownLatch(1)
        val release=CountDownLatch(1)
        val executor=Executors.newSingleThreadExecutor()
        try {
            val first=executor.submit<String> {
                coordinator.read("BTC") {
                    entered.countDown()
                    check(release.await(5,TimeUnit.SECONDS))
                    error("network")
                }
            }
            assertTrue(entered.await(2,TimeUnit.SECONDS))
            assertTrue(runCatching { coordinator.read("ETH") { "must not run" } }
                .exceptionOrNull() is java.util.concurrent.RejectedExecutionException)
            assertEquals(1L,coordinator.rejected.get())
            release.countDown()
            assertTrue(runCatching { first.get(2,TimeUnit.SECONDS) }.isFailure)
            assertEquals(0,coordinator.activeCount())
            assertEquals("retry",coordinator.read("BTC") { "retry" })
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }
}
