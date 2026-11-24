package com.williamsbot

/**
 * UI signed GET priority. Only opportunistic fast-status display refreshes
 * are deferred; never suppress critical Binance STOP/position reconciliation,
 * public quotes, signed private-stream recovery or user-requested close.
 *
 * Defer if ANY account-wide P0 signed safety sweep is in flight. A deferred
 * position snapshot remains STALE until an independently completed read,
 * and cannot authorize exposure, flatness or protective STOP ownership.
 */
internal fun optionalFuturesDisplayReadAllowed(
    accountReconciliationRunning:Boolean,
    privateStreamReconciliationRunning:Boolean,
    positionManagementRunning:Boolean
):Boolean = !accountReconciliationRunning &&
    !privateStreamReconciliationRunning && !positionManagementRunning
