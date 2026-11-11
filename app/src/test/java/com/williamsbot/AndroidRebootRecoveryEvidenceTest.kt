package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AndroidRebootRecoveryEvidenceTest {
    private val now=1_800_000_000_000L
    private val boot=now-100_000L
    private fun record(code:String,at:Long)=JSONObject().put("code",code).put("at_ms",at)
    private fun chain()=JSONObject().put("events",JSONArray()
        .put(record("BOOT_COMPLETED_OBSERVED",boot))
        .put(record("BOOT_OPT_IN_VERIFIED",boot+2_000))
        .put(record("BOOT_FGS_START_REQUESTED",boot+2_001))
        .put(record("SERVICE_FOREGROUND_CREATED",boot+3_000)))
    private fun android()=JSONObject().put("foreground_service_active",true)
        .put("cpu_wake_lock_held",true).put("runtime_worker_heartbeat_age_ms",2_000L)
    private fun status()=JSONObject().put("running",true)
        .put("user_ws_connected",true).put("user_stream_sync_required",false)
        .put("user_stream_last_successful_sync_ms",boot+10_000)

    @Test fun coldBootNeedsActualExchangeResyncAndLiveWorker() {
        val result=AndroidRebootRecoveryEvidence.assess(chain(),android(),status(),now)
        assertTrue(result.getBoolean("recovery_technical_proven"))
        assertTrue(result.getBoolean("signed_sync_after_boot"))
        assertEquals("BOOT_MONITOR_AND_SIGNED_RECONCILIATION_OBSERVED",result.getString("status"))
        assertFalse(result.getBoolean("continuous_24h_proven"))
    }

    @Test fun oldExchangeSnapshotOrSyncLatchCannotProveRebootRecovery() {
        val old=status().put("user_stream_last_successful_sync_ms",boot-1_000)
        assertFalse(AndroidRebootRecoveryEvidence.assess(chain(),android(),old,now)
            .getBoolean("recovery_technical_proven"))
        old.put("user_stream_last_successful_sync_ms",boot+10_000)
            .put("user_stream_sync_required",true)
        assertEquals("BOOT_SIGNED_RECONCILIATION_NOT_VERIFIED",
            AndroidRebootRecoveryEvidence.assess(chain(),android(),old,now).getString("status"))
    }

    @Test fun diag9NewApkFirstGuardNotMisreportedAsDeadEngine() {
        // A real Samsung archive captured ~29s after PACKAGE_REPLACED:
        // new service + verified worker startup, but guard heartbeat still null.
        val reboot=now-3L*60L*60L*1000L
        val service=now-29_000L
        val events=JSONObject().put("events",JSONArray()
            .put(record("BOOT_COMPLETED_OBSERVED",reboot))
            .put(record("BOOT_OPT_IN_VERIFIED",reboot+1_000L))
            .put(record("BOOT_FGS_START_REQUESTED",service-600L))
            .put(record("SERVICE_FOREGROUND_CREATED",service))
            .put(record("SERVICE_RUNTIME_OBSERVED",service+10_000L)))
        val phone=android().put("runtime_worker_heartbeat_age_ms",JSONObject.NULL)
        val awaiting= status().put("user_stream_last_successful_sync_ms",0L)
            .put("user_stream_sync_required",true)
        val assessed=AndroidRebootRecoveryEvidence.assess(events,phone,awaiting,now)
        assertEquals("BOOT_WORKER_HEARTBEAT_PENDING_AFTER_VERIFIED_START",
            assessed.getString("status"))
        assertTrue(assessed.getBoolean("worker_startup_observed_pending_heartbeat"))
        assertFalse(assessed.getBoolean("worker_live_now"))
        assertFalse(assessed.getBoolean("signed_sync_after_boot"))
        assertFalse(assessed.getBoolean("recovery_technical_proven"))
        // Once a fresh loop timestamp exists the failure, if any, belongs to
        // the unsigned CURRENT user-stream sync, not an invented dead worker.
        phone.put("runtime_worker_heartbeat_age_ms",2_000L)
        val afterHeartbeat=AndroidRebootRecoveryEvidence.assess(events,phone,awaiting,now)
        assertEquals("BOOT_SIGNED_RECONCILIATION_NOT_VERIFIED",
            afterHeartbeat.getString("status"))
        assertFalse(afterHeartbeat.getBoolean("recovery_technical_proven"))
    }

    @Test fun missedBroadcastAndStaleWorkerCannotFakeRecovery() {
        assertEquals("NO_REBOOT_EVIDENCE_RETAINED",
            AndroidRebootRecoveryEvidence.assess(JSONObject(),android(),status(),now).getString("status"))
        val stale=android().put("runtime_worker_heartbeat_age_ms",181_000)
        assertEquals("BOOT_NATIVE_WORKER_NOT_PROVEN_LIVE",
            AndroidRebootRecoveryEvidence.assess(chain(),stale,status(),now).getString("status"))
        val missing=JSONObject().put("events",JSONArray().put(record("BOOT_COMPLETED_OBSERVED",boot)))
        assertEquals("BOOT_OPT_IN_NOT_VERIFIED",
            AndroidRebootRecoveryEvidence.assess(missing,android(),status(),now).getString("status"))
    }
}
