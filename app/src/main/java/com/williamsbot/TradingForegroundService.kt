package com.williamsbot

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

class TradingForegroundService : Service() {
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
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Williams Trader")
                .setContentText(
                    "Autonomous USDⓈ-M Futures Testnet runtime on this device"
                )
                .setOngoing(true)
                .setCategory(
                    NotificationCompat.CATEGORY_SERVICE
                )
                .setContentIntent(contentIntent)
                .setPriority(
                    NotificationCompat.PRIORITY_LOW
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

    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                runCatching { StandaloneRuntime.stopTrading() }
                StandaloneRuntime.stop()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // Also runs after a sticky service recreation with a null intent.
                // Autostart is safe because persisted state is reconciled with Binance
                // before the scanner can submit any new order.
                runCatching {
                    StandaloneRuntime.start(applicationContext)
                    StandaloneRuntime.autostart(applicationContext)
                }.onFailure {
                    android.util.Log.e("WilliamsFutures", "Local Futures runtime startup failed", it)
                }
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        // Do not clear auto_run on unexpected teardown. START_STICKY recreation
        // re-enters autostart(), which reconciles the exchange state first.
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
                    "Williams trading",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description =
                        "Состояние торгового двигателя Williams"
                }
            )
        }
    }
}
