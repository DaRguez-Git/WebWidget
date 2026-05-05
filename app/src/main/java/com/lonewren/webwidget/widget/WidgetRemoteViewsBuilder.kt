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
 * RemoteViews has a tiny API surface: only the methods explicitly tagged with
 * @RemotableViewMethod can be called across processes. That's why we lean on
 * setImageViewBitmap / setTextViewText / setOnClickPendingIntent and avoid
 * anything fancy.
 */
object WidgetRemoteViewsBuilder {

    fun success(
        context: Context,
        appWidgetId: Int,
        snapshot: Bitmap,
        targetUrl: String,
    ): RemoteViews = RemoteViews(context.packageName, R.layout.widget_web).apply {
        setImageViewBitmap(R.id.widget_image, snapshot)
        setViewVisibility(R.id.widget_error_text, View.GONE)
        setOnClickPendingIntent(R.id.widget_root, openUrlPendingIntent(context, appWidgetId, targetUrl))
    }

    fun error(
        context: Context,
        appWidgetId: Int,
        widthPx: Int,
        heightPx: Int,
        targetUrl: String?,
        lastAttemptEpochMillis: Long,
    ): RemoteViews {
        // Render a placeholder bitmap with the error message + last attempt
        // timestamp baked in. We bake it instead of overlaying a TextView so
        // the layout stays a single ImageView and we don't have to juggle
        // RemoteViews font sizing across launchers.
        val placeholder = renderErrorBitmap(
            context = context,
            widthPx = widthPx,
            heightPx = heightPx,
            lastAttemptEpochMillis = lastAttemptEpochMillis,
        )
        return RemoteViews(context.packageName, R.layout.widget_web).apply {
            setImageViewBitmap(R.id.widget_image, placeholder)
            setViewVisibility(R.id.widget_error_text, View.GONE)
            if (targetUrl != null) {
                setOnClickPendingIntent(
                    R.id.widget_root,
                    openUrlPendingIntent(context, appWidgetId, targetUrl),
                )
            }
        }
    }

    /**
     * Initial layout shown while the very first snapshot is still rendering.
     * Avoids a brief blank square right after the widget is added.
     */
    fun loading(context: Context): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_web).apply {
            setViewVisibility(R.id.widget_error_text, View.VISIBLE)
            setTextViewText(R.id.widget_error_text, context.getString(R.string.widget_loading))
        }

    private fun openUrlPendingIntent(
        context: Context,
        appWidgetId: Int,
        url: String,
    ): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            // The launcher process starts this activity, so we need NEW_TASK.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // requestCode = appWidgetId so each widget owns a distinct PendingIntent.
        // FLAG_IMMUTABLE is required from API 31; FLAG_UPDATE_CURRENT keeps the
        // PendingIntent reusable when the URL changes.
        return PendingIntent.getActivity(
            context,
            appWidgetId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun renderErrorBitmap(
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

        // Vertically center the two-line stack. Paint.measureText gives width;
        // for vertical placement we use the font ascent/descent of the bigger
        // paint so the visual baseline is balanced.
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
