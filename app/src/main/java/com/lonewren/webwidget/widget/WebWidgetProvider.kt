package com.lonewren.webwidget.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle
import com.lonewren.webwidget.di.appContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Receives lifecycle callbacks for our widget. The actual rendering is done
 * by [com.lonewren.webwidget.worker.WebSnapshotWorker]; this class is mostly
 * about wiring lifecycle events to the WorkManager queue and to DataStore
 * cleanup.
 *
 * AppWidgetProvider extends BroadcastReceiver, which means each callback runs
 * on the main thread with a strict ~10s wall-clock budget before the system
 * kills us. We use goAsync() to extend that budget for the DataStore reads
 * and WorkManager enqueueing.
 */
class WebWidgetProvider : AppWidgetProvider() {

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val container = context.appContainer
        val cache = SnapshotCache(context.applicationContext)

        // Paint a loading placeholder *immediately*, synchronously. This
        // covers the cold-rebuild case (after reboot) where the widget bound
        // to the launcher would otherwise show a blank background until the
        // first worker run completes.
        for (id in appWidgetIds) {
            appWidgetManager.updateAppWidget(id, WidgetRemoteViewsBuilder.loading(context))
        }

        // goAsync extends the receiver wall-clock budget (~30s) so the
        // DataStore read and WorkManager enqueue complete before the system
        // can kill our process.
        val pending = goAsync()
        ioScope.launch {
            try {
                for (id in appWidgetIds) {
                    val cfg = container.widgetPreferences.get(id) ?: continue
                    val cached = cache.read(id)
                    if (cached != null) {
                        // Re-render with the real click target now that we
                        // know the URL — avoids tapping into about:blank.
                        val rv = WidgetRemoteViewsBuilder.success(
                            context = context,
                            appWidgetId = id,
                            snapshot = cached,
                            targetUrl = cfg.url,
                        )
                        appWidgetManager.updateAppWidget(id, rv)
                    }
                    // Re-arm WorkManager. This is idempotent thanks to
                    // unique-work names; we re-enqueue here in case the
                    // schedule was wiped (backup/restore, app data clear).
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
        // the snapshot is regenerated at the new dimensions instead of
        // showing a stretched cached bitmap.
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
