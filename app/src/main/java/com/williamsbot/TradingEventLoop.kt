package com.williamsbot

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * One serialized actor for execution-critical events.
 *
 * Every Binance private event is FIRST persisted into SQLite's durable inbox,
 * then one actor drain task is scheduled. The actor must NOT accept an
 * unbounded number of in-memory tasks after a callback storm; if scheduling
 * fails, the caller catches the explicit rejection and keeps the durable
 * inbox/reconcile gate closed. Never silently drop or CallerRuns a task:
 * both would break ordering of financial state mutations.
 */
class TradingEventLoop(
    name: String = "williams-trading-actor",
    maxQueuedTasks: Int = 256
) : AutoCloseable {
    init { require(maxQueuedTasks > 0) }
    private val executor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(maxQueuedTasks),
        { runnable -> Thread(runnable, name).apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()
    )
    private val peakQueued = AtomicInteger(0)

    fun queueDepth(): Int = executor.queue.size
    fun peakQueueDepth(): Int = peakQueued.get()
    fun actorActive(): Boolean = executor.activeCount > 0

    fun post(task: () -> Unit) {
        // execute() throws RejectedExecutionException when full/shutdown.
        // Returning normally without queuing previously left the runtime's
        // privateEventDrainScheduled=true forever even though nothing ran.
        executor.execute(task)
        val queued = executor.queue.size
        peakQueued.accumulateAndGet(queued) { left, right -> maxOf(left, right) }
    }

    fun <T> call(task: () -> T): T =
        executor.submit<T> { task() }.get()

    override fun close() {
        executor.shutdownNow()
        executor.awaitTermination(2, TimeUnit.SECONDS)
    }
}
