package com.williamsbot

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/** Fixed categories/types only: never retain throwable messages, URLs or credentials. */
internal object RuntimeContinuityEvidence {
    private const val PREFS="williams_continuity_health"
    @Volatile private var exits="[]"
    @Volatile private var exitsStatus="NOT_READ"
    @Volatile private var observedAt=0L
    @Synchronized fun event(context:Context,code:String,cause:Throwable?=null) {
        runCatching {
            val prefs=context.applicationContext.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
            val old=runCatching { JSONArray(prefs.getString("events","[]")) }.getOrDefault(JSONArray())
            val rows=JSONArray()
            for(i in maxOf(0,old.length()-31) until old.length()) rows.put(old.get(i))
            rows.put(JSONObject().put("code",code.filter { it.isUpperCase() || it=='_' }.take(64))
                .put("type",cause?.javaClass?.simpleName.orEmpty().take(64))
                .put("at_ms",System.currentTimeMillis()).put("elapsed_ms",SystemClock.elapsedRealtime()))
            prefs.edit().putString("events",rows.toString()).commit()
        }
    }
    fun refreshProcessExits(context:Context) {
        if(Build.VERSION.SDK_INT<30) { exitsStatus="UNSUPPORTED_API";return }
        runCatching {
            val manager=context.getSystemService(ActivityManager::class.java)
            val rows=JSONArray()
            for(info in manager.getHistoricalProcessExitReasons(context.packageName,0,8)) {
                rows.put(JSONObject().put("at_ms",info.timestamp).put("reason_code",info.reason)
                    .put("reason",BackgroundRuntimePolicy.exitLabel(info.reason)).put("status",info.status)
                    .put("importance",info.importance).put("pss_kb",info.pss).put("rss_kb",info.rss))
            }
            exits=rows.toString();observedAt=System.currentTimeMillis();exitsStatus="OBSERVED_SYSTEM_HISTORY"
        }.onFailure { exitsStatus="READ_FAILED_"+it.javaClass.simpleName.take(48) }
    }
    fun snapshot(context:Context):JSONObject {
        val rows=runCatching { JSONArray(context.applicationContext.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
            .getString("events","[]")) }.getOrDefault(JSONArray())
        return JSONObject().put("events",rows).put("process_exits",JSONArray(exits)).put("process_exits_status",exitsStatus)
            .put("process_exits_observed_at_ms",observedAt).put("physical_background_acceptance","NOT_VERIFIED")
    }
}
