package com.williamsbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

class StandaloneRuntimeReconciliationTest {
    @Test
    fun inFlightRefreshRetainsLastKnownSignedShortWithoutClaimingVerification() {
        val lastSigned = JSONArray().put(
            JSONObject().put("symbol", "ETHUSDT")
                .put("positionAmt", -0.395).put("direction", "SHORT")
                .put("stop_verification_status", "EXTERNAL_PROTECTED")
        )
        val (rows, error, observedAt) =
            lastKnownFuturesPositionsForDisplay(lastSigned.toString() to 123456789L, null)
        assertEquals(1, rows.length())
        assertEquals("ETHUSDT", rows.getJSONObject(0).getString("symbol"))
        assertEquals("SHORT", rows.getJSONObject(0).getString("direction"))
        assertEquals(123456789L, observedAt)
        assertTrue(error.contains("stale"))
        // The non-empty error ensures status() cannot mark these rows as verified.
    }

    @Test
    fun firstLoadMissingExchangeSnapshotIsUnknownNotConfirmedFlat() {
        val (rows, error, observedAt) =
            lastKnownFuturesPositionsForDisplay(null, null)
        assertEquals(0, rows.length())
        assertEquals(0L, observedAt)
        assertTrue(error.contains("loading"))
        val (_, timeoutError, _) =
            lastKnownFuturesPositionsForDisplay(null, "signed REST timeout")
        assertEquals("signed REST timeout", timeoutError)
    }

    @Test
    fun queuedUserStreamReconciliationCountsAsLiveWork() {
        val findings = diagnosticConsistencyFindings(
            coordinatorState = "RECONCILE_REQUIRED",
            coordinatorReady = false,
            coordinatorBlocker = "exchange_reconciliation_in_progress",
            runtimeState = "RECONCILE_REQUIRED",
            runtimeReconcileRequired = true,
            executionEnabled = false,
            dailyRiskBaselineSource = "EXCHANGE_RECONCILED",
            reconciliationRunning = true
        )
        assertFalse(findings.any { it.startsWith("RECONCILIATION_STALE_BLOCKER:") })
    }

    @Test
    fun activeReconciliationDoesNotMisclassifyThePriorFsmAndLatchAsAStuckTask() {
        val findings = diagnosticConsistencyFindings(
            "RECONCILE_REQUIRED", false, "exchange_reconciliation_in_progress", "ENTRY_PENDING",
            false, false, "EXCHANGE_RECONCILED", true
        )
        assertTrue(findings.isEmpty())
        val finishedButStillBlocked = diagnosticConsistencyFindings(
            "RECONCILE_REQUIRED", false, "exchange_reconciliation_in_progress", "ENTRY_PENDING",
            false, false, "EXCHANGE_RECONCILED", false
        )
        assertTrue(finishedButStillBlocked.any { it.startsWith("RECONCILIATION_STALE_BLOCKER:") })
    }

    @Test
    fun healthAndExecutionContractUseOneAuthoritativeStateMapping() {
        assertEquals("RECONCILE_REQUIRED", authoritativeRuntimeDisplayState(true, true, false, false, "RECONCILE_REQUIRED", "ENTRY_PENDING"))
        assertEquals("ENTRY_PENDING", authoritativeRuntimeDisplayState(true, true, false, false, "ENTRY_PENDING", "READY_FLAT"))
        assertEquals("OPEN", authoritativeRuntimeDisplayState(true, true, false, false, "OPEN_PROTECTED", "ENTRY_PENDING"))
        assertEquals("STOPPED", authoritativeRuntimeDisplayState(true, false, false, false, "READY", "STOPPED"))
    }

    @Test
    fun inProgressCoordinatorWithNoRunningTaskIsFlagged() {
        val findings = diagnosticConsistencyFindings(
            coordinatorState = "RECONCILE_REQUIRED",
            coordinatorReady = false,
            coordinatorBlocker = "exchange_reconciliation_in_progress",
            runtimeState = "RECONCILE_REQUIRED",
            runtimeReconcileRequired = true,
            executionEnabled = false,
            dailyRiskBaselineSource = "EXCHANGE_RECONCILED",
            reconciliationRunning = false
        )
        assertTrue(findings.any { it.startsWith("RECONCILIATION_STALE_BLOCKER:") })
    }

    @Test
    fun onlyCurrentConnectedSessionCanClearSyncLatch() {
        assertTrue(mayCommitUserStreamSync(true, false, 7L, 7L))
        assertFalse(mayCommitUserStreamSync(false, false, 7L, 7L))
        assertFalse(mayCommitUserStreamSync(true, true, 7L, 7L))
        assertFalse(mayCommitUserStreamSync(true, false, 6L, 7L))
    }

