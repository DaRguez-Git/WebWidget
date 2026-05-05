package com.lonewren.webwidget.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import com.lonewren.webwidget.R
import com.lonewren.webwidget.di.appContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Handles taps on the manual prev/next/refresh buttons overlaid on the
 * widget.
 *
 * Why a receiver instead of a trampoline activity?
 *  RemoteViews exposes [RemoteViews.showNext] / [RemoteViews.showPrevious]
 *  as remotable methods on AdapterViewAnimator (the parent class of
 *  StackView). Triggering them is the canonical pattern for nav buttons
 *  in scrollable widgets and works around launcher-specific gesture
 *  quirks. The same receiver also handles the manual refresh action,
 *  which kicks a one-shot WorkManager request for the visible URL.
 */
class WidgetNavigationReceiver : BroadcastReceiver() {

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val widgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        )
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return

        when (intent.action) {
            ACTION_NEXT -> handleNext(context, widgetId)
            ACTION_PREV -> handlePrev(context, widgetId)
            ACTION_REFRESH -> handleRefresh(context, widgetId)
            else -> return
        }
    }

    private fun handleNext(context: Context, widgetId: Int) {
        val rv = RemoteViews(context.packageName, R.layout.widget_web)
        rv.showNext(R.id.widget_stack)
        AppWidgetManager.getInstance(context).partiallyUpdateAppWidget(widgetId, rv)
        bumpCurrentIndex(context, widgetId, +1)
    }

    private fun handlePrev(context: Context, widgetId: Int) {
        val rv = RemoteViews(context.packageName, R.layout.widget_web)
        rv.showPrevious(R.id.widget_stack)
        AppWidgetManager.getInstance(context).partiallyUpdateAppWidget(widgetId, rv)
        bumpCurrentIndex(context, widgetId, -1)
    }

    private fun handleRefresh(context: Context, widgetId: Int) {
        val pending = goAsync()
        ioScope.launch {
            try {
                val prefs = context.appContainer.widgetPreferences
                val cfg = prefs.get(widgetId)
                val urlCount = cfg?.urls?.size ?: 0
                if (urlCount == 0) return@launch

                // The mirrored index is whatever we last recorded. If it's
                // pointing at the synthetic add card or out of range, fall
                // back to refreshing every URL — better than refreshing the
                // wrong one or none at all.
                val current = prefs.getCurrentIndex(widgetId)
                val targetIndex = if (current in 0 until urlCount) {
                    current
                } else {
                    com.lonewren.webwidget.worker.WebSnapshotWorker.ALL_URLS
                }
                WidgetScheduler.refreshNow(context, widgetId, targetIndex)
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * Mirrors the StackView's currently-visible card index. We can only
     * track the buttons we own; the launcher's native swipe gesture (if
     * the user uses it) bypasses this mirror, so the index can be stale.
     * That is acceptable because the buttons are the canonical nav path.
     *
     * The visible "slots" are [0, urls.size]: the URL cards plus the
     * trailing add card. With loopViews=true on the StackView we wrap.
     */
    private fun bumpCurrentIndex(context: Context, widgetId: Int, delta: Int) {
        val pending = goAsync()
        ioScope.launch {
            try {
                val prefs = context.appContainer.widgetPreferences
                val cfg = prefs.get(widgetId) ?: return@launch
                val slots = cfg.urls.size + 1 // +1 for the add card
                if (slots <= 0) return@launch
                val current = prefs.getCurrentIndex(widgetId)
                // Math.floorMod keeps the value non-negative even with delta=-1.
                val next = ((current + delta) % slots + slots) % slots
                prefs.setCurrentIndex(widgetId, next)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_NEXT = "com.lonewren.webwidget.ACTION_NEXT"
        const val ACTION_PREV = "com.lonewren.webwidget.ACTION_PREV"
        const val ACTION_REFRESH = "com.lonewren.webwidget.ACTION_REFRESH"

        fun nextPendingIntent(context: Context, appWidgetId: Int): PendingIntent =
            buildPendingIntent(context, appWidgetId, ACTION_NEXT, slot = 1)

        fun prevPendingIntent(context: Context, appWidgetId: Int): PendingIntent =
            buildPendingIntent(context, appWidgetId, ACTION_PREV, slot = 0)

        fun refreshPendingIntent(context: Context, appWidgetId: Int): PendingIntent =
            buildPendingIntent(context, appWidgetId, ACTION_REFRESH, slot = 2)

        private fun buildPendingIntent(
            context: Context,
            appWidgetId: Int,
            action: String,
            slot: Int,
        ): PendingIntent {
            val intent = Intent(context, WidgetNavigationReceiver::class.java).apply {
                this.action = action
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                // Distinct data so PendingIntents for different widget /
                // action combinations don't collapse to the same instance.
                data = Uri.parse("webwidget-nav://$action/$appWidgetId")
            }
            // Per-widget, per-action requestCode so each combination owns
            // its own PendingIntent slot in the system.
            val requestCode = appWidgetId * 8 + slot
            return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
