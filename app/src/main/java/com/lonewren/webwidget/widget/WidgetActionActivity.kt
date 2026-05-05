package com.lonewren.webwidget.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.lonewren.webwidget.config.WidgetConfigurationActivity

/**
 * Trampoline activity that dispatches widget card taps.
 *
 * Why a trampoline?
 *  RemoteViews cards inside a StackView share a single
 *  [android.app.PendingIntent] template. The fill-in intent supplied per
 *  card can override `data` and add `extras`, but **cannot change the
 *  action or the target component**. We need two distinct outcomes
 *  (open a URL in the browser vs open the configuration activity), so
 *  the template targets this activity which then dispatches based on
 *  extras.
 *
 * Theme.NoDisplay + finish-immediately means the activity never shows a
 * frame; the user perceives the tap as going straight to the browser or
 * the configuration screen.
 */
class WidgetActionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            dispatch(intent)
        } finally {
            finish()
        }
    }

    private fun dispatch(intent: Intent) {
        val widgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        )
        if (intent.getBooleanExtra(EXTRA_OPEN_CONFIG, false)) {
            startActivity(
                Intent(this, WidgetConfigurationActivity::class.java).apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
            return
        }
        val url = intent.getStringExtra(EXTRA_URL) ?: return
        startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }

    companion object {
        const val EXTRA_URL = "com.lonewren.webwidget.extra.URL"
        const val EXTRA_OPEN_CONFIG = "com.lonewren.webwidget.extra.OPEN_CONFIG"
    }
}
