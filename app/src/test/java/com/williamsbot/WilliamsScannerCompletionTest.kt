package com.williamsbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WilliamsScannerCompletionTest {
    @Test fun fastNineSymbolsAreKeptEvenWhenFirstFutureNeverFinishes() {
        val executor=Executors.newFixedThreadPool(3)
        val blocker=CountDownLatch(1)
        try {
            val service=ExecutorCompletionService<String?>(executor)
            val first=service.submit(Callable<String?> {
                blocker.await(10,TimeUnit.SECONDS)
                "SLOW"
            })
            val remaining=(1..9).map { index ->
                service.submit(Callable<String?> { "FAST_${index}" })
            }
            val batch=collectReadOnlyScannerCompletions(
                service,listOf(first)+remaining,
                System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(300)
            )
            assertEquals(9,batch.values.size)
            assertTrue(batch.values.containsAll((1..9).map { "FAST_${it}" }))
            assertEquals(9,batch.completedCount)
            assertTrue(batch.timedOut)
            assertTrue(first.isCancelled)
        } finally {
            blocker.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun allCompletedSignalsArePreservedAndNoTimeoutIsInvented() {
        val executor=Executors.newFixedThreadPool(3)
        try {
            val service=ExecutorCompletionService<Int?>(executor)
            val futures=(1..10).map { i -> service.submit(Callable<Int?> { i }) }
            val batch=collectReadOnlyScannerCompletions(
                service,futures,System.nanoTime()+TimeUnit.SECONDS.toNanos(4L)
            )
            assertEquals((1..10).toList(),batch.values.sorted())
            assertEquals(10,batch.completedCount)
            assertEquals(0,batch.failedCount)
            assertFalse(batch.timedOut)
        } finally { executor.shutdownNow() }
    }

    @Test fun failedOrAbsentAnalysisIsNeverUpgradedToCandidate() {
        val executor=Executors.newFixedThreadPool(2)
        try {
            val service=ExecutorCompletionService<String?>(executor)
            val failing=service.submit(Callable<String?> { error("bad closed candles") })
            val missing=service.submit(Callable<String?> { null })
            val good=service.submit(Callable<String?> { "GOOD" })
            val batch=collectReadOnlyScannerCompletions(
                service,listOf(failing,missing,good),
                System.nanoTime()+TimeUnit.SECONDS.toNanos(3L)
            )
            assertEquals(listOf("GOOD"),batch.values)
            assertEquals(3,batch.completedCount)
            assertEquals(1,batch.failedCount)
            assertFalse(batch.timedOut)
        } finally { executor.shutdownNow() }
    }
}
