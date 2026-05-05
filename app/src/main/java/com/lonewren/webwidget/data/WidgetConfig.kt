package com.lonewren.webwidget.data

/**
 * Per-widget user configuration. Keyed by appWidgetId in the DataStore.
 *
 * [urls] is the list of pages the widget swipes between. Empty list means
 * the user removed every entry; the widget should show the "add URL" card
 * only.
 *
 * [lastAttemptEpochMillis] is null until the worker has run at least once.
 * It's persisted (not just kept in memory) so that the per-URL error tile
 * can show "last attempt: HH:mm" even if the process was killed in between.
 */
data class WidgetConfig(
    val appWidgetId: Int,
    val urls: List<String>,
    val interval: RefreshInterval,
    val lastAttemptEpochMillis: Long? = null,
) {
    companion object {
        // Hard cap. Each URL produces one bitmap roughly the size of the
        // widget; 8 is a generous balance between memory use and how many
        // pages a user is likely to skim through with a swipe gesture.
        const val MAX_URLS = 8
    }
}
