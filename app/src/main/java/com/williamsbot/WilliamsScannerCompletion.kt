package com.williamsbot

import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Restrict expensive per-symbol wave-analysis concurrency to actual Java heap
 * (not largeMemoryClass). The 256 MiB device logged a scanner OOM.
 * This alters analysis scheduling, never the book signals.
 */
internal fun scannerAnalysisParallelism(maxJavaHeapBytes: Long, symbolCount: Int): Int {
    val budget=when {
        maxJavaHeapBytes<=0L -> 2
        maxJavaHeapBytes<=256L*1024L*1024L -> 3
        maxJavaHeapBytes<=384L*1024L*1024L -> 4
        else -> 6
    }
    return budget.coerceAtMost(symbolCount.coerceAtLeast(1))
}

/** Do not publish READY while a previous scan is still finalizing. */
internal fun scannerObservedState(scanning: Boolean, publishedState: String): String =
    if (scanning && publishedState in setOf("READY","READY_BLOCKED","PARTIAL","PARTIAL_BLOCKED"))
        "RUNNING" else publishedState

/** Read-only scanner fan-out: a stalled first symbol must never hide fast later results.
 * No signal is manufactured; only genuinely completed successful analyses are returned.
 * All tasks share one deadline, and unresolved tasks are interrupted at that deadline.
 */
internal data class WilliamsScannerBatch<T>(
    val values: List<T>,
    val completedCount: Int,
    val failedCount: Int,
    val timedOut: Boolean
)

internal fun <T : Any> collectReadOnlyScannerCompletions(
    completions: ExecutorCompletionService<T?>,
    submitted: List<Future<T?>>,
    deadlineNanos: Long,
    monotonicNanos: () -> Long = System::nanoTime
): WilliamsScannerBatch<T> {
    val values = ArrayList<T>(submitted.size)
    var received = 0
    var failures = 0
    var interrupted = false
    try {
        while (received < submitted.size) {
            val remainingNs = deadlineNanos - monotonicNanos()
            if (remainingNs <= 0L) break
            val future = completions.poll(remainingNs, TimeUnit.NANOSECONDS) ?: break
            received++
            try {
                future.get()?.let { values.add(it) }
            } catch (_: java.util.concurrent.ExecutionException) {
                failures++
            } catch (_: java.util.concurrent.CancellationException) {
                failures++
            }
        }
    } catch (_: InterruptedException) {
        interrupted = true
    } finally {
        if (received < submitted.size) {
            for (future in submitted) if (!future.isDone) future.cancel(true)
        }
        if (interrupted) Thread.currentThread().interrupt()
    }
    return WilliamsScannerBatch(values, received, failures, received < submitted.size)
}
