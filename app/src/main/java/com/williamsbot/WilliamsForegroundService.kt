package com.williamsbot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat

class WilliamsForegroundService : Service() {
    companion object {
        private const val CHANNEL_ID = "williams_trading_runtime"
        private const val NOTIFICATION_ID = 42020
        const val ACTION_START = "com.williamsbot.action.START_RUNTIME"
        const val ACTION_STOP = "com.williamsbot.action.STOP_RUNTIME"
    }

    private lateinit var connectivity: ConnectivityManager
    private var online = true
    private val supervisorHandler = Handler(Looper.getMainLooper())
    private val supervisor = object : Runnable {
        override fun run() {
            // Production APK is a cockpit for the authenticated VPS backend.
            // Binance credentials and the trading engine are never started locally.
            supervisorHandler.postDelayed(this, 60_000L)
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            online = true
            updateNotification()
        }

        override fun onLost(network: Network) {
            online = false
            updateNotification()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        runCatching { connectivity.registerDefaultNetworkCallback(networkCallback) }
        startForeground(NOTIFICATION_ID, buildNotification())
        // The VPS trading backend owns Binance REST/WSS, reconciliation and orders.
        // The Android FGS only keeps the cockpit/network monitor alive.
        supervisorHandler.postDelayed(supervisor, 60_000L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == Companion.ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        supervisorHandler.removeCallbacks(supervisor)
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Williams trading runtime",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Williams autonomous runtime and connectivity status"
                    setShowBadge(false)
                }
            )
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Williams Trader")
            .setContentText(if (online) "Runtime active • network online" else "Runtime active • network offline")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .build()

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification())
    }
}
