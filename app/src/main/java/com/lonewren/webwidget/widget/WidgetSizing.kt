package com.lonewren.webwidget.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.res.Configuration
import kotlin.math.roundToInt

/**
 * Computes the physical pixel size of a widget instance.
 *
 * AppWidgetManager.getAppWidgetOptions reports four values, all in **dp**:
 *   OPTION_APPWIDGET_MIN_WIDTH / MAX_WIDTH
 *   OPTION_APPWIDGET_MIN_HEIGHT / MAX_HEIGHT
 *
 * The "min" pair is the size in the *current* orientation, the "max" pair in
 * the other orientation. Picking based on Configuration.orientation gives the
 * actual dimensions the launcher is showing right now.
 *
 * If the launcher hasn't reported dimensions yet (fresh place, very old
 * launchers), we fall back to a sensible 4x2 cell estimate (~250x110dp).
 */
data class WidgetSize(val widthPx: Int, val heightPx: Int)

object WidgetSizing {

    private const val FALLBACK_WIDTH_DP = 250
    private const val FALLBACK_HEIGHT_DP = 110

    fun measure(context: Context, appWidgetId: Int): WidgetSize {
        val mgr = AppWidgetManager.getInstance(context)
        val opts = mgr.getAppWidgetOptions(appWidgetId)
        val portrait =
            context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT

        val widthDp = if (portrait) {
            opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        } else {
            opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0)
        }.takeIf { it > 0 } ?: FALLBACK_WIDTH_DP

        val heightDp = if (portrait) {
            opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
        } else {
            opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
        }.takeIf { it > 0 } ?: FALLBACK_HEIGHT_DP

        val density = context.resources.displayMetrics.density
        return WidgetSize(
            widthPx = (widthDp * density).roundToInt(),
            heightPx = (heightDp * density).roundToInt(),
        )
    }
}
