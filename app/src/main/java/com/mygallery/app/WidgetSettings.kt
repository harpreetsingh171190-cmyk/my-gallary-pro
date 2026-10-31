package com.mygallery.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.os.SystemClock
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * Which photos the home-screen widget shows: all photos, favorites, or one custom album
 * ("album:<id>"). Stored in the "settings" preferences.
 */
object WidgetSettings {
    const val ALL = "all"
    const val FAVORITES = "favorites"

    fun source(context: Context): String =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("widget_source", ALL) ?: ALL

    fun set(context: Context, value: String) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("widget_source", value).apply()
        refresh(context)
    }

    /** Minutes between automatic widget refreshes. Allowed: 30, 60, 120 or 360. */
    val INTERVALS = intArrayOf(30, 60, 120, 360)

    fun intervalMinutes(context: Context): Int =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).getInt("widget_minutes", 60)

    fun setInterval(context: Context, minutes: Int) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putInt("widget_minutes", minutes).apply()
        scheduleTicks(context)
    }

    /** Schedules repeated refreshes. Called when the widget is added and when the interval changes. */
    fun scheduleTicks(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = PendingIntent.getBroadcast(
            context, 0,
            Intent(context, MemoryWidgetProvider::class.java).setAction(MemoryWidgetProvider.TICK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val period = intervalMinutes(context) * 60_000L
        alarms.setInexactRepeating(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + period, period, pending)
    }

    fun cancelTicks(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = PendingIntent.getBroadcast(
            context, 0,
            Intent(context, MemoryWidgetProvider::class.java).setAction(MemoryWidgetProvider.TICK),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        if (pending != null) alarms.cancel(pending)
    }

    fun favoriteUris(context: Context): Set<String> =
        context.getSharedPreferences("favorites", Context.MODE_PRIVATE).getStringSet("uris", emptySet()) ?: emptySet()

    /** Asks the widget to redraw now, instead of waiting for its hourly update. */
    fun refresh(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, MemoryWidgetProvider::class.java))
        if (ids.isNotEmpty()) {
            context.sendBroadcast(
                Intent(context, MemoryWidgetProvider::class.java)
                    .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids),
            )
        }
    }
}
