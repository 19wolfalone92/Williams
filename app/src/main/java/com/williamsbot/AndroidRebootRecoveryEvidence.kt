package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject

/**
 * Evidence from this installation's most recent reboot, not a device command.
 * It never starts the engine, touches user credentials, or queries Binance.
 * A system broadcast + foreground service alone is insufficient: require
 * current native worker heartbeat and same-post-boot signed REST/user sync.
 */
internal object AndroidRebootRecoveryEvidence {
    private const val DAY_MS = 24L * 60L * 60L * 1000L

    fun assess(continuity:JSONObject, android:JSONObject, status:JSONObject, nowMs:Long):JSONObject {
        val events=continuity.optJSONArray("events") ?: JSONArray()
        val earliest=(nowMs-DAY_MS).coerceAtLeast(0L)
        fun latestAfter(code:String, lowerBound:Long):Long {
            var found=0L
            for(i in 0 until events.length()) {
                val row=events.optJSONObject(i) ?: continue
                val timestamp=row.optLong("at_ms",0L)
                if(row.optString("code")==code &&
                    timestamp>0L && timestamp>=lowerBound && timestamp<=nowMs) {
                    found=maxOf(found,timestamp)
                }
            }
            return found
        }

        val bootAt=latestAfter("BOOT_COMPLETED_OBSERVED",earliest)
        val optedInAt=if(bootAt>0L) latestAfter("BOOT_OPT_IN_VERIFIED",bootAt) else 0L
        val requestedAt=if(optedInAt>0L) latestAfter("BOOT_FGS_START_REQUESTED",optedInAt) else 0L
        // Android can create the foreground service on another thread just
        // before BOOT_COMPLETED is persisted. Diagnostic #20 observed FGS
        // creation 21 ms before that broadcast, with the subsequent FGS
        // start request recorded only later. Do not report a false failure.
        //
        // Require corroborating elapsedRealtime timestamps from the SAME
        // boot epoch; a persisted wall-clock event alone is not proof.
        val serviceAfterRequestAt=if(requestedAt>0L)
            latestAfter("SERVICE_FOREGROUND_CREATED",requestedAt) else 0L
        var serviceBeforeBroadcastAt=0L
        if(requestedAt>0L && serviceAfterRequestAt==0L && bootAt>0L) {
            var bootElapsed=-1L
            for(i in 0 until events.length()) {
                val row=events.optJSONObject(i) ?: continue
                if(row.optString("code")=="BOOT_COMPLETED_OBSERVED" &&
                    row.optLong("at_ms",0L)==bootAt)
                    bootElapsed=row.optLong("elapsed_ms",-1L)
            }
            if(bootElapsed>0L) {
                for(i in 0 until events.length()) {
                    val row=events.optJSONObject(i) ?: continue
                    if(row.optString("code")!="SERVICE_FOREGROUND_CREATED") continue
                    val at=row.optLong("at_ms",0L)
                    val elapsed=row.optLong("elapsed_ms",-1L)
                    val wallGap=bootAt-at
                    val elapsedGap=bootElapsed-elapsed
                    if(at>0L && elapsed>0L && wallGap in 0L..2_000L &&
                        elapsedGap in 0L..2_000L &&
                        kotlin.math.abs(wallGap-elapsedGap)<=1_000L) {
                        serviceBeforeBroadcastAt=maxOf(serviceBeforeBroadcastAt,at)
                    }
                }
            }
        }
        val serviceAt=if(serviceAfterRequestAt>0L)
            serviceAfterRequestAt else serviceBeforeBroadcastAt
        val serviceWasCreatedBeforeBroadcast=serviceBeforeBroadcastAt>0L
        val signedSyncAt=status.optLong("user_stream_last_successful_sync_ms",0L)
        val heartbeatAge=android.optLong("runtime_worker_heartbeat_age_ms",Long.MAX_VALUE)
        val runtimeObservedAt=if(serviceAt>0L)
            latestAfter("SERVICE_RUNTIME_OBSERVED",serviceAt) else 0L
        // A verified start is NOT a verified continuing worker heartbeat.
        // In the first service-guard interval after package replacement, the
        // service's cached heartbeat can still be absent even though the
        // native startup snapshot proved running+workerAlive. Report this as
        // pending evidence, never as recovered/ready or a confirmed crash.
        val startupHeartbeatPending=serviceAt>0L && runtimeObservedAt>=serviceAt &&
            nowMs>=serviceAt && nowMs-serviceAt<=60_000L &&
            heartbeatAge==Long.MAX_VALUE && status.optBoolean("running") &&
            android.optBoolean("foreground_service_active") &&
            android.optBoolean("cpu_wake_lock_held")
        val workerLive=status.optBoolean("running") &&
            android.optBoolean("foreground_service_active") &&
            android.optBoolean("cpu_wake_lock_held") &&
            heartbeatAge>=0L && heartbeatAge<=30_000L
        val signedAfterBoot=bootAt>0L && signedSyncAt>=bootAt && signedSyncAt<=nowMs &&
            status.optBoolean("user_ws_connected") &&
            !status.optBoolean("user_stream_sync_required",true)
        val statusCode=when {
            bootAt==0L -> "NO_REBOOT_EVIDENCE_RETAINED"
            optedInAt==0L -> "BOOT_OPT_IN_NOT_VERIFIED"
            requestedAt==0L -> "BOOT_FGS_REQUEST_NOT_OBSERVED"
            serviceAt==0L -> "BOOT_FGS_CREATION_NOT_OBSERVED"
            serviceWasCreatedBeforeBroadcast && runtimeObservedAt==0L ->
                "BOOT_RUNTIME_START_NOT_OBSERVED_AFTER_BROADCAST"
            !workerLive && startupHeartbeatPending -> "BOOT_WORKER_HEARTBEAT_PENDING_AFTER_VERIFIED_START"
            !workerLive -> "BOOT_NATIVE_WORKER_NOT_PROVEN_LIVE"
            !signedAfterBoot -> "BOOT_SIGNED_RECONCILIATION_NOT_VERIFIED"
            else -> "BOOT_MONITOR_AND_SIGNED_RECONCILIATION_OBSERVED"
        }
        return JSONObject()
            .put("status",statusCode)
            .put("boot_at_ms",bootAt)
            .put("opt_in_verified_at_ms",optedInAt)
            .put("fgs_start_requested_at_ms",requestedAt)
            .put("fgs_created_at_ms",serviceAt)
            .put("fgs_created_before_boot_broadcast",serviceWasCreatedBeforeBroadcast)
            .put("signed_user_stream_sync_at_ms",signedSyncAt)
            .put("worker_live_now",workerLive)
            .put("worker_startup_observed_at_ms",runtimeObservedAt)
            .put("worker_startup_observed_pending_heartbeat",startupHeartbeatPending)
            .put("signed_sync_after_boot",signedAfterBoot)
            .put("reboot_observed",bootAt>0L)
            .put("recovery_technical_proven",statusCode=="BOOT_MONITOR_AND_SIGNED_RECONCILIATION_OBSERVED")
            .put("continuous_24h_proven",false)
            .put("notes","Evidence can expire from the 32-event ring; this is not proof of continuous background operation or authorization for new positions.")
    }
}
