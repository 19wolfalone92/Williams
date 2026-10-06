package com.williamsbot

import android.content.Context
import android.os.PowerManager
import org.json.JSONObject

object AndroidRuntimeHealth {
    fun snapshot(context: Context): JSONObject {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val ignoring = if (android.os.Build.VERSION.SDK_INT >= 23) {
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } else true
        return JSONObject()
            .put("battery_optimization_ignored", ignoring)
            .put("power_save_mode", pm.isPowerSaveMode)
    }
}
