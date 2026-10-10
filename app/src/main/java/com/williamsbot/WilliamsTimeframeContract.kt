package com.williamsbot

/**
 * Owner-approved bot contract, not a Bill Williams book rule.
 * Only H1 creates canonical campaign signals. D1/H4 are context; M15 is
 * execution/fill monitoring; M5 is diagnostics and replay only.
 */
internal object WilliamsTimeframeContract {
    const val MACRO_CONTEXT = "1d"
    const val HIGHER_CONTEXT = "4h"
    const val CANONICAL_DECISION = "1h"
    const val EXECUTION_MONITOR = "15m"
    const val DIAGNOSTIC_REPLAY = "5m"

    fun canCreateCanonicalSignal(frame: String): Boolean =
        frame.trim().lowercase() == CANONICAL_DECISION
}
