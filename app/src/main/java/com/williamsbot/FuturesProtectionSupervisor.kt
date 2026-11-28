package com.williamsbot

/**
 * Exchange-observed protection state. These labels are diagnostic state only:
 * they never grant order ownership, trailing authority, or permission to mutate.
 */
internal enum class FuturesProtectionSupervisorState {
    PROTECTED,
    UNPROTECTED,
    PROTECTION_PENDING,
    PROTECTION_UNKNOWN,
    RECOVERY_REQUIRED,
    CLOSED
}

internal fun futuresProtectionSupervisorState(
    signedSnapshotComplete: Boolean,
    positionOpen: Boolean,
    matchingFullStops: Int,
    mutationPending: Boolean = false,
    recoveryRequired: Boolean = false
): FuturesProtectionSupervisorState {
    require(matchingFullStops >= 0) { "Negative signed protective STOP count" }
    if (!signedSnapshotComplete) return FuturesProtectionSupervisorState.PROTECTION_UNKNOWN
    if (!positionOpen) return FuturesProtectionSupervisorState.CLOSED
    if (matchingFullStops > 1) return FuturesProtectionSupervisorState.PROTECTION_UNKNOWN
    if (matchingFullStops == 1) return FuturesProtectionSupervisorState.PROTECTED
    if (mutationPending) return FuturesProtectionSupervisorState.PROTECTION_PENDING
    if (recoveryRequired) return FuturesProtectionSupervisorState.RECOVERY_REQUIRED
    return FuturesProtectionSupervisorState.UNPROTECTED
}