    @Test
    fun completeSignedDataCanSynchronizeWhileNewEntriesStayBlocked() {
        val blocked=JSONObject().put("ok",false).put("state","POSITION_UNPROTECTED")
            .put("exchange_snapshot_complete",true).put("positions_checked",3)
            .put("exchange_protection_observations",JSONArray())
        assertTrue(futuresSignedSnapshotComplete(blocked))
        assertFalse(blocked.getBoolean("ok"))
        assertEquals("POSITION_UNPROTECTED",blocked.getString("state"))
        blocked.remove("exchange_protection_observations")
        assertFalse(futuresSignedSnapshotComplete(blocked))
        assertFalse(futuresSignedSnapshotComplete(JSONObject().put("ok",true)))
        assertFalse(futuresSignedSnapshotComplete(JSONObject()
            .put("ok",false).put("blocker","signed REST timeout")))
    }

    @Test
    fun privateStreamLockContentionIsDeferredWithoutApprovingExchangeState() {
        assertTrue(deferrableFuturesReconciliation("USER_STREAM_RECONCILIATION"))
        assertTrue(deferrableFuturesReconciliation("POSITION_MANAGEMENT_RECONCILIATION"))
        for (purpose in listOf("STARTUP_RECONCILIATION", "USER_APPROVED_RECOVERY",
                "KILL_SWITCH_RECONCILIATION", "LIQUIDATION_GUARD_EXIT")) {
            assertFalse(deferrableFuturesReconciliation(purpose))
        }
        val deferred = JSONObject()
            .put("ok", false)
            .put("deferred_to_active_reconciliation", true)
            .put("blocker", "exchange_reconciliation_in_progress")
        // Deferred is never an exchange-authenticated sync or release approval.
        assertFalse(futuresSignedSnapshotComplete(deferred))
        assertFalse(deferred.getBoolean("ok"))
    }

    @Test
    fun webSocketConnectivityNeverAuthorizesEntriesFromExpiredSignedRestProof() {
        val now = 1_800_000_000_000L
        assertFalse(signedFuturesReconciliationFresh(0L, now))
        assertFalse(signedFuturesReconciliationFresh(now + 1L, now))
        assertTrue(signedFuturesReconciliationFresh(now - 45_000L, now))
        assertTrue(signedFuturesReconciliationFresh(now - 90_000L, now))
        assertFalse(signedFuturesReconciliationFresh(now - 90_001L, now))
        assertFalse(signedFuturesReconciliationFresh(now - 1L, now, 0L))
        // A permanently connected socket without new signed proof is not a
        // substitute for a current position/order/STOP snapshot.
        assertTrue(userStreamSyncIsFresh(true, false, now - 3_600_000L, now))
        assertFalse(signedFuturesReconciliationFresh(now - 3_600_000L, now))
    }

    @Test
    fun postIncidentPrivateStreamTelemetryCannotMistakeConnectedSocketForRestProof() {
        val now=1_800_000_000_000L
        assertEquals("STALLED_IN_FLIGHT_NO_NEW_ENTRY_AUTHORITY",
            privateStreamReconcileDiagnosticState(true,true,true,
                now-375_345L,0L,now))
        assertEquals("SIGNED_RECONCILIATION_IN_FLIGHT",
            privateStreamReconcileDiagnosticState(true,true,true,
                now-14_000L,0L,now))
        assertEquals("PRIVATE_SOCKET_NOT_SIGNED_SYNCED",
            privateStreamReconcileDiagnosticState(true,true,false,
                now-375_345L,0L,now))
        assertEquals("PRIVATE_SOCKET_DISCONNECTED",
            privateStreamReconcileDiagnosticState(false,true,false,
                now-375_345L,0L,now))
        assertEquals("CURRENT_SOCKET_SYNC_PROVED",
            privateStreamReconcileDiagnosticState(true,false,false,
                now-375_345L,now-1_000L,now))
        // Even if syncRequired was briefly released, an in-flight task
        // older than the deadline must retain the stalled warning.
        assertEquals("STALLED_IN_FLIGHT_NO_NEW_ENTRY_AUTHORITY",
            privateStreamReconcileDiagnosticState(true,false,true,
                now-300_001L,now-20L,now))
    }

    @Test
    fun reconciliationLifecycleHasAnExplicitTimeout() {
        assertTrue(reconciliationTaskLifecycleHealthy(false, 0L, 500_000L))
        assertTrue(reconciliationTaskLifecycleHealthy(true, 100_000L, 400_000L))
        assertFalse(reconciliationTaskLifecycleHealthy(true, 100_000L, 400_001L))
        assertFalse(reconciliationTaskLifecycleHealthy(true, 0L, 400_000L))
        assertFalse(reconciliationTaskLifecycleHealthy(true, 500_000L, 400_000L))
    }
}
