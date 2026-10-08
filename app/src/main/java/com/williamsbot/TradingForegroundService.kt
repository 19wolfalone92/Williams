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
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Williams Trader")
                .setContentText(
                    "Binance Testnet: торговый двигатель активен"
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

        // The authoritative Futures trading engine is backend-only.
        // This service intentionally does not start the legacy native Spot runtime.
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        when (intent?.action) {
            ACTION_START -> {
                // ACTION_START keeps only the Android foreground service alive.
                // Trading remains exclusively on the remote Futures backend.
            }

            ACTION_STOP -> {
                // Trading is stopped through the backend control API.
                stopSelf()
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        // Do not clear auto-run state here. A user-driven STOP explicitly
        // calls stopTrading() before stopSelf(); unexpected destruction
        // should leave the engine state available for recreation.
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
