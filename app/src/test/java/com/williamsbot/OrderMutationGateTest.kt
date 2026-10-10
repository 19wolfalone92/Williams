package com.williamsbot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderMutationGateTest {
    @Test
    fun unknownRuntimeFreshnessBlocksOrderMutation() {
        val evidence = OrderMutationEvidence(
            spotTestnet = true,
            runtimeReady = true,
            marketDataFresh = true,
            accountFresh = null,
            historyFresh = null,
            privateStreamFresh = null,
            reconciliationFresh = true,
            allRuntimeFreshnessProofsComplete = false
        )

        val blockers = OrderMutationGate.blockers(evidence)
        assertFalse(blockers.isEmpty())
        assertTrue(blockers.contains("account_fresh_unknown"))
        assertTrue(blockers.contains("history_fresh_unknown"))
        assertTrue(blockers.contains("private_stream_fresh_unknown"))
        assertTrue(blockers.contains("runtime_freshness_contract_failed"))
    }

    @Test
    fun mainnetOrUnknownEnvironmentBlocksMutation() {
        val evidence = OrderMutationEvidence(
            spotTestnet = false,
            runtimeReady = true,
            marketDataFresh = true,
            accountFresh = true,
            historyFresh = true,
            privateStreamFresh = true,
            reconciliationFresh = true,
            allRuntimeFreshnessProofsComplete = true
        )
        assertTrue(OrderMutationGate.blockers(evidence).contains("spot_testnet_failed"))
    }

    @Test
    fun onlyCompleteFreshSpotTestnetEvidencePasses() {
        val evidence = OrderMutationEvidence(
            spotTestnet = true,
            runtimeReady = true,
            marketDataFresh = true,
            accountFresh = true,
            historyFresh = true,
            privateStreamFresh = true,
            reconciliationFresh = true,
            allRuntimeFreshnessProofsComplete = true
        )
        assertTrue(OrderMutationGate.blockers(evidence).isEmpty())
    }
}
