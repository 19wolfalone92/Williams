package com.williamsbot

/**
 * Entry admission contract shared by native runtime gates.
 *
 * Recovery is a global financial proof, not an order/intent status. A missing
 * proof must fail closed even when the legacy reconciliation flag is clear.
 */
internal object GlobalRecoveryGateContract {
    fun entryAllowed(
        globalFinancialRecoveryComplete: Boolean,
        reconcileRequired: Boolean
    ): Boolean = globalFinancialRecoveryComplete && !reconcileRequired
}
