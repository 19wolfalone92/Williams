package com.williamsbot

/**
 * Runtime admission barrier for Binance mutations.
 *
 * NORMAL_EXECUTION is the only scope that can admit new-entry/regular
 * mutations. PROTECTIVE_RECOVERY is deliberately independent so risk-reducing
 * actions remain available while normal trading is halted.
 *
 * Admission and per-symbol reservation have one linearization point. The
 * network operation itself runs after admission while the reservation remains
 * owned by that caller; close() therefore blocks future admissions without
 * creating a close-vs-dispatch race.
 */
enum class AdmissionScope {
    NORMAL_EXECUTION,
    PROTECTIVE_RECOVERY
}

sealed class AdmissionResult<out T> {
    data class Admitted<T>(val value: T) : AdmissionResult<T>()
    data class Rejected(val reason: GateCloseReason) : AdmissionResult<Nothing>()
}

class UnknownNetworkOutcomeException(
    val method: String,
    val path: String,
    cause: Throwable
) : java.io.IOException(
    "Unknown network outcome for $method $path: " +
        (cause.message ?: cause.javaClass.simpleName),
    cause
)

enum class GateCloseReason {
    NONE,
    INIT,
    RECONCILE_REQUIRED,
    USER_STREAM_DISCONNECTED,
    REST_RECONCILIATION_PENDING,
    UNKNOWN_CANCEL_OUTCOME,
    RECONCILE_REQUIRED_UNPROTECTED_POSITION,
    KILL_SWITCH
}

class ExecutionGate {
    private val reservedSymbols = LinkedHashSet<String>()

    @Volatile
    private var normalOpen = true

    @Volatile
    private var recoveryOpen = true

    @Volatile
    private var currentReason: GateCloseReason = GateCloseReason.NONE

    private val threadAdmissions =
        ThreadLocal.withInitial { mutableMapOf<String, AdmissionScope>() }

    @Synchronized
    fun tryAdmit(
        symbol: String,
        scope: AdmissionScope = AdmissionScope.NORMAL_EXECUTION
    ): Boolean {
        val normalized = symbol.trim().uppercase()
        if (normalized.isBlank()) return false

        val permitted = when (scope) {
            AdmissionScope.NORMAL_EXECUTION -> normalOpen
            AdmissionScope.PROTECTIVE_RECOVERY -> recoveryOpen
        }
        if (!permitted) return false

        return reservedSymbols.add(normalized)
    }

    @Synchronized
    fun release(symbol: String) {
        reservedSymbols.remove(symbol.trim().uppercase())
    }

    /**
     * Admission covers the complete caller-supplied mutation. The reservation
     * is held until block returns, so two mutations for the same symbol cannot
     * overlap even if they originate on different worker threads.
     */
    fun <T> executeWithAdmission(
        symbol: String,
        scope: AdmissionScope = AdmissionScope.NORMAL_EXECUTION,
        block: () -> T
    ): AdmissionResult<T> {
        val normalized = symbol.trim().uppercase()
        if (normalized.isBlank()) {
            return AdmissionResult.Rejected(currentReason)
        }

        val owned = threadAdmissions.get()
        val nestedScope = owned[normalized]
        if (nestedScope != null) {
            // A higher-level admission already owns this symbol on the same
            // worker. Reuse it so nested signedPost/signedDelete calls do not
            // self-reject. A NORMAL owner may invoke protective recovery, but
            // a recovery owner can never escalate into normal execution.
            if (
                nestedScope == AdmissionScope.PROTECTIVE_RECOVERY &&
                scope == AdmissionScope.NORMAL_EXECUTION
            ) {
                return AdmissionResult.Rejected(currentReason)
            }
            return AdmissionResult.Admitted(block())
        }

        val admitted = synchronized(this) {
            val permitted = when (scope) {
                AdmissionScope.NORMAL_EXECUTION -> normalOpen
                AdmissionScope.PROTECTIVE_RECOVERY -> recoveryOpen
            }
            if (!permitted) {
                return@synchronized false
            }
            reservedSymbols.add(normalized)
        }

        if (!admitted) {
            return AdmissionResult.Rejected(currentReason)
        }

        owned[normalized] = scope
        return try {
            AdmissionResult.Admitted(block())
        } finally {
            owned.remove(normalized)
            release(normalized)
        }
    }

    @Synchronized
    fun closeNormalExecution(reason: GateCloseReason) {
        normalOpen = false
        currentReason = reason
    }

    @Synchronized
    fun closeAll(reason: GateCloseReason) {
        normalOpen = false
        recoveryOpen = false
        currentReason = reason
    }

    @Synchronized
    fun openAll() {
        normalOpen = true
        recoveryOpen = true
        currentReason = GateCloseReason.NONE
    }

    // Backwards-compatible aliases used by the existing runtime.
    fun close() = closeNormalExecution(GateCloseReason.RECONCILE_REQUIRED)
    fun open() = openAll()

    @Synchronized
    fun isOpen(): Boolean = normalOpen

    @Synchronized
    fun isNormalOpen(): Boolean = normalOpen

    @Synchronized
    fun isRecoveryOpen(): Boolean = recoveryOpen

    @Synchronized
    fun closeReason(): GateCloseReason = currentReason

    @Synchronized
    fun isReserved(symbol: String): Boolean =
        reservedSymbols.contains(symbol.trim().uppercase())

    @Synchronized
    fun reservedCount(): Int = reservedSymbols.size
}
