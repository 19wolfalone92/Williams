package com.williamsbot

/** Local final gate for NEW exposure, after all potentially slow preflights. */
internal fun futuresExposureTransportBlocker(
    serviceActive: Boolean,
    workerHeartbeatStale: Boolean,
    privateConnected: Boolean,
    privateSyncRequired: Boolean,
    lastSignedReconciliationAtMs: Long,
    nowMs: Long
): String? = when {
    !serviceActive -> "FOREGROUND_SERVICE_NOT_CONFIRMED_BEFORE_ENTRY"
    workerHeartbeatStale -> "WORKER_HEARTBEAT_STALE_BEFORE_ENTRY"
    !privateConnected || privateSyncRequired -> "PRIVATE_STREAM_UNSYNCHRONIZED_BEFORE_ENTRY"
    !signedFuturesReconciliationFresh(lastSignedReconciliationAtMs, nowMs) ->
        "SIGNED_RECONCILIATION_STALE_BEFORE_ENTRY"
    else -> null
}
