package com.lonewren.webwidget.config

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.lonewren.webwidget.R
import com.lonewren.webwidget.data.RefreshInterval

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WidgetConfigurationScreen(
    state: WidgetConfigurationViewModel.UiState,
    onUrlChanged: (String) -> Unit,
    onIntervalChanged: (RefreshInterval) -> Unit,
    onRequestPreview: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.widget_config_title)) })
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState())
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Capture in a local val so smart-cast survives across the
            // composable lambda below (smart-casts on properties of
            // captured objects don't propagate into closures).
            val urlErrorMessage = state.urlError
            OutlinedTextField(
                value = state.url,
                onValueChange = onUrlChanged,
                label = { Text(stringResource(R.string.widget_config_url_label)) },
                placeholder = { Text(stringResource(R.string.widget_config_url_hint)) },
                singleLine = true,
                isError = urlErrorMessage != null,
                supportingText = if (urlErrorMessage != null) {
                    { Text(urlErrorMessage) }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )

            IntervalChips(
                selected = state.interval,
                onSelect = onIntervalChanged,
            )

            PreviewSection(
                state = state,
                onRequestPreview = onRequestPreview,
            )

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                OutlinedButton(onClick = onCancel) {
                    Text(stringResource(R.string.widget_config_cancel))
                }
                Button(
                    onClick = onConfirm,
                    enabled = state.canConfirm,
                ) {
                    Text(stringResource(R.string.widget_config_confirm))
                }
            }
        }
    }
}

@Composable
private fun IntervalChips(
    selected: RefreshInterval,
    onSelect: (RefreshInterval) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.widget_config_interval_label))
        // FlowRow would be nicer but isn't in stable Material3 yet for our
        // BOM. A simple two-row grid keeps the dependency footprint clean.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            intervalChip(RefreshInterval.EVERY_15_MINUTES, selected, onSelect)
            intervalChip(RefreshInterval.EVERY_30_MINUTES, selected, onSelect)
            intervalChip(RefreshInterval.EVERY_HOUR, selected, onSelect)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            intervalChip(RefreshInterval.EVERY_6_HOURS, selected, onSelect)
            intervalChip(RefreshInterval.EVERY_24_HOURS, selected, onSelect)
        }
    }
}

@Composable
private fun intervalChip(
    interval: RefreshInterval,
    selected: RefreshInterval,
    onSelect: (RefreshInterval) -> Unit,
) {
    val labelRes = when (interval) {
        RefreshInterval.EVERY_15_MINUTES -> R.string.widget_config_interval_15m
        RefreshInterval.EVERY_30_MINUTES -> R.string.widget_config_interval_30m
        RefreshInterval.EVERY_HOUR -> R.string.widget_config_interval_1h
        RefreshInterval.EVERY_6_HOURS -> R.string.widget_config_interval_6h
        RefreshInterval.EVERY_24_HOURS -> R.string.widget_config_interval_24h
    }
    FilterChip(
        selected = interval == selected,
        onClick = { onSelect(interval) },
        label = { Text(stringResource(labelRes)) },
    )
}

@Composable
private fun PreviewSection(
    state: WidgetConfigurationViewModel.UiState,
    onRequestPreview: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.widget_config_preview))
            OutlinedButton(
                onClick = onRequestPreview,
                enabled = state.canConfirm && !state.isPreviewLoading,
            ) {
                Text("Refresh preview")
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f),
            contentAlignment = Alignment.Center,
        ) {
            when {
                state.isPreviewLoading -> CircularProgressIndicator()
                state.previewError != null -> Text(
                    text = "Preview failed: ${state.previewError}",
                )
                state.previewBitmap != null -> Image(
                    bitmap = state.previewBitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                else -> Text("Tap \"Refresh preview\" to render the page.")
            }
        }
    }
}
