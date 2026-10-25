package com.williamsbot

/** Liveness only. These observations never grant an exchange mutation. */
internal data class BackgroundRuntimeSnapshot(val autoRunRequested:Boolean=false,val running:Boolean=false,
    val workerAlive:Boolean=false,val generation:Long=0L,
    val lastWorkerLoopElapsedMs:Long=0L)

internal object BackgroundRuntimePolicy {
    const val CHECK_MS=30_000L
    const val WAKE_TIMEOUT_MS=120_000L
    const val STARTUP_LEASE_MS=300_000L
    const val MONITOR_STALE_MS=180_000L
    fun keepCpu(snapshot:BackgroundRuntimeSnapshot,startInFlight:Boolean,startAt:Long,now:Long):Boolean =
        snapshot.autoRunRequested || snapshot.running && snapshot.workerAlive ||
            startInFlight && startAt>=0 && now>=startAt && now-startAt<STARTUP_LEASE_MS
    // Detect an alive worker whose processing loop stopped advancing.
    // Its existence forbids a duplicate runtime start; new entry admission
    // is separately fenced while protective exchange orders remain intact.
    fun monitorStale(snapshot:BackgroundRuntimeSnapshot,now:Long):Boolean =
        snapshot.running && snapshot.workerAlive &&
            snapshot.lastWorkerLoopElapsedMs>0L &&
            now>=snapshot.lastWorkerLoopElapsedMs &&
            now-snapshot.lastWorkerLoopElapsedMs>=MONITOR_STALE_MS
    // Nonblocking cold-start snapshots cannot read encrypted opt-in until
    // the service's lifecycle worker initializes the secure runtime.
    // Once it does, a claimed successful auto-start must have a LIVE worker.
    fun autostartComplete(snapshot:BackgroundRuntimeSnapshot):Boolean =
        !snapshot.autoRunRequested || (snapshot.running && snapshot.workerAlive)
    fun shouldRetry(snapshot:BackgroundRuntimeSnapshot,inFlight:Boolean,nextAttemptAt:Long,now:Long):Boolean =
        snapshot.autoRunRequested && (!snapshot.running || !snapshot.workerAlive) && !inFlight && now>=nextAttemptAt
    fun retryDelay(failures:Int):Long=when {
        failures<=1 -> 60_000L
        failures==2 -> 120_000L
        failures==3 -> 300_000L
        else -> 900_000L
    }
    fun exitLabel(reason:Int):String=when(reason) {
        1 -> "EXIT_SELF"
        2 -> "SIGNALED"
        3 -> "LOW_MEMORY"
        4 -> "CRASH"
        5 -> "CRASH_NATIVE"
        6 -> "ANR"
        7 -> "INITIALIZATION_FAILURE"
        8 -> "PERMISSION_CHANGE"
        9 -> "EXCESSIVE_RESOURCE_USAGE"
        10 -> "USER_REQUESTED"
        11 -> "USER_STOPPED"
        12 -> "DEPENDENCY_DIED"
        13 -> "OTHER"
        14 -> "FREEZER"
        15 -> "PACKAGE_STATE_CHANGE"
        16 -> "PACKAGE_UPDATED"
        else -> "UNKNOWN"
    }
}
