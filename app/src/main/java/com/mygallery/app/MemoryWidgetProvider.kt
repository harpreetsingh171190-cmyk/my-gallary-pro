package com.mygallery.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.util.Size
import android.widget.RemoteViews

/**
 * Home-screen widget showing one random photo (videos are skipped). It refreshes about hourly and
 * tapping it opens the app. The photo shown is not behind the app lock, so remove the widget if
 * that matters to you.
 */
class MemoryWidgetProvider : AppWidgetProvider() {
    companion object {
        const val TICK = "com.mygallery.app.WIDGET_TICK"
    }

    override fun onEnabled(context: Context) {
        WidgetSettings.scheduleTicks(context)
    }

    override fun onDisabled(context: Context) {
        WidgetSettings.cancelTicks(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == TICK) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(android.content.ComponentName(context, MemoryWidgetProvider::class.java))
            onUpdate(context, manager, ids)
        } else {
            super.onReceive(context, intent)
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        Thread {
            try {
                val all = runCatching { MediaRepository.load(context) }
                    .getOrDefault(emptyList())
                    .filter { !it.isVideo }
                val source = WidgetSettings.source(context)
                val photos = when {
                    source == WidgetSettings.FAVORITES -> {
                        val favorites = WidgetSettings.favoriteUris(context)
                        all.filter { it.uri.toString() in favorites }
                    }
                    source.startsWith("album:") -> {
                        val folder = "album_" + source.removePrefix("album:")
                        all.filter { it.folder == folder }
                    }
                    else -> all
                }
                val pick = photos.randomOrNull()
                // Keep the bitmap small: RemoteViews has a size limit over IPC.
                val bitmap = pick?.let {
                    runCatching { context.contentResolver.loadThumbnail(it.uri, Size(360, 360), null) }.getOrNull()
                }
                val open = PendingIntent.getActivity(
                    context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
                )
                for (id in appWidgetIds) {
                    val views = RemoteViews(context.packageName, R.layout.widget_memory)
                    if (bitmap != null) views.setImageViewBitmap(R.id.widget_image, bitmap)
                    views.setTextViewText(R.id.widget_caption, pick?.name ?: context.getString(R.string.s_widget_no_photos))
                    views.setOnClickPendingIntent(R.id.widget_root, open)
                    manager.updateAppWidget(id, views)
                }
            } finally {
                pending.finish()
            }
        }.start()
    }
}
