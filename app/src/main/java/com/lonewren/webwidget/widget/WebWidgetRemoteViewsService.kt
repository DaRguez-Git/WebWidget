package com.lonewren.webwidget.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.lonewren.webwidget.R
import com.lonewren.webwidget.data.WidgetConfig
import com.lonewren.webwidget.di.appContainer
import kotlinx.coroutines.runBlocking
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Backs the widget's [android.widget.StackView]. The launcher binds to this
 * service through [RemoteViews.setRemoteAdapter]; for each visible card it
 * calls [RemoteViewsFactory.getViewAt], which we satisfy by either
 * decoding the snapshot PNG or returning the synthetic "+ Add URL" card.
 *
 * The factory lives in our process, but its methods are invoked by the
 * launcher across a Binder. Keep work in [getViewAt] cheap (single PNG
 * decode) and avoid retaining bitmaps after returning the [RemoteViews].
 */
class WebWidgetRemoteViewsService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        val widgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        )
        return CardFactory(applicationContext, widgetId)
    }

    private class CardFactory(
        private val context: Context,
        private val widgetId: Int,
    ) : RemoteViewsFactory {

        // Loaded once per onDataSetChanged so the launcher's getViewAt loop
        // sees a consistent snapshot of disk + DataStore state.
        private var snapshots: List<File> = emptyList()
        private var urls: List<String> = emptyList()

        private val cache = SnapshotCache(context.applicationContext)

        override fun onCreate() = reload()

        override fun onDataSetChanged() = reload()

        private fun reload() {
            snapshots = cache.listSnapshots(widgetId)
            // RemoteViewsFactory methods run on a binder thread, not the main
            // thread, so blocking on a quick DataStore read is acceptable
            // here. The reads are millisecond-scale.
            urls = runBlocking {
                context.appContainer.widgetPreferences.get(widgetId)?.urls.orEmpty()
            }
        }

        override fun onDestroy() {
            snapshots = emptyList()
            urls = emptyList()
        }

        // N URL cards plus one trailing "+ Add URL" card. The add card is
        // hidden when the user already has [WidgetConfig.MAX_URLS] entries
        // (no more can be added anyway).
        //
        // Special case: when URLs are configured but no snapshots have
        // landed yet, returning 0 forces the empty view ("Loading…") to
        // show. Without this branch the user would see only the "+ Add"
        // card while the worker is still rendering, which is confusing —
        // it looks like the widget is empty when it's actually busy.
        override fun getCount(): Int {
            if (urls.isNotEmpty() && snapshots.isEmpty()) return 0
            val urlCount = snapshots.size
            val addCard = if (urls.size < WidgetConfig.MAX_URLS) 1 else 0
            return urlCount + addCard
        }

        override fun getViewAt(position: Int): RemoteViews {
            return if (position < snapshots.size) {
                buildUrlCard(position)
            } else {
                buildAddCard()
            }
        }

        private fun buildUrlCard(position: Int): RemoteViews {
            val file = snapshots[position]
            val bmp = BitmapFactory.decodeFile(file.absolutePath)
            val rv = RemoteViews(context.packageName, R.layout.widget_card)
            if (bmp != null) {
                rv.setImageViewBitmap(R.id.card_image, bmp)
            }
            // "Last updated: HH:mm" text per card. We use the file's
            // mtime so we don't need a parallel data structure of
            // per-URL timestamps — every writeSnapshot bumps it for free.
            val timestampText = context.getString(
                R.string.widget_card_last_updated,
                TIMESTAMP_FORMAT.format(Date(file.lastModified())),
            )
            rv.setTextViewText(R.id.card_timestamp, timestampText)

            // Per-card click: open the URL. The pending-intent template is
            // set on the StackView; we only contribute the URL here.
            val url = urls.getOrNull(position)
            if (url != null) {
                val fillIn = Intent().apply {
                    putExtra(WidgetActionActivity.EXTRA_URL, url)
                }
                rv.setOnClickFillInIntent(R.id.card_root, fillIn)
            }
            return rv
        }

        private fun buildAddCard(): RemoteViews {
            val rv = RemoteViews(context.packageName, R.layout.widget_add_card)
            val fillIn = Intent().apply {
                putExtra(WidgetActionActivity.EXTRA_OPEN_CONFIG, true)
            }
            rv.setOnClickFillInIntent(R.id.card_root, fillIn)
            return rv
        }

        // Returning null lets the launcher show its default loading
        // indicator instead of a custom blank card.
        override fun getLoadingView(): RemoteViews? = null

        // Two distinct layouts (URL card and add card).
        override fun getViewTypeCount(): Int = 2

        override fun getItemId(position: Int): Long = position.toLong()

        override fun hasStableIds(): Boolean = true

        companion object {
            // Locale.getDefault for the user's region; time-only because the
            // pill is small and a date wouldn't add information for the
            // "minutes-ago" use case.
            private val TIMESTAMP_FORMAT = SimpleDateFormat("HH:mm", Locale.getDefault())
        }
    }
}
