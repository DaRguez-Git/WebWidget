package com.lonewren.webwidget.config

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lonewren.webwidget.WebWidgetApplication
import com.lonewren.webwidget.data.RefreshInterval
import com.lonewren.webwidget.data.WidgetConfig
import com.lonewren.webwidget.di.AppContainer
import com.lonewren.webwidget.widget.WidgetScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Backs [WidgetConfigurationActivity]. Exposes a single immutable [UiState]
 * that the Compose tree renders, plus event handlers that mutate it.
 *
 * The state holds the URL *list* the user is currently editing (one entry
 * per row in the form). [save] persists it after stripping empty rows and
 * trimming whitespace.
 */
class WidgetConfigurationViewModel(
    application: Application,
    private val container: AppContainer,
) : AndroidViewModel(application) {

    data class UrlEntry(val value: String, val error: String?)

    data class UiState(
        val urls: List<UrlEntry> = listOf(UrlEntry("", null)),
        val interval: RefreshInterval = RefreshInterval.DEFAULT,
    ) {
        /**
         * The user can save when at least one URL is non-blank and every
         * non-blank URL parses as a valid http(s) URI. Blank rows are
         * dropped at save time, so they don't block confirmation.
         */
        val canConfirm: Boolean
            get() = urls.any { it.value.isNotBlank() } &&
                urls.none { it.value.isNotBlank() && it.error != null }

        val canAddMore: Boolean get() = urls.size < WidgetConfig.MAX_URLS
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun onUrlChanged(index: Int, newValue: String) {
        _state.update { state ->
            val updated = state.urls.toMutableList()
            if (index in updated.indices) {
                updated[index] = UrlEntry(newValue, validateUrl(newValue))
            }
            state.copy(urls = updated)
        }
    }

    fun onAddUrlClicked() {
        _state.update { state ->
            if (!state.canAddMore) state
            else state.copy(urls = state.urls + UrlEntry("", null))
        }
    }

    fun onRemoveUrlClicked(index: Int) {
        _state.update { state ->
            val updated = state.urls.toMutableList()
            if (index !in updated.indices) return@update state
            updated.removeAt(index)
            // Keep at least one row in the form so the UI never collapses
            // to nothing — the user can clear it but we always show a row
            // they can type into.
            if (updated.isEmpty()) updated += UrlEntry("", null)
            state.copy(urls = updated)
        }
    }

    fun onIntervalChanged(interval: RefreshInterval) {
        _state.update { it.copy(interval = interval) }
    }

    /**
     * Persists the configuration and schedules the worker. Suspends until
     * both have completed so the Activity can call setResult with confidence.
     */
    suspend fun persistAndSchedule(appWidgetId: Int) {
        val current = _state.value
        val cleaned = current.urls
            .map { it.value.trim() }
            .filter { it.isNotBlank() }
        container.widgetPreferences.save(
            WidgetConfig(
                appWidgetId = appWidgetId,
                urls = cleaned,
                interval = current.interval,
            ),
        )
        WidgetScheduler.schedule(getApplication(), appWidgetId, current.interval)
    }

    fun loadExisting(appWidgetId: Int) {
        viewModelScope.launch {
            val existing = container.widgetPreferences.get(appWidgetId) ?: return@launch
            _state.update {
                val rows = existing.urls.map { UrlEntry(it, validateUrl(it)) }
                it.copy(
                    // If the user previously saved an empty list, make sure
                    // the form still shows one editable row.
                    urls = rows.ifEmpty { listOf(UrlEntry("", null)) },
                    interval = existing.interval,
                )
            }
        }
    }

    private fun validateUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return "Must start with http:// or https://"
        }
        return runCatching { android.net.Uri.parse(trimmed) }
            .fold(
                onSuccess = { uri -> if (uri.host.isNullOrBlank()) "Missing host" else null },
                onFailure = { "Invalid URL" },
            )
    }

    companion object {
        fun factory(): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as WebWidgetApplication
                WidgetConfigurationViewModel(app, app.container)
            }
        }
    }
}
