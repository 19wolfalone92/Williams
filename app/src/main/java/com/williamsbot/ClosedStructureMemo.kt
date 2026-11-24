package com.williamsbot

import java.util.LinkedHashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong

/** Bounded read-only mathematical cache. Callers must supply the actual input
 * content and every parameter affecting the result. Never use this for quotes,
 * balances, order state, permission checks or network operations. */
internal class ClosedStructureMemo<K,V>(val capacity:Int) {
    init { require(capacity>0) }
    private val entries=LinkedHashMap<K,FutureTask<V>>(16,0.75f,true)
    private val lock=Any()
    private val started=AtomicLong()
    private val joined=AtomicLong()
    private val cacheHits=AtomicLong()
    private val rejected=AtomicLong()
    private val failures=AtomicLong()

    val startedCount:Long get()=started.get()
    val joinedCount:Long get()=joined.get()
    val cacheHitsCount:Long get()=cacheHits.get()
    val rejectedCount:Long get()=rejected.get()
    val failureCount:Long get()=failures.get()

    fun getOrCompute(key:K,compute:()->V):V {
        val task=synchronized(lock) {
            val existing=entries[key]
            if(existing!=null) {
                if(existing.isDone) cacheHits.incrementAndGet()
                else joined.incrementAndGet()
                existing
            } else {
                // Completed LRU entries may be evicted. If every slot is
                // occupied by an in-flight computation, reject new unique
                // work instead of running an untracked FutureTask and allowing
                // concurrent callers to create an unbounded duplicate queue.
                while(entries.size>=capacity) {
                    val oldest=entries.entries.firstOrNull { it.value.isDone }
                    if(oldest==null) {
                        rejected.incrementAndGet()
                        throw RejectedExecutionException("CLOSED_STRUCTURE_MEMO_INFLIGHT_LIMIT")
                    }
                    entries.remove(oldest.key)
                }
                FutureTask(compute).also {
                    entries[key]=it
                    started.incrementAndGet()
                }
            }
        }
        try {
            // FutureTask.run() is idempotent even when two consumers race.
            task.run()
            return task.get()
        } catch(failure:ExecutionException) {
            failures.incrementAndGet()
            synchronized(lock) { if(entries[key]===task) entries.remove(key) }
            throw failure.cause ?: failure
        } catch(failure:InterruptedException) {
            Thread.currentThread().interrupt()
            throw failure
        } catch(failure:CancellationException) {
            failures.incrementAndGet()
            synchronized(lock) { if(entries[key]===task) entries.remove(key) }
            throw failure
        }
    }

    fun size():Int=synchronized(lock) { entries.size }
    fun inFlightCount():Int=synchronized(lock) { entries.values.count { !it.isDone } }
}
