package com.williamsbot

import android.app.Application
import android.content.Context
import android.os.Process
import android.os.SystemClock
import org.json.JSONObject
import java.lang.Thread.UncaughtExceptionHandler

/**
 * Observe fatal Java/Kotlin crashes without saving stack traces, exception messages,
 * API keys, private URLs or exchange payloads. A crash fingerprint is evidence,
 * NOT a reason to bypass Binance reconciliation or trade-entry safety gates.
 */
internal object AndroidCrashEvidence {
    private const val PREFS = "williams_fatal_crash_evidence"
    @Volatile private var installed = false

    internal data class Fingerprint(val exceptionType: String, val appFrame: String)

    /** Pure extraction: never persist throwable.message or arbitrary stack frames. */
    fun fingerprint(failure: Throwable): Fingerprint {
        var root = failure
        repeat(8) { if (root.cause != null && root.cause !== root) root = root.cause!! }
        val exceptionType = root.javaClass.simpleName
            .filter { it.isLetterOrDigit() || it == '_' }.take(64)
            .ifBlank { "UnknownThrowable" }
        val frame = root.stackTrace.firstOrNull { it.className.startsWith("com.williamsbot.") }
        val label = if (frame != null) {
            val clazz = frame.className.removePrefix("com.williamsbot.")
                .filter { it.isLetterOrDigit() || it == '_' || it == '$' || it == '.' }.take(80)
            val method = frame.methodName
                .filter { it.isLetterOrDigit() || it == '_' || it == '$' }.take(60)
            "$clazz.$method:" + frame.lineNumber.coerceAtLeast(0)
        } else "NO_APPLICATION_FRAME"
        return Fingerprint(exceptionType, label.take(150))
    }

    fun install(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            val app = context.applicationContext
            val original = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler(UncaughtExceptionHandler { thread, failure ->
                // Never obstruct the Android default crash handler, even if disk
                // is full or the failure is OutOfMemoryError.
                try { record(app, failure) } catch (_: Throwable) { }
                if (original != null) original.uncaughtException(thread, failure)
                else {
                    Process.killProcess(Process.myPid())
                    kotlin.system.exitProcess(10)
                }
            })
            installed = true
        }
    }

    private fun record(context: Context, failure: Throwable) {
        val safe = fingerprint(failure)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("exception_type", safe.exceptionType)
            .putString("first_app_frame", safe.appFrame)
            .putLong("at_ms", System.currentTimeMillis())
            .putLong("elapsed_ms", SystemClock.elapsedRealtime())
            .commit()
    }

    fun snapshot(context: Context): JSONObject {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return JSONObject()
            .put("capture_kind", "BEST_EFFORT_UNCAUGHT_JAVA_ONLY")
            .put("at_ms", prefs.getLong("at_ms", 0L))
            .put("elapsed_ms", prefs.getLong("elapsed_ms", 0L))
            .put("exception_type", prefs.getString("exception_type", "") ?: "")
            .put("first_app_frame", prefs.getString("first_app_frame", "") ?: "")
            .put("no_raw_exception_or_credentials", true)
    }
}

/** Register before any Activity, background service or Binance runtime is initialized. */
class WilliamsApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AndroidCrashEvidence.install(this)
    }
}
