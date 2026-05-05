package com.lonewren.webwidget.worker

import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lonewren.webwidget.R
import com.lonewren.webwidget.di.appContainer
import com.lonewren.webwidget.widget.SnapshotCache
import com.lonewren.webwidget.widget.WidgetRemoteViewsBuilder
import com.lonewren.webwidget.widget.WidgetSizing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Periodic worker that:
 *  1. Reads the per-widget config from DataStore.
 *  2. Renders each configured URL serially through [WebSnapshotRenderer].
 *  3. Writes one PNG per URL through [SnapshotCache]; URLs that failed
 *     get the error placeholder so the user still sees something on the
 *     swipe.
 *  4. Tells the launcher to reload the StackView via
 *     [AppWidgetManager.notifyAppWidgetViewDataChanged].
 *
 * Render is serial (not parallel) because each WebView snapshot uses
 * non-trivial memory; running all N at once would peak at N × ~50 MB
 * and trip the system's low-memory killer on smaller devices.
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
        val cache = SnapshotCache(applicationContext)
        val renderer = WebSnapshotRenderer(applicationContext)
        val appWidgetManager = AppWidgetManager.getInstance(applicationContext)

        // Show "Loading…" while we work. The empty view is only visible
        // while the StackView has zero items, so this only really matters
        // on the first run.
        val rv = WidgetRemoteViewsBuilder.adapter(
            context = applicationContext,
            appWidgetId = widgetId,
            emptyText = applicationContext.getString(R.string.widget_loading),
        )
        appWidgetManager.updateAppWidget(widgetId, rv)

        if (config.urls.isEmpty()) {
            // No URLs configured; the StackView only shows the "+ Add" card.
            // Wipe any leftover snapshots from a previous configuration.
            withContext(Dispatchers.IO) { cache.delete(widgetId) }
            appWidgetManager.notifyAppWidgetViewDataChanged(widgetId, R.id.widget_stack)
            return Result.success()
        }

        var anyFailed = false
        val bitmaps = ArrayList<Bitmap>(config.urls.size)
        for (url in config.urls) {
            val outcome = renderer.render(
                url = url,
                targetWidthPx = size.widthPx,
                targetHeightPx = size.heightPx,
            )
            val bmp = when (outcome) {
                is WebSnapshotRenderer.Result.Success -> outcome.bitmap
                is WebSnapshotRenderer.Result.Failure -> {
                    anyFailed = true
                    Log.w(
                        TAG,
                        "snapshot failed for widget=$widgetId url=$url: ${outcome.reason}",
                    )
                    WidgetRemoteViewsBuilder.renderErrorTile(
                        context = applicationContext,
                        widthPx = size.widthPx,
                        heightPx = size.heightPx,
                        lastAttemptEpochMillis = now,
                    )
                }
            }
            bitmaps += bmp
        }

        withContext(Dispatchers.IO) { cache.writeSnapshots(widgetId, bitmaps) }
        // notifyAppWidgetViewDataChanged tells the launcher to re-call
        // RemoteViewsFactory.onDataSetChanged. Without this the new tiles
        // remain invisible until the launcher rebinds for another reason.
        appWidgetManager.notifyAppWidgetViewDataChanged(widgetId, R.id.widget_stack)

        bitmaps.forEach { it.recycle() }

        // Use retry() so a transient failure on at least one URL re-runs
        // sooner than the next periodic tick. Full success: success().
        return if (anyFailed) Result.retry() else Result.success()
    }

    companion object {
        const val KEY_WIDGET_ID = "appWidgetId"
        private const val TAG = "WebSnapshotWorker"
    }
}
