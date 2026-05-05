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

// Top-level delegate is the recommended pattern: the DataStore instance is a
// process-singleton tied to the application context. Creating two of them on
// the same file would corrupt the on-disk preferences, hence the delegate.
private val Context.widgetDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "web_widgets",
)

/**
 * Persistent store for per-widget config. Keys are namespaced by appWidgetId
 * (e.g. `widget.42.url`) so that the same DataStore file can hold every
 * widget the user has placed without us managing a list manually.
 */
class WidgetPreferences(private val appContext: Context) {

    private val store: DataStore<Preferences> get() = appContext.widgetDataStore

    private fun urlKey(id: Int) = stringPreferencesKey("widget.$id.url")
    private fun intervalKey(id: Int) = stringPreferencesKey("widget.$id.interval")
    private fun lastAttemptKey(id: Int) = longPreferencesKey("widget.$id.last_attempt")

    fun observe(appWidgetId: Int): Flow<WidgetConfig?> =
        store.data.map { prefs -> readConfig(prefs, appWidgetId) }

    suspend fun get(appWidgetId: Int): WidgetConfig? =
        readConfig(store.data.first(), appWidgetId)

    suspend fun save(config: WidgetConfig) {
        store.edit { prefs ->
            prefs[urlKey(config.appWidgetId)] = config.url
            prefs[intervalKey(config.appWidgetId)] = config.interval.name
            // We do NOT overwrite lastAttempt here — only the worker writes it,
            // so saving from the configuration UI doesn't accidentally erase
            // the timestamp shown on the error placeholder.
        }
    }

    suspend fun setLastAttempt(appWidgetId: Int, epochMillis: Long) {
        store.edit { prefs -> prefs[lastAttemptKey(appWidgetId)] = epochMillis }
    }

    suspend fun remove(appWidgetId: Int) {
        store.edit { prefs ->
            prefs.remove(urlKey(appWidgetId))
            prefs.remove(intervalKey(appWidgetId))
            prefs.remove(lastAttemptKey(appWidgetId))
        }
    }

    private fun readConfig(prefs: Preferences, appWidgetId: Int): WidgetConfig? {
        val url = prefs[urlKey(appWidgetId)] ?: return null
        val interval = RefreshInterval.fromName(prefs[intervalKey(appWidgetId)])
        val lastAttempt = prefs[lastAttemptKey(appWidgetId)]
        return WidgetConfig(appWidgetId, url, interval, lastAttempt)
    }
}
