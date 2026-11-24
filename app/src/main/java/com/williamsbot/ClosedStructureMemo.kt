package com.williamsbot

import java.util.LinkedHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

/** Bounded read-only mathematical cache. Callers must supply the actual input
 * content and every parameter affecting the result. Never use this for quotes,
 * balances, order state, permission checks or network operations. */
internal class ClosedStructureMemo<K,V>(private val capacity:Int) {
    init { require(capacity>0) }
    private val entries=LinkedHashMap<K,FutureTask<V>>(16,0.75f,true)
    private val lock=Any()

    fun getOrCompute(key:K,compute:()->V):V {
        val task=synchronized(lock) {
            entries[key] ?: run {
                // Never evict an in-flight calculation and start duplicate work.
                while(entries.size>=capacity) {
                    val oldest=entries.entries.firstOrNull { it.value.isDone } ?: break
                    entries.remove(oldest.key)
                }
                FutureTask(compute).also { if(entries.size<capacity) entries[key]=it }
            }
        }
        try {
            // FutureTask.run() is idempotent even when two consumers race.
            task.run()
            return task.get()
        } catch(failure:ExecutionException) {
            synchronized(lock) { if(entries[key]===task) entries.remove(key) }
            throw failure.cause ?: failure
        } catch(failure:InterruptedException) {
            Thread.currentThread().interrupt()
            throw failure
        } catch(failure:java.util.concurrent.CancellationException) {
            synchronized(lock) { if(entries[key]===task) entries.remove(key) }
            throw failure
        }
    }

    fun size():Int=synchronized(lock) { entries.size }
}
