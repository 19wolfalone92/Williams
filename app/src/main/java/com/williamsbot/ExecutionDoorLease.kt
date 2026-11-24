package com.williamsbot

internal enum class ExecutionLeaseFinalization { RELEASE_ALLOWED, RECONCILE_FENCE_FAILED, LEASE_CLEAR_FAILED }

/** Only a durable recovery fence may replace an unresolved execution lease.
 * A false/throwing write never authorizes a release. The runtime retains
 * the account reservation until restart/reconciliation if persistence fails.
 */
internal fun finalizeExecutionDoorLease(
    requiresReconciliation: Boolean,
    persistReconciliation: () -> Boolean,
    clearSqliteLease: () -> Boolean,
    clearPreferenceLease: () -> Boolean
): ExecutionLeaseFinalization {
    if (requiresReconciliation && !runCatching(persistReconciliation).getOrDefault(false))
        return ExecutionLeaseFinalization.RECONCILE_FENCE_FAILED
    val sqliteCleared = runCatching(clearSqliteLease).getOrDefault(false)
    val mirrorCleared = runCatching(clearPreferenceLease).getOrDefault(false)
    return if (sqliteCleared && mirrorCleared) ExecutionLeaseFinalization.RELEASE_ALLOWED
        else ExecutionLeaseFinalization.LEASE_CLEAR_FAILED
}
