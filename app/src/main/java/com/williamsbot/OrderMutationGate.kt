package com.williamsbot

/**
 * Fail-closed admission contract for Android-originated Binance order mutations.
 * A missing measurement is UNKNOWN and is never promoted to ready.
 */
data class OrderMutationEvidence(
    val spotTestnet: Boolean?,
    val runtimeReady: Boolean?,
    val marketDataFresh: Boolean?,
    val accountFresh: Boolean?,
    val historyFresh: Boolean?,
    val privateStreamFresh: Boolean?,
    val reconciliationFresh: Boolean?,
    val allRuntimeFreshnessProofsComplete: Boolean?
)

object OrderMutationGate {
    fun blockers(evidence: OrderMutationEvidence): List<String> = buildList {
        fun check(name: String, value: Boolean?) {
            when (value) {
                true -> Unit
                false -> add("${name}_failed")
                null -> add("${name}_unknown")
            }
        }
        check("spot_testnet", evidence.spotTestnet)
        check("runtime_ready", evidence.runtimeReady)
        check("market_data_fresh", evidence.marketDataFresh)
        check("account_fresh", evidence.accountFresh)
        check("history_fresh", evidence.historyFresh)
        check("private_stream_fresh", evidence.privateStreamFresh)
        check("reconciliation_fresh", evidence.reconciliationFresh)
        check("runtime_freshness_contract", evidence.allRuntimeFreshnessProofsComplete)
    }

    fun requireReady(evidence: OrderMutationEvidence) {
        val reasons = blockers(evidence)
        check(reasons.isEmpty()) {
            "ORDER_MUTATION_BLOCKED: " + reasons.joinToString(",")
        }
    }
}
