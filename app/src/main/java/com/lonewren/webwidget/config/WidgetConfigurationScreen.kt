package com.lonewren.webwidget.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lonewren.webwidget.R
import com.lonewren.webwidget.data.RefreshInterval
import com.lonewren.webwidget.data.WidgetConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WidgetConfigurationScreen(
    state: WidgetConfigurationViewModel.UiState,
    onUrlChanged: (Int, String) -> Unit,
    onAddUrl: () -> Unit,
    onRemoveUrl: (Int) -> Unit,
    onIntervalChanged: (RefreshInterval) -> Unit,
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
            UrlList(
                urls = state.urls,
                canRemove = state.urls.size > 1 || state.urls.first().value.isNotBlank(),
                onUrlChanged = onUrlChanged,
                onRemoveUrl = onRemoveUrl,
            )

            OutlinedButton(
                onClick = onAddUrl,
                enabled = state.canAddMore,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.widget_config_add_url))
            }

            Text(
                stringResource(R.string.widget_config_max_urls_hint, WidgetConfig.MAX_URLS),
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            )

            IntervalChips(
                selected = state.interval,
                onSelect = onIntervalChanged,
            )

            if (!state.canConfirm) {
                Text(
                    text = stringResource(R.string.widget_config_no_urls),
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                )
            }

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
private fun UrlList(
    urls: List<WidgetConfigurationViewModel.UrlEntry>,
    canRemove: Boolean,
    onUrlChanged: (Int, String) -> Unit,
    onRemoveUrl: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        urls.forEachIndexed { index, entry ->
            UrlRow(
                index = index,
                entry = entry,
                onValueChange = { onUrlChanged(index, it) },
                onRemove = { onRemoveUrl(index) }.takeIf { canRemove || urls.size > 1 },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UrlRow(
    index: Int,
    entry: WidgetConfigurationViewModel.UrlEntry,
    onValueChange: (String) -> Unit,
    onRemove: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Local val: smart-cast on entry.error doesn't survive into the
        // composable lambda below; capturing it locally fixes that.
        val errorMessage = entry.error
        OutlinedTextField(
            value = entry.value,
            onValueChange = onValueChange,
            label = { Text(stringResource(R.string.widget_config_url_label, index + 1)) },
            placeholder = { Text(stringResource(R.string.widget_config_url_hint)) },
            singleLine = true,
            isError = errorMessage != null,
            supportingText = if (errorMessage != null) {
                { Text(errorMessage) }
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.weight(1f),
        )
        if (onRemove != null) {
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.widget_config_remove_url),
                )
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

@OptIn(ExperimentalMaterial3Api::class)
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
