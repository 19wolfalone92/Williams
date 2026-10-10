package com.williamsbot

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import org.json.JSONObject

object AndroidRuntimeHealth {
    fun snapshot(context: Context): JSONObject {
        val pm = context.getSystemService(PowerManager::class.java)
        val am = context.getSystemService(ActivityManager::class.java)
        val usage = context.getSystemService(UsageStatsManager::class.java)

        val result = JSONObject()
            .put("android_sdk", Build.VERSION.SDK_INT)
            .put("manufacturer", Build.MANUFACTURER)
            .put("device_model", Build.MODEL)

        if (pm != null) {
            result.put(
                "battery_optimization_ignored",
                runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }
                    .getOrDefault(false)
            )
            result.put("power_save_mode", runCatching { pm.isPowerSaveMode }.getOrDefault(true))
            result.put("interactive", runCatching { pm.isInteractive }.getOrDefault(false))
        } else {
            result.put("power_manager_available", false)
        }

        if (am != null) {
            if (Build.VERSION.SDK_INT >= 28) {
                runCatching { am.isBackgroundRestricted }
                    .onSuccess { result.put("background_restricted", it) }
                    .onFailure {
                        result.put("background_restricted", JSONObject.NULL)
                        result.put("background_restriction_error", it.javaClass.simpleName)
                    }
            }

            val serviceRunning = runCatching {
                am.getRunningServices(Int.MAX_VALUE).any {
                    it.service.className == TradingForegroundService::class.java.name &&
                        it.pid == android.os.Process.myPid()
                }
            }
            result.put("foreground_service_running", serviceRunning.getOrDefault(false))
            if (serviceRunning.isFailure) {
                result.put("foreground_service_probe_error", serviceRunning.exceptionOrNull()?.javaClass?.simpleName)
            }
        } else {
            result.put("activity_manager_available", false)
            result.put("foreground_service_running", false)
        }

        if (Build.VERSION.SDK_INT >= 28 && usage != null) {
            runCatching { usage.appStandbyBucket }
                .onSuccess {
                    result.put("standby_bucket", it)
                    result.put("standby_bucket_available", true)
                }
                .onFailure {
                    // Usage Access is optional. A missing user grant must not
                    // break health/diagnostics and must not be reported as bucket 0.
                    result.put("standby_bucket", JSONObject.NULL)
                    result.put("standby_bucket_available", false)
                    result.put("standby_bucket_error", it.javaClass.simpleName)
                }
        } else {
            result.put("standby_bucket", JSONObject.NULL)
            result.put("standby_bucket_available", false)
        }

        return result
    }
}
