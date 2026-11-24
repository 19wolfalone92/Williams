package com.williamsbot

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class ExecutionDoorPriorityTest {
    private val key = FUTURES_EXECUTION_DOOR_KEY

    private fun awaitDepth(gate: ExecutionGate, depth: Int) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (gate.queueDepth() != depth && System.nanoTime() < end) Thread.sleep(1)
        assertEquals(depth, gate.queueDepth())
    }

    @Test fun protectionOvertakesQueuedEntriesAndReservedCapacityRemainsAvailable() {
        repeat(10) {
            val gate = ExecutionGate(3, 1)
            val order = LinkedBlockingQueue<String>()
            val errors = LinkedBlockingQueue<Throwable>()
            assertTrue(gate.tryReserve(key))
            fun submit(name: String, priority: ExecutionPriority) = thread {
                try {
                    assertEquals(ExecutionAdmission.ACQUIRED, gate.reserve(key, priority, 5000))
                    order.add(name)
                    gate.release(key)
                } catch (e: Throwable) { errors.add(e) }
            }
            val first = submit("entry1", ExecutionPriority.ENTRY)
            awaitDepth(gate, 1)
            val second = submit("entry2", ExecutionPriority.ENTRY)
            awaitDepth(gate, 2)
            val rejection = LinkedBlockingQueue<ExecutionAdmission>()
            val third = thread { rejection.add(gate.reserve(key, ExecutionPriority.ENTRY, 500)) }
            third.join(2000)
            assertEquals(ExecutionAdmission.QUEUE_FULL, rejection.poll())
            val protection = submit("stop", ExecutionPriority.PROTECTION)
            awaitDepth(gate, 3)
            gate.release(key)
            listOf(first, second, protection).forEach { it.join(6000); assertFalse(it.isAlive) }
            assertTrue(errors.toString(), errors.isEmpty())
            assertEquals(listOf("stop", "entry1", "entry2"), order.toList())
            assertEquals(0, gate.queueDepth())
            assertEquals(0, gate.reservedCount())
            assertEquals(3, gate.peakQueueDepth())
        }
    }

    @Test fun timeoutInterruptAndForeignReleaseCannotClearOwnedDoor() {
        val gate = ExecutionGate()
        assertTrue(gate.tryReserve(key))
        assertEquals(ExecutionAdmission.REENTRANT, gate.reserve(key, ExecutionPriority.PROTECTION, 5000))
        val outcomes = LinkedBlockingQueue<ExecutionAdmission>()
        val timeout = thread { outcomes.add(gate.reserve(key, ExecutionPriority.PROTECTION, 30)) }
        timeout.join(2000)
        assertEquals(ExecutionAdmission.TIMED_OUT, outcomes.poll())
        assertTrue(gate.isReserved(key))
        val interrupted = CountDownLatch(1)
        val waiter = thread {
            try { gate.reserve(key, ExecutionPriority.PROTECTION, 10000) }
            catch (_: InterruptedException) { interrupted.countDown() }
        }
        awaitDepth(gate, 1)
        waiter.interrupt()
        assertTrue(interrupted.await(2, TimeUnit.SECONDS))
        waiter.join(2000)
        val foreign = LinkedBlockingQueue<Boolean>()
        val nonOwner = thread { foreign.add(runCatching { gate.release(key) }.isFailure) }
        nonOwner.join(2000)
        assertEquals(true, foreign.poll())
        assertTrue(gate.isReserved(key))
        assertEquals(0, gate.queueDepth())
        gate.release(key)
        assertEquals(0, gate.reservedCount())
    }

    @Test fun criticalRuntimePurposesHaveHigherPriorityThanNewRisk() {
        assertEquals(ExecutionPriority.PROTECTION, executionPriorityForPurpose("POSITION_MANAGEMENT_RECONCILIATION"))
        assertEquals(ExecutionPriority.PROTECTION, executionPriorityForPurpose("OPERATOR_STOP_ACK_RECOVERY"))
        assertEquals(ExecutionPriority.FILL, executionPriorityForPurpose("USER_STREAM_RECONCILIATION"))
        assertEquals(ExecutionPriority.STOP_TRAIL, executionPriorityForPurpose("WILLIAMS_STRUCTURAL_STOP_TRAIL"))
        assertEquals(ExecutionPriority.ENTRY, executionPriorityForPurpose("WILLIAMS_PENDING_ENTRY"))
        assertEquals(ExecutionPriority.ADDITION, executionPriorityForPurpose("WILLIAMS_CAMPAIGN_ADDITION"))
    }
}
