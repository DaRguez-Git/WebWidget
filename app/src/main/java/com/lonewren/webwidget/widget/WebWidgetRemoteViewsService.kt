package com.lonewren.webwidget.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.BitmapFactory
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.lonewren.webwidget.R
import java.io.File

/**
 * Backs the widget's [android.widget.ListView]. The launcher binds to this
 * service through [RemoteViews.setRemoteAdapter]; for each visible row it
 * calls [RemoteViewsFactory.getViewAt], which we satisfy by decoding one tile
 * PNG from disk.
 *
 * Constraints:
 *  - Lives in our process but its methods are invoked by the launcher across
 *    a Binder. Keep work in [getViewAt] cheap (single PNG decode).
 *  - We must NOT keep references to bitmaps after returning the [RemoteViews]
 *    — the launcher serializes them across the binder and any retained
 *    reference is just memory we hold for nothing.
 */
class WebWidgetRemoteViewsService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        val widgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        )
        return TileFactory(applicationContext, widgetId)
    }

    private class TileFactory(
        private val context: android.content.Context,
        private val widgetId: Int,
    ) : RemoteViewsFactory {

        // Tile filenames are loaded once on each onDataSetChanged so the
        // launcher's getViewAt loop sees a consistent snapshot of disk.
        private var tiles: List<File> = emptyList()
        private val cache = SnapshotCache(context.applicationContext)

        override fun onCreate() = reload()

        override fun onDataSetChanged() = reload()

        private fun reload() {
            tiles = cache.listTiles(widgetId)
        }

        override fun onDestroy() {
            tiles = emptyList()
        }

        override fun getCount(): Int = tiles.size

        override fun getViewAt(position: Int): RemoteViews {
            val file = tiles.getOrNull(position) ?: return loadingView
            // Decode every call: ListView discards rows aggressively and our
            // tiles are small (~widgetWidth × widgetHeight). Holding them in
            // memory across the factory lifetime would balloon RSS for no
            // perceivable scroll-perf gain.
            val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return loadingView
            return RemoteViews(context.packageName, R.layout.widget_tile).apply {
                setImageViewBitmap(R.id.tile_image, bmp)
                // Per-row click intent: the template is set on the ListView
                // by WidgetRemoteViewsBuilder.adapter(); this fill-in just
                // tells the launcher to fire it for taps on this tile.
                setOnClickFillInIntent(R.id.tile_image, Intent())
            }
        }

        // Returning null here lets the launcher show a default loading
        // indicator instead of a custom blank row.
        override fun getLoadingView(): RemoteViews? = null

        override fun getViewTypeCount(): Int = 1

        override fun getItemId(position: Int): Long = position.toLong()

        override fun hasStableIds(): Boolean = true

        private val loadingView: RemoteViews
            get() = RemoteViews(context.packageName, R.layout.widget_tile)
    }
}
