package com.williamsbot

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.app.Notification
import androidx.core.app.ServiceCompat
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import android.os.PowerManager
import android.os.SystemClock

class TradingForegroundService : Service() {
    @Volatile private var destroyed=false
    @Volatile private var stopping=false
    private val starting=AtomicBoolean(false)
    private val staleRecoveryQueued=AtomicBoolean(false)
    @Volatile private var nextStaleRecoveryElapsedMs=0L
    @Volatile private var startElapsed=0L
    @Volatile private var nextAttemptElapsed=0L
    @Volatile private var initializationRetryNeeded=false
    private var failures=0
    private var wakeLock:PowerManager.WakeLock?=null
    private val leaseCallback:()->Unit={ updateWakeLease() }
    private val guard=Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task,"williams-service-guard").apply { isDaemon=true }
    }
    private val lifecycleWorker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "williams-service-lifecycle").apply { isDaemon = true }
    }

    companion object {
        const val ACTION_START = "com.williamsbot.action.START"
        const val ACTION_STOP = "com.williamsbot.action.STOP"
        private const val CHANNEL_ID = "williams_trading"
        private const val NOTIFICATION_ID = 41014
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()

        val openIntent =
            Intent(this, MainActivity::class.java).apply {
                flags =
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

        val contentIntent =
            PendingIntent.getActivity(
                this,
                41014,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
            )

        val notification =
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Торговый бот Williams")
                .setContentText(
                    "Фоновое сопровождение Binance Futures Demo на этом устройстве"
                )
                .setOngoing(true)
                .setCategory(
                    Notification.CATEGORY_SERVICE
                )
                .setContentIntent(contentIntent)
                .setPriority(
                    Notification.PRIORITY_LOW
                )
                .build()

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= 34) {
                android.content.pm.ServiceInfo
                    .FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
        )
        RuntimeServiceState.active = true
        RuntimeServiceState.monitorHeartbeatStale=false
        RuntimeServiceState.lastWorkerLoopElapsedMs=0L
        RuntimeServiceState.confirmedAtMs = System.currentTimeMillis()
        RuntimeServiceState.refreshLease=leaseCallback
        // This is an actual FGS creation, unlike repeated onStartCommand() calls.
        // Record it separately so diagnostics never mistake duplicate STARTs
        // for Android killing and restarting the process.
        lifecycleWorker.execute {
            RuntimeContinuityEvidence.event(applicationContext,"SERVICE_FOREGROUND_CREATED")
        }
        guard.scheduleWithFixedDelay({
            runCatching {
                RuntimeServiceState.lastGuardElapsedMs=SystemClock.elapsedRealtime()
                updateWakeLease()
                val snapshot=StandaloneRuntime.backgroundSnapshot()
                val now=SystemClock.elapsedRealtime()
                RuntimeServiceState.lastWorkerLoopElapsedMs=snapshot.lastWorkerLoopElapsedMs
                val stale=BackgroundRuntimePolicy.monitorStale(snapshot,now)
                if(RuntimeServiceState.monitorHeartbeatStale!=stale) {
                    RuntimeServiceState.monitorHeartbeatStale=stale
                    RuntimeContinuityEvidence.event(applicationContext,
                        if(stale) "WORKER_HEARTBEAT_STALE" else "WORKER_HEARTBEAT_RECOVERED")
                    if (!stale && RuntimeServiceState.lastError?.startsWith("STALE_WORKER_")==true)
                        RuntimeServiceState.lastError=null
                }
                // A stale worker blocks new orders. Once it is proven idle from
                // financial mutations, request a cooperative exit on the
                // lifecycle executor; NEVER start a parallel worker.
                if (stale && !destroyed && !stopping &&
                    now>=nextStaleRecoveryElapsedMs &&
                    staleRecoveryQueued.compareAndSet(false,true)) {
                    nextStaleRecoveryElapsedMs=now+120_000L
                    try {
                        lifecycleWorker.execute {
                            try {
                                val result=StandaloneRuntime.requestStalledWorkerRecovery()
                                val event=when(result) {
                                    "COOPERATIVE_INTERRUPT_SENT" -> "STALE_WORKER_INTERRUPT_REQUESTED"
                                    "DEFERRED_EXCHANGE_SAFETY_WORK" -> "STALE_WORKER_RECOVERY_DEFERRED"
                                    else -> "STALE_WORKER_RECOVERY_NOT_READY"
                                }
                                RuntimeContinuityEvidence.event(applicationContext,event)
                                if (result=="DEFERRED_EXCHANGE_SAFETY_WORK" ||
                                    result=="RECONCILE_FENCE_PERSIST_FAILED")
                                    RuntimeServiceState.lastError="STALE_WORKER_"+result
                            } catch(error:Exception) {
                                RuntimeStartupEvidence.failure(applicationContext,
                                    "STALE_WORKER_RECOVERY_FAILED",error)
                                RuntimeServiceState.lastError=
                                    "STALE_WORKER_RECOVERY_FAILED:"+error.javaClass.simpleName
                            } finally {
                                staleRecoveryQueued.set(false)
                            }
                        }
                    } catch(error:java.util.concurrent.RejectedExecutionException) {
                        staleRecoveryQueued.set(false)
                    }
                }
                // A live but wedged worker must not be started a second time.
                // Existing exchange protection/monitor reconciliation remain
                // independent of the new-entry liveness fence.
                if(!destroyed && !stopping && (BackgroundRuntimePolicy.shouldRetry(snapshot,starting.get(),nextAttemptElapsed,now) ||
                    snapshot.autoRunRequested && initializationRetryNeeded && !starting.get() && now>=nextAttemptElapsed))
                    requestRuntimeStart()
            }.onFailure { RuntimeServiceState.lastError="SERVICE_GUARD_FAILED: "+it.javaClass.simpleName }
        },0L,BackgroundRuntimePolicy.CHECK_MS,TimeUnit.MILLISECONDS)
        // Do not clear a BOOT_SECURE_PREFS_UNREADABLE/BOOT_FGS_START_BLOCKED
        // failure yet: foreground-service creation proves nothing about
        // Keystore access or successful NativeEngine reconciliation.
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        val action = intent?.action
        if(action!=ACTION_STOP) {
            // CRITICAL cold-start invariant: StandaloneRuntime.server is null
            // after process death/reboot. Its nonblocking snapshot deliberately
            // returns autoRunRequested=false WITHOUT accessing encrypted disk.
            // It CANNOT decide whether the user previously opted in.
            //
            // A LIVE worker is already owned by this process. Repeated UI
            // ACTION_START requests are not recovery events. Do not call
            // NativeEngine.start()/autostart() again or race Binance reconciliation.
            // A cold/default snapshot is NOT trusted as an opt-out: it MUST
            // still enter the encrypted auto-run bootstrap on the worker thread.
            val current=StandaloneRuntime.backgroundSnapshot()
            if(!BackgroundRuntimePolicy.alreadySupervising(current)) requestRuntimeStart()
            // An alive-but-stalled worker is deliberately not duplicated.
            // Its independent heartbeat/execution gates still report the fault.
            return START_STICKY
        }
        stopping=true
        // Keystore, SQLite and authenticated reconciliation may block. Never do them
        // on Android's main/service thread, including after START_STICKY recreation.
        lifecycleWorker.execute {
            if (action == ACTION_STOP) {
                runCatching {
                    if (StandaloneRuntime.stop()) stopSelf()
                    else stopping=false
                }.onFailure {
                    stopping=false
                    android.util.Log.e("WilliamsFutures", "Safe shutdown was refused; monitoring retained", it)
                }
            }
        }

        return START_STICKY
    }

    private fun requestRuntimeStart() {
        if(destroyed || stopping || !starting.compareAndSet(false,true)) return
        startElapsed=SystemClock.elapsedRealtime()
        RuntimeServiceState.lastRecoveryAttemptMs=System.currentTimeMillis()
        updateWakeLease()
        try { lifecycleWorker.execute {
            try {
                RuntimeContinuityEvidence.refreshProcessExits(applicationContext)
                RuntimeContinuityEvidence.event(applicationContext,"SERVICE_START_ATTEMPT")
                runCatching {
                    StandaloneRuntime.start(applicationContext)
                    StandaloneRuntime.autostart(applicationContext)
                    val verified=StandaloneRuntime.backgroundSnapshot()
                    // Running Android FGS is NOT a running Futures monitor:
                    // on opted-in boot, require proof of a live native worker.
                    check(BackgroundRuntimePolicy.autostartComplete(verified)) {
                        "OPTED_IN_MONITOR_NOT_RUNNING"
                    }
                }.onSuccess {
                    // Foreground notification is not proof the trading engine
                    // actually started. Clear only prior service startup errors.
                    RuntimeServiceState.lastError = null
                    initializationRetryNeeded=false
                    failures=0
                    nextAttemptElapsed=SystemClock.elapsedRealtime()+BackgroundRuntimePolicy.CHECK_MS
                    RuntimeContinuityEvidence.event(applicationContext,"SERVICE_RUNTIME_OBSERVED")
                }.onFailure {
                    // Previously swallowed: UI showed a running Android service
                    // while Binance engine had never started after reboot.
                    RuntimeStartupEvidence.failure(applicationContext, "ENGINE_AUTOSTART_FAILED", it)
                    RuntimeServiceState.lastError = safeDiagnosticText(
                        "Futures runtime autostart failed: " + (it.message ?: it.javaClass.simpleName)
                    )
                    android.util.Log.e("WilliamsFutures", "Local Futures runtime startup failed", it)
                    RuntimeContinuityEvidence.event(applicationContext,"SERVICE_AUTOSTART_FAILED",it)
                    failures++
                    initializationRetryNeeded=true
                    nextAttemptElapsed=SystemClock.elapsedRealtime()+BackgroundRuntimePolicy.retryDelay(failures)
                }
            } finally {
                starting.set(false)
                updateWakeLease()
            }
        } } catch(failure:java.util.concurrent.RejectedExecutionException) { starting.set(false);updateWakeLease() }
    }

    @Synchronized private fun updateWakeLease() {
        val keep=!destroyed && BackgroundRuntimePolicy.keepCpu(StandaloneRuntime.backgroundSnapshot(),starting.get(),startElapsed,SystemClock.elapsedRealtime())
        try {
            if(keep) {
                val lock=wakeLock ?: (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,"Williams:Monitoring").apply { setReferenceCounted(false);wakeLock=this }
                // Renew a bounded lease only while monitoring is requested.
                // A dead service/guard cannot leak a permanent CPU lock.
                lock.acquire(BackgroundRuntimePolicy.WAKE_TIMEOUT_MS)
            } else if(wakeLock?.isHeld==true) wakeLock?.release()
            RuntimeServiceState.wakeLockHeld=wakeLock?.isHeld==true
        } catch(failure:RuntimeException) {
            RuntimeServiceState.wakeLockHeld=false
            RuntimeServiceState.lastError="CPU_WAKE_LEASE_FAILED: "+failure.javaClass.simpleName
        }
    }

    override fun onTaskRemoved(rootIntent:Intent?) {
        // Never block Android's onTaskRemoved main callback on encrypted
        // preferences or a synchronous SharedPreferences audit commit.
        runCatching { lifecycleWorker.execute {
            val snapshot=StandaloneRuntime.backgroundSnapshot()
            if(!starting.get() && !snapshot.autoRunRequested && !(snapshot.running && snapshot.workerAlive)) {
                RuntimeContinuityEvidence.event(applicationContext,"TASK_REMOVED_IDLE_SERVICE_STOPPED")
                stopSelf()
            } else {
                RuntimeContinuityEvidence.event(applicationContext,"TASK_REMOVED_MONITOR_RETAINED")
            }
        } }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // Do not clear auto_run on unexpected teardown. START_STICKY recreation
        // re-enters autostart(), which reconciles the exchange state first.
        RuntimeServiceState.active = false
        RuntimeServiceState.monitorHeartbeatStale=false
        RuntimeServiceState.lastWorkerLoopElapsedMs=0L
        destroyed=true
        if(RuntimeServiceState.refreshLease===leaseCallback) RuntimeServiceState.refreshLease=null
        guard.shutdownNow()
        updateWakeLease()
        runCatching { lifecycleWorker.execute { RuntimeContinuityEvidence.event(applicationContext,"SERVICE_DESTROYED") } }
        lifecycleWorker.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager =
                getSystemService(
                    NotificationManager::class.java
                )
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Торговый движок Williams",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description =
                        "Состояние торгового двигателя Williams"
                }
            )
        }
    }
}
