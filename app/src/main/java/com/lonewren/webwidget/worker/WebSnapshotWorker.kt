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
import com.lonewren.webwidget.widget.WidgetSize
import com.lonewren.webwidget.widget.WidgetSizing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Worker that materializes the snapshot bitmaps consumed by
 * [com.lonewren.webwidget.widget.WebWidgetRemoteViewsService].
 *
 * Two modes, selected by the [KEY_URL_INDEX] input:
 *  - **All-URLs mode** (default, no input or index < 0): renders every
 *    URL serially and rewrites the cache folder. Triggered by the
 *    periodic schedule and the one-shot kick after configuration.
 *  - **Single-URL mode** (`KEY_URL_INDEX >= 0`): renders only that one
 *    URL and replaces just its tile, leaving the rest untouched.
 *    Triggered by the manual-refresh button on the visible card.
 *
 * Render is serial in all-URLs mode because each WebView snapshot uses
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
        val targetIndex = inputData.getInt(KEY_URL_INDEX, ALL_URLS)

        val container = applicationContext.appContainer
        val config = container.widgetPreferences.get(widgetId)
            ?: return Result.success() // Widget was removed mid-flight.

        val now = System.currentTimeMillis()
        container.widgetPreferences.setLastAttempt(widgetId, now)

        val size = WidgetSizing.measure(applicationContext, widgetId)
        val cache = SnapshotCache(applicationContext)
        val appWidgetManager = AppWidgetManager.getInstance(applicationContext)

        if (config.urls.isEmpty()) {
            // No URLs configured; the StackView only shows the "+ Add" card.
            withContext(Dispatchers.IO) { cache.delete(widgetId) }
            appWidgetManager.notifyAppWidgetViewDataChanged(widgetId, R.id.widget_stack)
            return Result.success()
        }

        return if (targetIndex >= 0 && targetIndex < config.urls.size) {
            renderSingle(
                widgetId = widgetId,
                index = targetIndex,
                url = config.urls[targetIndex],
                size = size,
                cache = cache,
                appWidgetManager = appWidgetManager,
                attemptEpochMillis = now,
            )
        } else {
            renderAll(
                widgetId = widgetId,
                urls = config.urls,
                size = size,
                cache = cache,
                appWidgetManager = appWidgetManager,
                attemptEpochMillis = now,
            )
        }
    }

    private suspend fun renderAll(
        widgetId: Int,
        urls: List<String>,
        size: WidgetSize,
        cache: SnapshotCache,
        appWidgetManager: AppWidgetManager,
        attemptEpochMillis: Long,
    ): Result {
        // Bind the adapter so the empty view ("Loading…") shows on the
        // first run while we render.
        appWidgetManager.updateAppWidget(
            widgetId,
            WidgetRemoteViewsBuilder.adapter(
                context = applicationContext,
                appWidgetId = widgetId,
                emptyText = applicationContext.getString(R.string.widget_loading),
            ),
        )

        var anyFailed = false
        val bitmaps = ArrayList<Bitmap>(urls.size)
        for (url in urls) {
            val bmp = renderOrPlaceholder(url, size, attemptEpochMillis) { failed ->
                if (failed) anyFailed = true
            }
            bitmaps += bmp
        }

        withContext(Dispatchers.IO) { cache.writeSnapshots(widgetId, bitmaps) }
        appWidgetManager.notifyAppWidgetViewDataChanged(widgetId, R.id.widget_stack)
        bitmaps.forEach { it.recycle() }

        return if (anyFailed) Result.retry() else Result.success()
    }

    private suspend fun renderSingle(
        widgetId: Int,
        index: Int,
        url: String,
        size: WidgetSize,
        cache: SnapshotCache,
        appWidgetManager: AppWidgetManager,
        attemptEpochMillis: Long,
    ): Result {
        var failed = false
        val bmp = renderOrPlaceholder(url, size, attemptEpochMillis) { f ->
            if (f) failed = true
        }
        withContext(Dispatchers.IO) { cache.writeSnapshot(widgetId, index, bmp) }
        appWidgetManager.notifyAppWidgetViewDataChanged(widgetId, R.id.widget_stack)
        bmp.recycle()
        return if (failed) Result.retry() else Result.success()
    }

    private suspend fun renderOrPlaceholder(
        url: String,
        size: WidgetSize,
        attemptEpochMillis: Long,
        onFailureFlag: (Boolean) -> Unit,
    ): Bitmap {
        val renderer = WebSnapshotRenderer(applicationContext)
        return when (val outcome = renderer.render(
            url = url,
            targetWidthPx = size.widthPx,
            targetHeightPx = size.heightPx,
        )) {
            is WebSnapshotRenderer.Result.Success -> outcome.bitmap
            is WebSnapshotRenderer.Result.Failure -> {
                onFailureFlag(true)
                Log.w(TAG, "snapshot failed for url=$url: ${outcome.reason}")
                WidgetRemoteViewsBuilder.renderErrorTile(
                    context = applicationContext,
                    widthPx = size.widthPx,
                    heightPx = size.heightPx,
                    lastAttemptEpochMillis = attemptEpochMillis,
                )
            }
        }
    }

    companion object {
        const val KEY_WIDGET_ID = "appWidgetId"
        /** Optional input. >= 0: render only that URL. < 0 / absent: all. */
        const val KEY_URL_INDEX = "urlIndex"
        const val ALL_URLS = -1
        private const val TAG = "WebSnapshotWorker"
    }
}
