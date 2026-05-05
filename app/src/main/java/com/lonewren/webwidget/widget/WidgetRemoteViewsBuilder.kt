package com.lonewren.webwidget.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import com.lonewren.webwidget.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

/**
 * Builds the [RemoteViews] tree pushed to the launcher for a given widget.
 *
 * The widget is a ListView whose rows come from [WebWidgetRemoteViewsService].
 * This builder is responsible for:
 *  - Wiring the ListView to the service via setRemoteAdapter (every refresh
 *    must pass a fresh Intent so the launcher invalidates its row cache).
 *  - Setting the empty view text (loading / error).
 *  - Setting the click pending intent template — the per-row fill-in is
 *    set inside the factory.
 */
object WidgetRemoteViewsBuilder {

    /**
     * Builds the adapter-backed RemoteViews. Call [Companion.notifyDataChanged]
     * after every snapshot write so the launcher reloads tiles.
     *
     * @param emptyText shown by the launcher when the ListView reports zero
     *                  rows (i.e. before the first snapshot lands or while
     *                  the cache is being rewritten).
     */
    fun adapter(
        context: Context,
        appWidgetId: Int,
        targetUrl: String?,
        emptyText: String,
    ): RemoteViews = RemoteViews(context.packageName, R.layout.widget_web).apply {
        val adapterIntent = Intent(context, WebWidgetRemoteViewsService::class.java).apply {
            putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            // Distinct data URI per appWidgetId is essential: the launcher
            // dedupes adapter intents by their `Intent.filterEquals`, which
            // ignores extras. Without unique data the launcher would reuse
            // the factory of another widget.
            data = Uri.parse("webwidget://$appWidgetId")
        }
        setRemoteAdapter(R.id.widget_list, adapterIntent)
        setEmptyView(R.id.widget_list, R.id.widget_empty)
        setTextViewText(R.id.widget_empty, emptyText)
        setViewVisibility(R.id.widget_empty, View.VISIBLE)

        if (targetUrl != null) {
            setPendingIntentTemplate(
                R.id.widget_list,
                openUrlPendingIntent(context, appWidgetId, targetUrl),
            )
            // Tap on the empty view (loading/error state) also opens the URL,
            // so the user has a way to recover with the same gesture.
            setOnClickPendingIntent(
                R.id.widget_empty,
                openUrlPendingIntent(context, appWidgetId, targetUrl),
            )
        }
    }

    /**
     * Pre-renders a placeholder bitmap with the localized error title and
     * the timestamp of the last attempt. We bake into a bitmap rather than
     * relying on RemoteViews TextView styling because RemoteViews has poor
     * text controls at small widget sizes.
     */
    fun renderErrorTile(
        context: Context,
        widthPx: Int,
        heightPx: Int,
        lastAttemptEpochMillis: Long,
    ): Bitmap {
        val safeWidth = widthPx.coerceAtLeast(64)
        val safeHeight = heightPx.coerceAtLeast(64)
        val bmp = Bitmap.createBitmap(safeWidth, safeHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.parseColor("#FFF5F5F5"))

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFB00020")
            textSize = (min(safeWidth, safeHeight) * 0.10f).coerceIn(28f, 56f)
            isFakeBoldText = true
        }
        val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF555555")
            textSize = titlePaint.textSize * 0.55f
        }

        val title = context.getString(R.string.widget_error_title)
        val subtitle = context.getString(
            R.string.widget_error_last_attempt,
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(lastAttemptEpochMillis)),
        )

        val titleBounds = Rect().also { titlePaint.getTextBounds(title, 0, title.length, it) }
        val subtitleBounds = Rect().also {
            subtitlePaint.getTextBounds(subtitle, 0, subtitle.length, it)
        }
        val totalHeight = titleBounds.height() + subtitleBounds.height() + (titlePaint.textSize * 0.5f)
        val titleY = (safeHeight - totalHeight) / 2f + titleBounds.height()
        val subtitleY = titleY + subtitleBounds.height() + (titlePaint.textSize * 0.5f)

        canvas.drawText(
            title,
            (safeWidth - titlePaint.measureText(title)) / 2f,
            titleY,
            titlePaint,
        )
        canvas.drawText(
            subtitle,
            (safeWidth - subtitlePaint.measureText(subtitle)) / 2f,
            subtitleY,
            subtitlePaint,
        )
        return bmp
    }

    private fun openUrlPendingIntent(
        context: Context,
        appWidgetId: Int,
        url: String,
    ): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return PendingIntent.getActivity(
            context,
            appWidgetId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
