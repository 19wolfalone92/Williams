package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WilliamsPrivateStreamRecoveryPolicyTest {
    @Test fun signedAuditMayCompleteDataSyncButMustNotUnlockUnownedCampaign() {
        val riskBlocked=JSONObject()
            .put("ok",false)
            .put("actual_risk_audit_blocked",true)
            .put("exchange_snapshot_complete",true)
            .put("positions_checked",7)
            .put("exchange_protection_observations",JSONArray())
        // Successful signed observation != permission to take ownership or trade.
        assertTrue(futuresSignedSnapshotComplete(riskBlocked))
        assertFalse(riskBlocked.getBoolean("ok"))
        assertTrue(mayCommitUserStreamSync(true,false,8,8))
        assertFalse(mayCommitUserStreamSync(false,false,8,8))
        assertFalse(mayCommitUserStreamSync(true,false,7,8))
        assertFalse(mayCommitUserStreamSync(true,true,8,8))
        riskBlocked.remove("exchange_protection_observations")
        assertFalse(futuresSignedSnapshotComplete(riskBlocked))
    }

    @Test fun eventBurstsDoNotSpinForeverOrCertifyStaleSnapshot() {
        assertTrue(privateStreamReconciliationMayRepeat(true,1))
        assertFalse(privateStreamReconciliationMayRepeat(true,2))
        assertFalse(privateStreamReconciliationMayRepeat(false,1))
        assertFalse(privateStreamReconciliationMayRepeat(true,0))
        assertFalse(privateStreamReconciliationMayRepeat(true,3))
        assertFalse(privateStreamReconciliationMayRepeat(true,1,0))
        assertFalse(mayCommitUserStreamSync(true,true,4,4))
    }

    @Test fun retryThrottleStaysFailClosedUntilCurrentSignedSnapshot() {
        assertTrue(shouldRetryUserStreamReconciliation(true,true,false,10_000,20_000))
        assertFalse(shouldRetryUserStreamReconciliation(true,false,false,10_000,20_000))
        assertFalse(shouldRetryUserStreamReconciliation(false,true,false,10_000,20_000))
        assertFalse(shouldRetryUserStreamReconciliation(true,true,true,10_000,20_000))
        assertFalse(shouldRetryUserStreamReconciliation(true,true,false,10_000,19_999))
    }
}
