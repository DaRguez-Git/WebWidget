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
        // Bind the adapter immediately so the empty view ("Loading…")
        // appears on cold rebuilds (post-reboot, etc.) instead of a blank
        // square. The actual snapshots are produced by the worker that
        // we re-enqueue below.
        for (id in appWidgetIds) {
            val rv = WidgetRemoteViewsBuilder.adapter(
                context = context,
                appWidgetId = id,
                emptyText = context.getString(R.string.widget_loading),
            )
            appWidgetManager.updateAppWidget(id, rv)
        }

        // goAsync extends the receiver wall-clock budget (~30s) so the
        // DataStore reads + WorkManager enqueue complete before the system
        // can kill our process.
        val container = context.appContainer
        val pending = goAsync()
        ioScope.launch {
            try {
                for (id in appWidgetIds) {
                    val cfg = container.widgetPreferences.get(id) ?: continue
                    // Idempotent re-arm in case WorkManager was wiped (app
                    // data clear, backup/restore).
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
        // every URL is re-rendered at the new card dimensions.
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
