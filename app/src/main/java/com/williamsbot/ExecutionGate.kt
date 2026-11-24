package com.williamsbot

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal const val FUTURES_EXECUTION_DOOR_KEY = "__FUTURES_ACCOUNT__"

/** Pure policy contract used by the runtime gate and deterministic unit tests. */
internal fun executionDoorBlocker(
    running: Boolean,
    paused: Boolean,
    reconcileRequired: Boolean,
    killLatched: Boolean,
    allowPaused: Boolean = false,
    allowStopped: Boolean = false,
    allowKillLatched: Boolean = false,
    allowReconcileRequired: Boolean = false
): String? = when {
    !running && !allowStopped -> "Runtime is not running"
    paused && !allowPaused -> "Runtime is paused"
    reconcileRequired && !allowReconcileRequired -> "RECONCILE_REQUIRED"
    killLatched && !allowKillLatched -> "KILL_SWITCH_LATCHED"
    else -> null
}

internal enum class ExecutionPriority {
    PROTECTION, FILL, EMERGENCY_REDUCTION, STOP_TRAIL, RECONCILIATION,
    CANCEL_ENTRY, ENTRY, ADDITION, RESEARCH
}

internal enum class ExecutionAdmission { ACQUIRED, BUSY, QUEUE_FULL, TIMED_OUT, REENTRANT }

/** Admission only: never preempts an in-flight mutation or transfers its durable lease. */
class ExecutionGate(private val maxWaiters: Int = 64, private val safetyReserve: Int = 16) {
    init { require(maxWaiters > 0 && safetyReserve in 0..maxWaiters) }
    private class Waiter(val key: String, val priority: ExecutionPriority, val since: Long)
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val owners = mutableMapOf<String, Thread>()
    private val waiters = mutableListOf<Waiter>()
    private var peak = 0

    fun tryReserve(symbol: String): Boolean =
        reserve(symbol, ExecutionPriority.ENTRY, 0) == ExecutionAdmission.ACQUIRED

    internal fun reserve(symbol: String, priority: ExecutionPriority, waitMs: Long): ExecutionAdmission {
        require(waitMs >= 0)
        val key = symbol.uppercase(java.util.Locale.ROOT)
        if (key.isBlank()) return ExecutionAdmission.BUSY
        val owner = Thread.currentThread()
        lock.lockInterruptibly()
        try {
            if (owners[key] === owner) return ExecutionAdmission.REENTRANT
            if (!owners.containsKey(key) && waiters.none { it.key == key && it.priority <= priority }) {
                owners[key] = owner
                return ExecutionAdmission.ACQUIRED
            }
            if (waitMs == 0L) return ExecutionAdmission.BUSY
            val limit = if (priority <= ExecutionPriority.EMERGENCY_REDUCTION) maxWaiters else maxWaiters - safetyReserve
            if (waiters.size >= limit) return ExecutionAdmission.QUEUE_FULL
            val ticket = Waiter(key, priority, System.nanoTime())
            waiters.add(ticket)
            peak = maxOf(peak, waiters.size)
            val budget = TimeUnit.MILLISECONDS.toNanos(waitMs)
            try {
                while (true) {
                    val remaining = budget - (System.nanoTime() - ticket.since)
                    if (remaining <= 0L) return ExecutionAdmission.TIMED_OUT
                    // minByOrNull preserves insertion order among equal priorities.
                    val first = waiters.asSequence().filter { it.key == key }.minByOrNull { it.priority.ordinal }
                    if (!owners.containsKey(key) && first === ticket) {
                        owners[key] = owner
                        return ExecutionAdmission.ACQUIRED
                    }
                    changed.awaitNanos(remaining)
                }
            } finally {
                waiters.remove(ticket)
                changed.signalAll()
            }
        } finally { lock.unlock() }
    }

    fun release(symbol: String) = lock.withLock {
        val key = symbol.uppercase(java.util.Locale.ROOT)
        // The runtime account lease may only be released by its acquiring thread.
        // Generic symbol reservations retain the legacy cross-thread release contract.
        if (key == FUTURES_EXECUTION_DOOR_KEY && owners.containsKey(key)) {
            check(owners[key] === Thread.currentThread()) { "Execution Door release by non-owner" }
        }
        owners.remove(key)
        changed.signalAll()
    }

    fun isReserved(symbol: String): Boolean = lock.withLock { owners.containsKey(symbol.uppercase(java.util.Locale.ROOT)) }
    fun reservedCount(): Int = lock.withLock { owners.size }
    internal fun queueDepth(): Int = lock.withLock { waiters.size }
    internal fun peakQueueDepth(): Int = lock.withLock { peak }
    internal fun oldestWaitMs(): Long = lock.withLock {
        waiters.firstOrNull()?.let { TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - it.since) } ?: 0L
    }
}

/** Only changes scheduling, never trade eligibility or permission to mutate. */
internal fun executionPriorityForPurpose(purpose: String): ExecutionPriority = when (purpose) {
    "OPERATOR_STOP_ACK_RECOVERY", "SIGNED_MANUAL_PRICE_PROTECTED_STOP_HANDOFF",
    "OPERATOR_APPROVE_EXISTING_STOP_ONLY", "STARTUP_RECONCILIATION", "RECOVERY_RECONCILIATION",
    "POSITION_MANAGEMENT_RECONCILIATION", "SELF_HEAL_RECONCILIATION" -> ExecutionPriority.PROTECTION
    "USER_STREAM_RECONCILIATION" -> ExecutionPriority.FILL
    "KILL_SWITCH_CLOSE", "KILL_SWITCH_CANCEL_PENDING", "KILL_SWITCH_PRE_CLOSE_RECONCILIATION",
    "KILL_SWITCH_FINAL_RECONCILIATION", "UNPROTECTED_POSITION_GUARD_EXIT", "LIQUIDATION_GUARD_EXIT" -> ExecutionPriority.EMERGENCY_REDUCTION
    "WILLIAMS_STRUCTURAL_STOP_TRAIL", "OPERATOR_EXISTING_STOP_ONLY_TRAIL" -> ExecutionPriority.STOP_TRAIL
    "MANUAL_FUTURES_EXIT", "TRIGGERED_WILLIAMS_EXIT", "COURSE_STAGE_REDUCTION" -> ExecutionPriority.EMERGENCY_REDUCTION
    "WILLIAMS_PRE_ENTRY_SIGNED_REFRESH", "MANUAL_EXIT_POSITION_NOT_FOUND_RECONCILIATION" -> ExecutionPriority.RECONCILIATION
    "WILLIAMS_PENDING_ENTRY", "TC2_CONFIRMED_FLAT_REVERSE_ENTRY" -> ExecutionPriority.ENTRY
    "WILLIAMS_CAMPAIGN_ADDITION" -> ExecutionPriority.ADDITION
    else -> if (purpose.contains("CANCEL" ) || purpose == "OPERATOR_SUSPEND_ENTRIES") ExecutionPriority.CANCEL_ENTRY
        else ExecutionPriority.RECONCILIATION
}
