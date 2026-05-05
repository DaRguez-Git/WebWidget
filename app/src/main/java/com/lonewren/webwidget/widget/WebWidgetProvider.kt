package com.lonewren.webwidget.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle
import com.lonewren.webwidget.R
import com.lonewren.webwidget.di.appContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Receives lifecycle callbacks for our widget. The actual rendering is done
 * by [com.lonewren.webwidget.worker.WebSnapshotWorker]; this class wires
 * lifecycle events to the WorkManager queue, paints a transient adapter
 * RemoteViews so the empty view shows up immediately, and handles cleanup.
 */
class WebWidgetProvider : AppWidgetProvider() {

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val container = context.appContainer

        // Paint the adapter RemoteViews immediately. Even if the worker
        // hasn't run yet, the launcher will bind to our factory and show
        // the empty view ("Loading…") instead of a blank cell.
        for (id in appWidgetIds) {
            val rv = WidgetRemoteViewsBuilder.adapter(
                context = context,
                appWidgetId = id,
                targetUrl = null,
                emptyText = context.getString(R.string.widget_loading),
            )
            appWidgetManager.updateAppWidget(id, rv)
        }

        // goAsync extends the receiver wall-clock budget (~30s) so the
        // DataStore read and WorkManager enqueue complete before the system
        // can kill our process.
        val pending = goAsync()
        ioScope.launch {
            try {
                for (id in appWidgetIds) {
                    val cfg = container.widgetPreferences.get(id) ?: continue
                    // Now that we know the URL, rebuild the RemoteViews so
                    // taps on the empty view (and on tiles, once they
                    // arrive) open the right page.
                    val rv = WidgetRemoteViewsBuilder.adapter(
                        context = context,
                        appWidgetId = id,
                        targetUrl = cfg.url,
                        emptyText = context.getString(R.string.widget_loading),
                    )
                    appWidgetManager.updateAppWidget(id, rv)
                    // Idempotent re-arm: in case WorkManager was wiped (app
                    // data clear, backup/restore) we re-enqueue the periodic
                    // and the immediate one-shot.
                    WidgetScheduler.schedule(context, id, cfg.interval)
                }
            } finally {
                pending.finish()
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?,
    ) {
        // The user resized the widget. Re-enqueue an immediate one-shot so
        // the snapshot is regenerated at the new tile dimensions instead of
        // showing stretched cached tiles.
        val container = context.appContainer
        val pending = goAsync()
        ioScope.launch {
            try {
                val cfg = container.widgetPreferences.get(appWidgetId) ?: return@launch
                WidgetScheduler.schedule(context, appWidgetId, cfg.interval)
            } finally {
                pending.finish()
            }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val cache = SnapshotCache(context.applicationContext)
        val prefs = context.appContainer.widgetPreferences
        val pending = goAsync()
        ioScope.launch {
            try {
                for (id in appWidgetIds) {
                    WidgetScheduler.cancel(context, id)
                    cache.delete(id)
                    prefs.remove(id)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
