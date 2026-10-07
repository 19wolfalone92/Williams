package com.williamsbot.domain.runtime

interface RuntimeStateStore {
    val positionsJson: String
    val pendingEntriesJson: String
    val reconcileRequired: Boolean
    val killLatched: Boolean
    val executionGateSymbol: String
    val tradeJournalJson: String

    fun saveTradingState(
        positionsJson: String,
        pendingEntriesJson: String
    ): Boolean

    fun setReconcileRequired(required: Boolean): Boolean
    fun setKillLatched(latched: Boolean): Boolean
    fun setExecutionGateSymbol(symbol: String): Boolean
    fun clearExecutionGateSymbol(): Boolean
    fun saveTradeJournal(json: String): Boolean
}
