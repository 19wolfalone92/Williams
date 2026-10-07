package com.williamsbot

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import org.json.JSONObject

object AndroidRuntimeHealth {
    fun snapshot(context: Context): JSONObject {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val usage = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

        val ignoring = pm.isIgnoringBatteryOptimizations(context.packageName)

        val result = JSONObject()
            .put("battery_optimization_ignored", ignoring)
            .put("power_save_mode", pm.isPowerSaveMode)
            .put("interactive", pm.isInteractive)

        if (Build.VERSION.SDK_INT >= 28) {
            result.put("background_restricted", am.isBackgroundRestricted)
        }

        if (Build.VERSION.SDK_INT >= 28) {
            result.put("standby_bucket", usage.appStandbyBucket)
        }

        return result
    }
}
