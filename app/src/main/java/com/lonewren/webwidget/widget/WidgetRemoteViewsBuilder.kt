package com.lonewren.webwidget.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.widget.RemoteViews
import com.lonewren.webwidget.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

/**
 * Builds the [RemoteViews] tree pushed to the launcher for a given widget.
 *
 * The widget is a StackView whose cards come from
 * [WebWidgetRemoteViewsService]. This builder wires up:
 *  - The remote adapter intent (one per appWidgetId, with a unique data
 *    URI to defeat Intent.filterEquals deduping in the launcher).
 *  - The empty view (loading text).
 *  - The pending-intent template that all card clicks share. The template
 *    targets [WidgetActionActivity] so each card's fill-in extras decide
 *    whether the tap opens a URL or the configuration screen.
 */
object WidgetRemoteViewsBuilder {

    fun adapter(
        context: Context,
        appWidgetId: Int,
        emptyText: String,
    ): RemoteViews = RemoteViews(context.packageName, R.layout.widget_web).apply {
        val adapterIntent = Intent(context, WebWidgetRemoteViewsService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            // Unique data URI per appWidgetId so the launcher does not
            // dedupe two different widget instances onto the same factory.
            data = Uri.parse("webwidget://$appWidgetId")
        }
        setRemoteAdapter(R.id.widget_stack, adapterIntent)
        setEmptyView(R.id.widget_stack, R.id.widget_empty)
        setTextViewText(R.id.widget_empty, emptyText)

        // Click template. Template's component is fixed to the trampoline;
        // each card supplies extras via setOnClickFillInIntent. The fill-in
        // CANNOT change action/component, only data and extras — that's why
        // we route everything through the trampoline.
        //
        // FLAG_MUTABLE is required for setPendingIntentTemplate: the
        // launcher merges the per-card fill-in intent into the template
        // before firing it. An immutable template would prevent that
        // merge. The constant exists from API 31; on older APIs the bit
        // is a no-op (PendingIntents were mutable by default), so it's
        // safe to set unconditionally on minSdk 26.
        val templateIntent = Intent(context, WidgetActionActivity::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            data = Uri.parse("webwidget-action://$appWidgetId")
        }
        val templatePI = PendingIntent.getActivity(
            context,
            appWidgetId,
            templateIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        setPendingIntentTemplate(R.id.widget_stack, templatePI)
    }

    /**
     * Pre-renders a placeholder bitmap for a single failing URL. We bake
     * the text into a bitmap rather than relying on RemoteViews TextView
     * styling because RemoteViews has poor text controls at small widget
     * sizes.
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
}
