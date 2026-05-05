package com.lonewren.webwidget.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.widgetDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "web_widgets",
)

/**
 * Persistent store for per-widget config. Keys are namespaced by appWidgetId
 * (e.g. `widget.42.urls`) so that the same DataStore file can hold every
 * widget the user has placed without us managing a list manually.
 *
 * URLs are serialized as a single newline-delimited string. We avoid
 * stringSetPreferencesKey because Set semantics drop ordering, and the
 * order of the URL list IS user-visible (it's the order of the cards
 * in the StackView).
 */
class WidgetPreferences(private val appContext: Context) {

    private val store: DataStore<Preferences> get() = appContext.widgetDataStore

    private fun urlsKey(id: Int) = stringPreferencesKey("widget.$id.urls")
    private fun intervalKey(id: Int) = stringPreferencesKey("widget.$id.interval")
    private fun lastAttemptKey(id: Int) = longPreferencesKey("widget.$id.last_attempt")

    fun observe(appWidgetId: Int): Flow<WidgetConfig?> =
        store.data.map { prefs -> readConfig(prefs, appWidgetId) }

    suspend fun get(appWidgetId: Int): WidgetConfig? =
        readConfig(store.data.first(), appWidgetId)

    suspend fun save(config: WidgetConfig) {
        store.edit { prefs ->
            prefs[urlsKey(config.appWidgetId)] = encodeUrls(config.urls)
            prefs[intervalKey(config.appWidgetId)] = config.interval.name
            // We do NOT overwrite lastAttempt here — only the worker writes
            // it, so saving from the configuration UI doesn't accidentally
            // erase the timestamp shown on the per-URL error placeholder.
        }
    }

    suspend fun setLastAttempt(appWidgetId: Int, epochMillis: Long) {
        store.edit { prefs -> prefs[lastAttemptKey(appWidgetId)] = epochMillis }
    }

    suspend fun remove(appWidgetId: Int) {
        store.edit { prefs ->
            prefs.remove(urlsKey(appWidgetId))
            prefs.remove(intervalKey(appWidgetId))
            prefs.remove(lastAttemptKey(appWidgetId))
        }
    }

    private fun readConfig(prefs: Preferences, appWidgetId: Int): WidgetConfig? {
        val rawUrls = prefs[urlsKey(appWidgetId)] ?: return null
        val urls = decodeUrls(rawUrls)
        val interval = RefreshInterval.fromName(prefs[intervalKey(appWidgetId)])
        val lastAttempt = prefs[lastAttemptKey(appWidgetId)]
        return WidgetConfig(appWidgetId, urls, interval, lastAttempt)
    }

    // \n-delimited; URLs cannot legally contain a literal newline, so this
    // is unambiguous without needing JSON or a binary delimiter.
    private fun encodeUrls(urls: List<String>): String =
        urls.joinToString(separator = "\n")

    private fun decodeUrls(raw: String): List<String> =
        if (raw.isEmpty()) emptyList() else raw.split('\n')
}
