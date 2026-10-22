package com.williamsbot

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.core.content.edit
import org.json.JSONObject

internal object RuntimeServiceState {
    @Volatile var active = false
    @Volatile var confirmedAtMs = 0L
    @Volatile var lastError: String? = null
}

/**
 * Boot receiver, foreground service and NativeEngine may all run before the
 * cockpit Activity exists. Record only a fixed error category and exception
 * TYPE in private unencrypted preferences; never persist keys, exception
 * messages, headers, request URLs, Binance payloads or user identifiers.
 */
internal object RuntimeStartupEvidence {
    private const val PREFS = "williams_startup_health"
    fun failure(context: Context, code: String, cause: Throwable?) {
        val safeCode = code.take(64).filter { it.isUpperCase() || it == '_' }
        runCatching {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit(commit = true) {
                    putString("last_failure_code", safeCode)
                    putString("last_failure_type", cause?.javaClass?.simpleName.orEmpty().take(64))
                    putLong("last_failure_at_ms", System.currentTimeMillis())
                }
        }
    }
    fun clear(context: Context) {
        runCatching {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit(commit = true) {
                    remove("last_failure_code")
                    remove("last_failure_type")
                    remove("last_failure_at_ms")
                }
        }
    }
    fun snapshot(context: Context): JSONObject {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return JSONObject()
            .put("code", p.getString("last_failure_code", "") ?: "")
            .put("exception_type", p.getString("last_failure_type", "") ?: "")
            .put("at_ms", p.getLong("last_failure_at_ms", 0L))
    }
}

object AndroidRuntimeHealth {
    fun snapshot(context: Context): JSONObject {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val usage = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

        val ignoring = pm.isIgnoringBatteryOptimizations(context.packageName)

        val result = JSONObject()
            .put("application_id", context.packageName)
            .put("app_version", BuildConfig.VERSION_NAME)
            .put("credential_storage_policy", "ANDROID_KEYSTORE_ENCRYPTED_APP_PRIVATE_NO_BACKUP")
            .put("foreground_service_active", RuntimeServiceState.active)
            .put("foreground_service_confirmed_at_ms", RuntimeServiceState.confirmedAtMs)
            .put("foreground_service_error", RuntimeServiceState.lastError ?: JSONObject.NULL)
            .put("last_boot_startup_failure", RuntimeStartupEvidence.snapshot(context))
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
