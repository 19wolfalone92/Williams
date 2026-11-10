package com.williamsbot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadOnlySignedForensicsPriorityTest {
    private fun blocked(
        account:Boolean=false, privateWs:Boolean=false,
        position:Boolean=false, wsSync:Boolean=false,
        runtimeLatch:Boolean=false, state:String="READY"
    ) = readOnlySignedForensicsMustDefer(
        accountReconciliationRunning=account,
        privateReconciliationRunning=privateWs,
        positionManagementRunning=position,
        privateStreamSyncRequired=wsSync,
        runtimeReconcileRequired=runtimeLatch,
        coordinatorState=state
    )

    @Test fun optionalSignedSweepRunsOnlyWhenAccountIsFlatAndVerified() {
        assertFalse(blocked())
        assertTrue(blocked(account=true))
        assertTrue(blocked(privateWs=true))
        assertTrue(blocked(position=true))
        assertTrue(blocked(wsSync=true))
        assertTrue(blocked(runtimeLatch=true))
    }

    @Test fun incompleteOrActiveCampaignStateCannotBeBypassedByQuietSocket() {
        for (state in listOf("UNINITIALIZED","RECONCILE_REQUIRED",
                "ENTRY_PENDING","OPEN_PROTECTED","POSITION_UNPROTECTED",
                "EXIT_PENDING","KILL_SWITCH_LATCHED")) {
            assertTrue("P0 coordinator state must preempt P1: $state", blocked(state=state))
        }
    }
}
