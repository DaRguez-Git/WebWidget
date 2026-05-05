package com.lonewren.webwidget.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import com.lonewren.webwidget.R

/**
 * Handles the manual prev/next navigation buttons overlaid on the widget.
 *
 * Why a receiver instead of a trampoline activity?
 *  RemoteViews exposes [RemoteViews.showNext] / [RemoteViews.showPrevious]
 *  as remotable methods on AdapterViewAnimator (the parent class of
 *  StackView). To trigger them, we publish a fresh RemoteViews tree from
 *  this receiver — that's the canonical pattern for nav buttons in
 *  scrollable widgets, and it works around launcher-specific gesture
 *  quirks of StackView.
 */
class WidgetNavigationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val widgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        )
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return

        val rv = RemoteViews(context.packageName, R.layout.widget_web)
        when (intent.action) {
            ACTION_NEXT -> rv.showNext(R.id.widget_stack)
            ACTION_PREV -> rv.showPrevious(R.id.widget_stack)
            else -> return
        }
        AppWidgetManager.getInstance(context).partiallyUpdateAppWidget(widgetId, rv)
    }

    companion object {
        const val ACTION_NEXT = "com.lonewren.webwidget.ACTION_NEXT"
        const val ACTION_PREV = "com.lonewren.webwidget.ACTION_PREV"

        fun nextPendingIntent(context: Context, appWidgetId: Int): PendingIntent =
            buildPendingIntent(context, appWidgetId, ACTION_NEXT)

        fun prevPendingIntent(context: Context, appWidgetId: Int): PendingIntent =
            buildPendingIntent(context, appWidgetId, ACTION_PREV)

        private fun buildPendingIntent(
            context: Context,
            appWidgetId: Int,
            action: String,
        ): PendingIntent {
            val intent = Intent(context, WidgetNavigationReceiver::class.java).apply {
                this.action = action
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                // Distinct data so PendingIntents for different widget /
                // direction combinations don't dedupe to the same instance.
                data = Uri.parse("webwidget-nav://$action/$appWidgetId")
            }
            // requestCode mixes id and direction so each (widget, dir) gets
            // its own PendingIntent slot.
            val requestCode = appWidgetId * 2 + (if (action == ACTION_NEXT) 1 else 0)
            return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
