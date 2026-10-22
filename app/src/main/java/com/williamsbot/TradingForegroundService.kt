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

class TradingForegroundService : Service() {
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
        RuntimeServiceState.confirmedAtMs = System.currentTimeMillis()
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
        // Keystore, SQLite and authenticated reconciliation may block. Never do them
        // on Android's main/service thread, including after START_STICKY recreation.
        lifecycleWorker.execute {
            if (action == ACTION_STOP) {
                runCatching {
                    StandaloneRuntime.stopTrading()
                    if (StandaloneRuntime.stop()) stopSelf()
                }.onFailure {
                    android.util.Log.e("WilliamsFutures", "Safe shutdown was refused; monitoring retained", it)
                }
            } else {
                runCatching {
                    StandaloneRuntime.start(applicationContext)
                    StandaloneRuntime.autostart(applicationContext)
                }.onSuccess {
                    // Foreground notification is not proof the trading engine
                    // actually started. Clear only prior service startup errors.
                    RuntimeServiceState.lastError = null
                }.onFailure {
                    // Previously swallowed: UI showed a running Android service
                    // while Binance engine had never started after reboot.
                    RuntimeStartupEvidence.failure(applicationContext, "ENGINE_AUTOSTART_FAILED", it)
                    RuntimeServiceState.lastError = safeDiagnosticText(
                        "Futures runtime autostart failed: " + (it.message ?: it.javaClass.simpleName)
                    )
                    android.util.Log.e("WilliamsFutures", "Local Futures runtime startup failed", it)
                }
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        // Do not clear auto_run on unexpected teardown. START_STICKY recreation
        // re-enters autostart(), which reconciles the exchange state first.
        RuntimeServiceState.active = false
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
