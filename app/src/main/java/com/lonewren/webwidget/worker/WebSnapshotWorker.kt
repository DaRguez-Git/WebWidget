package com.lonewren.webwidget.worker

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lonewren.webwidget.di.appContainer
import com.lonewren.webwidget.widget.SnapshotCache
import com.lonewren.webwidget.widget.WidgetRemoteViewsBuilder
import com.lonewren.webwidget.widget.WidgetSizing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Periodic worker that:
 *  1. Reads the per-widget config from DataStore.
 *  2. Asks [WebSnapshotRenderer] to produce a Bitmap for the configured URL.
 *  3. Pushes the result (or an error placeholder) into the launcher via
 *     [AppWidgetManager.updateAppWidget].
 *
 * The actual WebView interaction is in [WebSnapshotRenderer]; this class is
 * the thin wiring layer.
 */
class WebSnapshotWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val widgetId = inputData.getInt(KEY_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            return Result.failure()
        }

        val container = applicationContext.appContainer
        val config = container.widgetPreferences.get(widgetId)
            ?: return Result.success() // Widget was removed mid-flight.

        val now = System.currentTimeMillis()
        container.widgetPreferences.setLastAttempt(widgetId, now)

        val size = WidgetSizing.measure(applicationContext, widgetId)
        val renderer = WebSnapshotRenderer(applicationContext)

        val outcome = renderer.render(
            url = config.url,
            targetWidthPx = size.widthPx,
            targetHeightPx = size.heightPx,
        )

        when (outcome) {
            is WebSnapshotRenderer.Result.Success -> {
                // Persist on disk so the provider can repaint quickly when the
                // launcher rebinds the widget (e.g. after a reboot).
                withContext(Dispatchers.IO) {
                    SnapshotCache(applicationContext).write(widgetId, outcome.bitmap)
                }
                val rv = WidgetRemoteViewsBuilder.success(
                    context = applicationContext,
                    appWidgetId = widgetId,
                    snapshot = outcome.bitmap,
                    targetUrl = config.url,
                )
                AppWidgetManager.getInstance(applicationContext)
                    .updateAppWidget(widgetId, rv)
                return Result.success()
            }
            is WebSnapshotRenderer.Result.Failure -> {
                val rv = WidgetRemoteViewsBuilder.error(
                    context = applicationContext,
                    appWidgetId = widgetId,
                    widthPx = size.widthPx,
                    heightPx = size.heightPx,
                    targetUrl = config.url,
                    lastAttemptEpochMillis = now,
                )
                AppWidgetManager.getInstance(applicationContext)
                    .updateAppWidget(widgetId, rv)
                // Return retry so WorkManager backs off and tries again on
                // the next periodic tick. We deliberately don't return
                // failure(): a transient network issue shouldn't drop the
                // periodic schedule.
                return Result.retry()
            }
        }
    }

    companion object {
        const val KEY_WIDGET_ID = "appWidgetId"
    }
}
