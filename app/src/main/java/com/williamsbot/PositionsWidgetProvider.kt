package com.williamsbot

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class PositionsWidgetProvider : AppWidgetProvider() {
    companion object {
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, PositionsWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(component)
            if (ids.isNotEmpty()) updateAsync(context.applicationContext, manager, ids)
        }

        private fun updateAsync(context: Context, manager: AppWidgetManager, ids: IntArray) {
            Thread {
                val status = runCatching {
                    val c = URL("http://127.0.0.1:18080/api/v1/status").openConnection() as HttpURLConnection
                    c.connectTimeout = 2500
                    c.readTimeout = 3500
                    c.requestMethod = "GET"
                    c.inputStream.bufferedReader().use { JSONObject(it.readText()) }
                }.getOrNull()
                val views = RemoteViews(context.packageName, R.layout.widget_positions)
                val open = status?.optJSONArray("positions")
                val risk = status?.optDouble("reserved_risk_pct", 0.0) ?: 0.0
                views.setTextViewText(R.id.widget_risk, String.format(Locale.US, "%.2f%%", risk * 100.0))
                val hasPositions = open != null && open.length() > 0
                views.setTextViewText(R.id.widget_empty, if (hasPositions) "" else "Нет открытых позиций")
                views.setViewVisibility(R.id.widget_positions, if (hasPositions) android.view.View.VISIBLE else android.view.View.GONE)
                val rowIds = intArrayOf(R.id.widget_pos1, R.id.widget_pos2, R.id.widget_pos3)
                for (i in rowIds.indices) {
                    val p = if (open != null && i < open.length()) open.optJSONObject(i) else null
                    val text = if (p != null) {
                        val symbol = p.optString("symbol", "").removeSuffix("USDT")
                        val entry = p.optDouble("entry", 0.0)
                        val stop = p.optDouble("stop", 0.0)
                        val take = p.optDouble("take", 0.0)
                        symbol + "  E " + price(entry) + "  SL " + price(stop) + "  TP " + price(take)
                    } else ""
                    views.setTextViewText(rowIds[i], text)
                    views.setViewVisibility(rowIds[i], if (text.isBlank()) android.view.View.GONE else android.view.View.VISIBLE)
                }
                val intent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                views.setOnClickPendingIntent(
                    R.id.widget_title,
                    PendingIntent.getActivity(context, 41414, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                )
                manager.updateAppWidget(ids, views)
            }.start()
        }

        private fun price(v: Double): String =
            if (v <= 0.0) "—" else String.format(Locale.US, "%.4f", v)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refresh(context)
    override fun onEnabled(context: Context) = refresh(context)
}
