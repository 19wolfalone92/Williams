package com.williamsbot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalRecoveryGateContractTest {
    @Test
    fun legacyReadyStateCannotOverrideMissingGlobalFinancialProof() {
        assertFalse(
            GlobalRecoveryGateContract.entryAllowed(
                globalFinancialRecoveryComplete = false,
                reconcileRequired = false
            )
        )
    }

    @Test
    fun reconciliationLatchAlwaysBlocksEntryEvenIfProofFlagIsTrue() {
        assertFalse(
            GlobalRecoveryGateContract.entryAllowed(
                globalFinancialRecoveryComplete = true,
                reconcileRequired = true
            )
        )
    }

    @Test
    fun onlyExplicitCompleteProofAndClearReconciliationAllowEntry() {
        assertTrue(
            GlobalRecoveryGateContract.entryAllowed(
                globalFinancialRecoveryComplete = true,
                reconcileRequired = false
            )
        )
    }
}
