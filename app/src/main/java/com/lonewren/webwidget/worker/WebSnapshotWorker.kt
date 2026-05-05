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
 *  2. Asks [WebSnapshotRenderer] for the full-page tall bitmap.
 *  3. Slices it into widget-sized tiles and writes them through
 *     [SnapshotCache].
 *  4. Tells the launcher to reload the widget's ListView via
 *     [AppWidgetManager.notifyAppWidgetViewDataChanged].
 *
 * On failure: a single error tile is written and the same notify is fired,
 * so the widget shows the placeholder as one ListView row.
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

        // Always rebuild the adapter RemoteViews — the empty view text changes
        // between loading / error states and the launcher won't refresh the
        // text on its own.
        val rv = WidgetRemoteViewsBuilder.adapter(
            context = applicationContext,
            appWidgetId = widgetId,
            targetUrl = config.url,
            emptyText = applicationContext.getString(R.string.widget_loading),
        )
        appWidgetManager.updateAppWidget(widgetId, rv)

        val outcome = renderer.renderFullPage(
            url = config.url,
            targetWidthPx = size.widthPx,
        )

        when (outcome) {
            is WebSnapshotRenderer.Result.Success -> {
                val tiles = withContext(Dispatchers.Default) {
                    sliceIntoTiles(outcome.bitmap, tileHeightPx = size.heightPx)
                }
                withContext(Dispatchers.IO) { cache.writeTiles(widgetId, tiles) }

                // notifyAppWidgetViewDataChanged is the trigger that makes
                // the launcher call our factory's onDataSetChanged. Without
                // this call the new tiles stay invisible.
                appWidgetManager.notifyAppWidgetViewDataChanged(widgetId, R.id.widget_list)

                outcome.bitmap.recycle()
                tiles.forEach { it.recycle() }
                return Result.success()
            }
            is WebSnapshotRenderer.Result.Failure -> {
                Log.w(
                    TAG,
                    "snapshot failed for widget=$widgetId url=${config.url}: ${outcome.reason}",
                )
                val errorTile = WidgetRemoteViewsBuilder.renderErrorTile(
                    context = applicationContext,
                    widthPx = size.widthPx,
                    heightPx = size.heightPx,
                    lastAttemptEpochMillis = now,
                )
                withContext(Dispatchers.IO) { cache.writeSingleTile(widgetId, errorTile) }
                appWidgetManager.notifyAppWidgetViewDataChanged(widgetId, R.id.widget_list)
                errorTile.recycle()
                // retry() so WorkManager retries on the next periodic tick
                // without dropping the schedule.
                return Result.retry()
            }
        }
    }

    /**
     * Splits a tall bitmap into N rows of [tileHeightPx] each, plus a final
     * shorter tile for the remainder. Each tile is a copy (createBitmap +
     * source rect) so the source bitmap can be recycled afterwards.
     */
    private fun sliceIntoTiles(source: Bitmap, tileHeightPx: Int): List<Bitmap> {
        val safeTileHeight = tileHeightPx.coerceAtLeast(MIN_TILE_HEIGHT_PX)
        val width = source.width
        val total = source.height
        if (total <= safeTileHeight) {
            // The whole page fits in one tile. Avoid an unnecessary copy.
            return listOf(source.copy(Bitmap.Config.ARGB_8888, false))
        }
        val tiles = mutableListOf<Bitmap>()
        var y = 0
        while (y < total) {
            val rowHeight = (total - y).coerceAtMost(safeTileHeight)
            tiles += Bitmap.createBitmap(source, 0, y, width, rowHeight)
            y += rowHeight
        }
        return tiles
    }

    companion object {
        const val KEY_WIDGET_ID = "appWidgetId"
        private const val TAG = "WebSnapshotWorker"
        // Defends against zero / negative widget heights from launchers that
        // haven't reported OPTIONS yet.
        private const val MIN_TILE_HEIGHT_PX = 120
    }
}
