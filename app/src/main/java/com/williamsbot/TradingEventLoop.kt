package com.williamsbot

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * One serialized actor for execution-critical events.
 *
 * Keep its event queue lossless until a durable private-stream spool and signed
 * replay protocol are implemented. Silently bounding/dropping events or using
 * CallerRunsPolicy would violate order and allow concurrent financial state
 * mutations. Expose queue pressure instead, so watchdog/diagnostics can detect
 * backlog and revoke entry authority (the WS receipt latch already does this).
 */
class TradingEventLoop(name: String = "williams-trading-actor") : AutoCloseable {
    private val executor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        LinkedBlockingQueue<Runnable>(),
        { runnable -> Thread(runnable, name).apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()
    )
    private val peakQueued = AtomicInteger(0)

    fun queueDepth(): Int = executor.queue.size
    fun peakQueueDepth(): Int = peakQueued.get()
    fun actorActive(): Boolean = executor.activeCount > 0

    fun post(task: () -> Unit) {
        if (!executor.isShutdown) {
            executor.execute(task)
            val queued = executor.queue.size
            peakQueued.accumulateAndGet(queued) { left, right -> maxOf(left, right) }
        }
    }

    fun <T> call(task: () -> T): T =
        executor.submit<T> { task() }.get()

    override fun close() {
        executor.shutdownNow()
        executor.awaitTermination(2, TimeUnit.SECONDS)
    }
}
