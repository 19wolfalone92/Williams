package com.williamsbot

import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong

/** Exact-request single flight. Results are not cached across requests.
 * The caller owns the I/O; no internal executor or unbounded waiting queue.
 * Only public kline GETs use this class. Signed order and STOP paths do not. */
internal class HistoryRequestCoordinator<K,V>(private val capacity:Int=32) {
    init { require(capacity>0) }
    private val lock=Any()
    private val active=HashMap<K,FutureTask<V>>()
    val started=AtomicLong()
    val joined=AtomicLong()
    val rejected=AtomicLong()

    fun read(key:K,fetch:()->V):V {
        val task=synchronized(lock) {
            val existing=active[key]
            if(existing!=null) {
                joined.incrementAndGet()
                existing
            } else {
                if(active.size>=capacity) {
                    rejected.incrementAndGet()
                    throw RejectedExecutionException("PUBLIC_HISTORY_INFLIGHT_LIMIT")
                }
                FutureTask(fetch).also {
                    active[key]=it
                    started.incrementAndGet()
                }
            }
        }
        try {
            task.run()
            return task.get()
        } catch(failure:ExecutionException) {
            throw failure.cause ?: failure
        } catch(failure:InterruptedException) {
            Thread.currentThread().interrupt()
            throw failure
        } finally {
            // Interrupted joiners must not erase an ongoing request.
            if(task.isDone) synchronized(lock) {
                if(active[key]===task) active.remove(key)
            }
        }
    }
    fun activeCount():Int=synchronized(lock){active.size}
}
