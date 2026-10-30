package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject

/**
 * Read-only, bounded lifecycle evidence. A service start request is NOT a
 * process restart. Events use a 32-row durable ring; a missing event is never
 * proof that Android kept the process alive over a full day.
 *
 * This classifier is not an execution-admission gate and never touches Binance.
 */
internal object RuntimeServiceLifecycleAssessment {
    private const val WINDOW_MS = 24L * 60L * 60L * 1000L
    private val abnormalExits = setOf(
        "LOW_MEMORY", "CRASH", "CRASH_NATIVE", "ANR", "EXCESSIVE_RESOURCE_USAGE"
    )

    fun summarize(continuity:JSONObject, nowMs:Long):JSONObject {
        val fromMs=(nowMs-WINDOW_MS).coerceAtLeast(0L)
        val events=continuity.optJSONArray("events") ?: JSONArray()
        val exits=continuity.optJSONArray("process_exits") ?: JSONArray()
        val eventCounts=mutableMapOf<String,Int>()
        var lastForegroundCreated=0L
        var firstObservedEvent=0L

        for(i in 0 until events.length()) {
            val event=events.optJSONObject(i) ?: continue
            val time=event.optLong("at_ms",0L)
            if(time<=0L || time<fromMs || time>nowMs) continue
            if(firstObservedEvent==0L || time<firstObservedEvent) firstObservedEvent=time
            val code=event.optString("code")
            eventCounts[code]=(eventCounts[code] ?: 0)+1
            if(code=="SERVICE_FOREGROUND_CREATED") {
                lastForegroundCreated=maxOf(lastForegroundCreated,time)
            }
        }

        var observedExits=0
        var abnormal=0
        var exitThenForegroundCreated=0
        for(i in 0 until exits.length()) {
            val exit=exits.optJSONObject(i) ?: continue
            val time=exit.optLong("at_ms",0L)
            if(time<=0L || time<fromMs || time>nowMs) continue
            observedExits++
            val reason=exit.optString("reason")
            if(reason in abnormalExits) abnormal++
            if(lastForegroundCreated>time) exitThenForegroundCreated++
        }

        val exitHistory=continuity.optString("process_exits_status")
        return JSONObject()
            .put("window_ms",WINDOW_MS)
            .put("ring_buffer_limit",32)
            .put("first_retained_event_at_ms",firstObservedEvent)
            .put("foreground_service_created_count",eventCounts["SERVICE_FOREGROUND_CREATED"] ?: 0)
            .put("service_destroyed_count",eventCounts["SERVICE_DESTROYED"] ?: 0)
            .put("service_start_attempt_count",eventCounts["SERVICE_START_ATTEMPT"] ?: 0)
            .put("service_autostart_failure_count",eventCounts["SERVICE_AUTOSTART_FAILED"] ?: 0)
            .put("worker_heartbeat_stale_count",eventCounts["WORKER_HEARTBEAT_STALE"] ?: 0)
            .put("worker_heartbeat_recovered_count",eventCounts["WORKER_HEARTBEAT_RECOVERED"] ?: 0)
            .put("worker_heartbeat_lost_count",eventCounts["WORKER_HEARTBEAT_LOST"] ?: 0)
            .put("stale_worker_interrupt_requested_count",
                eventCounts["STALE_WORKER_INTERRUPT_REQUESTED"] ?: 0)
            .put("stale_worker_recovery_deferred_count",
                eventCounts["STALE_WORKER_RECOVERY_DEFERRED"] ?: 0)
            .put("stale_worker_recovery_not_ready_count",
                eventCounts["STALE_WORKER_RECOVERY_NOT_READY"] ?: 0)
            .put("observed_process_exit_count",observedExits)
            .put("abnormal_process_exit_count",abnormal)
            .put("exit_followed_by_foreground_creation_count",exitThenForegroundCreated)
            .put("system_exit_history_status",exitHistory)
            .put("service_start_attempt_is_restart_proof",false)
            .put("continuous_24h_monitoring_verified",false)
            .put("assessment",when {
                abnormal>0 || (eventCounts["SERVICE_AUTOSTART_FAILED"] ?: 0)>0 ||
                    (eventCounts["WORKER_HEARTBEAT_STALE"] ?: 0)>0 -> "FAULT_OBSERVED"
                observedExits>0 || (eventCounts["SERVICE_DESTROYED"] ?: 0)>0 -> "LIFECYCLE_CHANGE_OBSERVED"
                else -> "NO_FAILURE_IN_RETAINED_EVIDENCE_ONLY"
            })
    }
}
