package com.lonewren.webwidget.config

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Launched by the launcher when the user drops the widget on the home screen
 * (and from the widget's "configure" entry afterwards).
 *
 * Contract enforced by AppWidgetManager:
 *  - We MUST set the result to Activity.RESULT_OK with an intent containing
 *    EXTRA_APPWIDGET_ID; otherwise the launcher tears the widget down.
 *  - We MUST default the activity result to RESULT_CANCELED before any user
 *    interaction so back-press cleans up properly.
 */
class WidgetConfigurationActivity : ComponentActivity() {

    private val viewModel: WidgetConfigurationViewModel by viewModels {
        WidgetConfigurationViewModel.factory()
    }

    private var appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Default to CANCELED. If the user backs out without confirming, the
        // launcher will see this and discard the (still incomplete) widget.
        setResult(Activity.RESULT_CANCELED)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        // If the user is reconfiguring an existing widget, prefill the form.
        viewModel.loadExisting(appWidgetId)

        setContent {
            MaterialTheme {
                val state by viewModel.state.collectAsState()
                WidgetConfigurationScreen(
                    state = state,
                    onUrlChanged = viewModel::onUrlChanged,
                    onIntervalChanged = viewModel::onIntervalChanged,
                    onRequestPreview = { viewModel.onRequestPreview(appWidgetId) },
                    onConfirm = ::confirmAndFinish,
                    onCancel = ::finish,
                )
            }
        }
    }

    private fun confirmAndFinish() {
        lifecycleScope.launch {
            viewModel.persistAndSchedule(appWidgetId)
            val resultIntent = Intent().putExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                appWidgetId,
            )
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
        }
    }
}
