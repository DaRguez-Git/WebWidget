package com.lonewren.webwidget.data

/**
 * Per-widget user configuration. Keyed by appWidgetId in the DataStore.
 *
 * [lastAttemptEpochMillis] is null until the worker has run at least once.
 * It's persisted (not just kept in memory) so that the placeholder bitmap can
 * show "last attempt: HH:mm" even if the process was killed in between.
 */
data class WidgetConfig(
    val appWidgetId: Int,
    val url: String,
    val interval: RefreshInterval,
    val lastAttemptEpochMillis: Long? = null,
)
