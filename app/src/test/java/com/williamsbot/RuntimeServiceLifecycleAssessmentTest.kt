package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RuntimeServiceLifecycleAssessmentTest {
    private val now=1_800_000_000_000L
    private fun event(code:String,ago:Long=1_000L):JSONObject =
        JSONObject().put("code",code).put("at_ms",now-ago)
    private fun continuity(events:JSONArray,exits:JSONArray=JSONArray(),status:String="OBSERVED_SYSTEM_HISTORY"):JSONObject =
        JSONObject().put("events",events).put("process_exits",exits)
            .put("process_exits_status",status)

    @Test fun repeatedLegacyStartRequestsAreNotMisclassifiedAsProcessRestarts() {
        val events=JSONArray().put(event("SERVICE_START_ATTEMPT",10_000))
            .put(event("SERVICE_RUNTIME_OBSERVED",9_999))
            .put(event("SERVICE_START_ATTEMPT",5_000))
            .put(event("SERVICE_RUNTIME_OBSERVED",4_999))
        val result=RuntimeServiceLifecycleAssessment.summarize(continuity(events),now)
        assertEquals(2,result.getInt("service_start_attempt_count"))
        assertEquals(0,result.getInt("foreground_service_created_count"))
        assertEquals(0,result.getInt("observed_process_exit_count"))
        assertFalse(result.getBoolean("service_start_attempt_is_restart_proof"))
        assertFalse(result.getBoolean("continuous_24h_monitoring_verified"))
        assertEquals("NO_FAILURE_IN_RETAINED_EVIDENCE_ONLY",result.getString("assessment"))
    }

    @Test fun actualAbnormalExitAndSubsequentFgSCreationAreDistinctEvidence() {
        val events=JSONArray().put(event("SERVICE_FOREGROUND_CREATED",1_000))
            .put(event("SERVICE_START_ATTEMPT",500))
        val exits=JSONArray().put(JSONObject().put("reason","CRASH")
            .put("at_ms",now-5_000))
        val result=RuntimeServiceLifecycleAssessment.summarize(continuity(events,exits),now)
        assertEquals(1,result.getInt("observed_process_exit_count"))
        assertEquals(1,result.getInt("abnormal_process_exit_count"))
        assertEquals(1,result.getInt("exit_followed_by_foreground_creation_count"))
        assertEquals(1,result.getInt("foreground_service_created_count"))
        assertEquals("FAULT_OBSERVED",result.getString("assessment"))
    }

    @Test fun staleAndFutureEvidenceCannotPretendToProveCurrent24Hours() {
        val events=JSONArray().put(event("WORKER_HEARTBEAT_STALE",86_400_001L))
            .put(JSONObject().put("code","SERVICE_FOREGROUND_CREATED").put("at_ms",now+5_000L))
        val exit=JSONArray().put(JSONObject().put("reason","LOW_MEMORY")
            .put("at_ms",now-86_400_001L))
        val result=RuntimeServiceLifecycleAssessment.summarize(continuity(events,exit,"READ_FAILED"),now)
        assertEquals(0,result.getInt("worker_heartbeat_stale_count"))
        assertEquals(0,result.getInt("observed_process_exit_count"))
        assertEquals(0,result.getInt("foreground_service_created_count"))
        assertEquals("READ_FAILED",result.getString("system_exit_history_status"))
        assertFalse(result.getBoolean("continuous_24h_monitoring_verified"))
    }

    @Test fun stalledRecoveryEvidenceDistinguishesRequestedFromDeferredAndNoWorkerProof() {
        val events=JSONArray()
            .put(event("WORKER_HEARTBEAT_STALE",10_000))
            .put(event("STALE_WORKER_RECOVERY_DEFERRED",9_000))
            .put(event("STALE_WORKER_INTERRUPT_REQUESTED",8_000))
            .put(event("WORKER_HEARTBEAT_LOST",7_500))
            .put(event("WORKER_HEARTBEAT_RECOVERED",7_000))
        val result=RuntimeServiceLifecycleAssessment.summarize(continuity(events),now)
        assertEquals(1,result.getInt("stale_worker_interrupt_requested_count"))
        assertEquals(1,result.getInt("stale_worker_recovery_deferred_count"))
        assertEquals(0,result.getInt("stale_worker_recovery_not_ready_count"))
        assertEquals(1,result.getInt("worker_heartbeat_stale_count"))
        assertEquals(1,result.getInt("worker_heartbeat_recovered_count"))
        assertEquals(1,result.getInt("worker_heartbeat_lost_count"))
        assertEquals("FAULT_OBSERVED",result.getString("assessment"))
        assertFalse(result.getBoolean("continuous_24h_monitoring_verified"))
    }

    @Test fun failureOfAutostartIsNotHiddenByLaterGoodHeartbeat() {
        val events=JSONArray().put(event("SERVICE_AUTOSTART_FAILED",20_000))
            .put(event("SERVICE_RUNTIME_OBSERVED",5_000))
        val result=RuntimeServiceLifecycleAssessment.summarize(continuity(events),now)
        assertEquals(1,result.getInt("service_autostart_failure_count"))
        assertEquals("FAULT_OBSERVED",result.getString("assessment"))
    }
}
