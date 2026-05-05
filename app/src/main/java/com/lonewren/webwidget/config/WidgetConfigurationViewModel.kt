package com.lonewren.webwidget.config

import android.app.Application
import android.appwidget.AppWidgetManager
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lonewren.webwidget.WebWidgetApplication
import com.lonewren.webwidget.data.RefreshInterval
import com.lonewren.webwidget.data.WidgetConfig
import com.lonewren.webwidget.di.AppContainer
import com.lonewren.webwidget.widget.WidgetScheduler
import com.lonewren.webwidget.widget.WidgetSizing
import com.lonewren.webwidget.worker.WebSnapshotRenderer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Backs [WidgetConfigurationActivity]. Exposes a single immutable [UiState]
 * that the Compose tree renders, plus event handlers that mutate it.
 *
 * The preview flow lives here (not in the worker) because the user expects
 * synchronous-ish feedback while typing: the worker is for the background
 * cadence. The two paths share [WebSnapshotRenderer].
 */
class WidgetConfigurationViewModel(
    application: Application,
    private val container: AppContainer,
) : AndroidViewModel(application) {

    data class UiState(
        val url: String = "",
        val interval: RefreshInterval = RefreshInterval.DEFAULT,
        val previewBitmap: Bitmap? = null,
        val isPreviewLoading: Boolean = false,
        val previewError: String? = null,
        val urlError: String? = null,
    ) {
        val canConfirm: Boolean get() = urlError == null && url.isNotBlank()
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun onUrlChanged(newUrl: String) {
        _state.update {
            it.copy(
                url = newUrl,
                urlError = validateUrl(newUrl),
                // Invalidate the preview when the URL changes so the user
                // doesn't think a stale snapshot is current.
                previewBitmap = null,
                previewError = null,
            )
        }
    }

    fun onIntervalChanged(interval: RefreshInterval) {
        _state.update { it.copy(interval = interval) }
    }

    fun onRequestPreview(appWidgetId: Int) {
        val current = _state.value
        if (!current.canConfirm) return
        _state.update { it.copy(isPreviewLoading = true, previewError = null) }

        viewModelScope.launch {
            val size = WidgetSizing.measure(getApplication(), appWidgetId)
            val renderer = WebSnapshotRenderer(getApplication<Application>().applicationContext)
            // Using shorter timeout for previews so users aren't stuck looking
            // at a spinner; the periodic worker uses the longer default.
            val outcome = renderer.render(
                url = current.url,
                targetWidthPx = size.widthPx,
                targetHeightPx = size.heightPx,
                timeoutMillis = 12_000L,
            )
            _state.update {
                when (outcome) {
                    is WebSnapshotRenderer.Result.Success -> it.copy(
                        previewBitmap = outcome.bitmap,
                        isPreviewLoading = false,
                        previewError = null,
                    )
                    is WebSnapshotRenderer.Result.Failure -> it.copy(
                        previewBitmap = null,
                        isPreviewLoading = false,
                        previewError = outcome.reason,
                    )
                }
            }
        }
    }

    /**
     * Persists the configuration and schedules the first run. Suspends until
     * both have completed so the Activity can call setResult with confidence.
     */
    suspend fun persistAndSchedule(appWidgetId: Int) {
        val current = _state.value
        container.widgetPreferences.save(
            WidgetConfig(
                appWidgetId = appWidgetId,
                url = current.url.trim(),
                interval = current.interval,
            ),
        )
        WidgetScheduler.schedule(getApplication(), appWidgetId, current.interval)
    }

    fun loadExisting(appWidgetId: Int) {
        viewModelScope.launch {
            val existing = container.widgetPreferences.get(appWidgetId) ?: return@launch
            _state.update {
                it.copy(
                    url = existing.url,
                    interval = existing.interval,
                    urlError = validateUrl(existing.url),
                )
            }
        }
    }

    private fun validateUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null // empty handled by canConfirm
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return "URL must start with http:// or https://"
        }
        // Reject obvious garbage; full RFC validation is overkill for a
        // single-user input field.
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
