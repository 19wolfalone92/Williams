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
        val serviceAt=if(requestedAt>0L) latestAfter("SERVICE_FOREGROUND_CREATED",requestedAt) else 0L
        val signedSyncAt=status.optLong("user_stream_last_successful_sync_ms",0L)
        val heartbeatAge=android.optLong("runtime_worker_heartbeat_age_ms",Long.MAX_VALUE)
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
            .put("signed_user_stream_sync_at_ms",signedSyncAt)
            .put("worker_live_now",workerLive)
            .put("signed_sync_after_boot",signedAfterBoot)
            .put("reboot_observed",bootAt>0L)
            .put("recovery_technical_proven",statusCode=="BOOT_MONITOR_AND_SIGNED_RECONCILIATION_OBSERVED")
            .put("continuous_24h_proven",false)
            .put("notes","Evidence can expire from the 32-event ring; this is not proof of continuous background operation or authorization for new positions.")
    }
}
