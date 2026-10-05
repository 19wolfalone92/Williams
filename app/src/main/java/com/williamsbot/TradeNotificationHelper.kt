package com.williamsbot

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray

object TradeNotificationHelper {
    private const val CHANNEL_ID = "williams_trades"
    private const val PREFS = "williams_trade_notifications"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Williams сделки", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "Открытие и закрытие сделок Williams" }
            )
        }
    }

    fun notify(context: Context, title: String, text: String, id: Int) {
        ensureChannel(context)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(com.williamsbot.R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(id, notification)
    }

    fun processTradeList(context: Context, trades: JSONArray) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val initialized = prefs.getBoolean("initialized", false)
        val seen = prefs.getStringSet("seen", emptySet())!!.toMutableSet()

        for (i in 0 until trades.length()) {
            val t = trades.optJSONObject(i) ?: continue
            val id = t.optInt("id", i)
            val entryTime = t.optString("entry_time")
            val exitTime = t.optString("exit_time")
            val isClosed = exitTime.isNotBlank() && exitTime != "null"

            if (!initialized) {
                if (!isClosed) seen.add("open:" + id)
                seen.add("closed:" + id)
                continue
            }

            if (entryTime.isNotBlank() && seen.add("open:" + id)) {
                val text = t.optString("symbol") + " " + t.optString("side", "LONG") +
                    " • entry " + t.optDouble("entry_price", 0.0)
                notify(context, "Williams: сделка открыта", text, 10000 + id)
            }

            if (isClosed && seen.add("closed:" + id)) {
                val pnl = t.optDouble("pnl", 0.0)
                val result = if (pnl >= 0.0) "прибыль" else "убыток"
                val text = t.optString("symbol") + " • " + result + " " +
                    "%.4f".format(java.util.Locale.US, pnl) + " • " +
                    t.optString("reason", "")
                notify(context, "Williams: сделка закрыта", text, 20000 + id)
            }
        }

        prefs.edit().putBoolean("initialized", true).putStringSet("seen", seen).apply()
    }
}
