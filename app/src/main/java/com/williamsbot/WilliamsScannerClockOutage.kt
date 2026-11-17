package com.williamsbot

/**
 * All-symbol failure caused by ONE shared prerequisite (public Binance UTC).
 * This is a retryable diagnostic state, NOT successful H1 analysis and NEVER
 * an entry permit. Do not hide heterogeneous analysis failures as clock faults.
 */
internal fun scannerAllSymbolClockOutage(
    expectedSymbols: Int,
    completedAnalyses: Int,
    symbolErrors: Map<String, String>
): Boolean = expectedSymbols > 0 && completedAnalyses == 0 &&
    symbolErrors.size == expectedSymbols && symbolErrors.values.all { detail ->
        detail.contains("USD-M Futures clock expired") ||
            detail.contains("USD-M Futures server clock sample unverified") ||
            detail.contains("USD-M Futures clock HTTP")
    }
