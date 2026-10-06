package com.williamsbot

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Single serialized actor for execution-critical trading events. */
class TradingEventLoop(name: String = "williams-trading-actor") : AutoCloseable {
    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, name).apply { isDaemon = true }
        }

    fun post(task: () -> Unit) {
        if (!executor.isShutdown) executor.execute(task)
    }

    fun <T> call(task: () -> T): T =
        executor.submit<T> { task() }.get()

    override fun close() {
        executor.shutdownNow()
        executor.awaitTermination(2, TimeUnit.SECONDS)
    }
}
