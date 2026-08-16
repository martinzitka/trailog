package io.github.martinzitka.trailog.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.martinzitka.trailog.R
import io.github.martinzitka.trailog.ui.format.Format
import io.github.martinzitka.trailog.ui.format.icon
import io.github.martinzitka.trailog.ui.format.label

/**
 * History — the list of recorded activities, most recent first. Fully functional offline: every
 * row is read from the on-device database and nothing here touches the network.
 *
 * All four states are rendered: loading, empty (which explains how to record a first activity —
 * a blank list is not acceptable), populated, and a read error with a retry. Rows are keyed by
 * activity id so a list of several hundred imported activities scrolls without recomposing
 * everything, and the scroll position survives configuration change via [rememberLazyListState].
 *
 * @param onOpenActivity called with an activity id when a row is tapped.
 * @param onStartRecording called from the empty state to send the user to the Record tab.
 */
@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    onOpenActivity: (String) -> Unit,
    onStartRecording: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()

    when (val s = state) {
        is HistoryUiState.Loading -> LoadingContent(modifier)

        is HistoryUiState.Empty -> MessageContent(
            icon = Icons.Filled.Timeline,
            title = stringResource(R.string.history_empty_title),
            body = stringResource(R.string.history_empty_body),
            actionLabel = stringResource(R.string.history_empty_action),
            onAction = onStartRecording,
            modifier = modifier,
        )

        is HistoryUiState.Error -> MessageContent(
            icon = Icons.Filled.CloudOff,
            title = stringResource(R.string.history_error_title),
            body = stringResource(R.string.history_error_body),
            actionLabel = stringResource(R.string.history_error_retry),
            onAction = viewModel::retry,
            modifier = modifier,
        )

        is HistoryUiState.Populated -> ActivityList(
            items = s.items,
            onOpenActivity = onOpenActivity,
            modifier = modifier,
        )
    }
}

// ---- states ------------------------------------------------------------------------------

@Composable
private fun LoadingContent(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        CircularProgressIndicator()
        Text(
            stringResource(R.string.history_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The shared shape of the empty and error states: an icon, an explanation and one action. */
@Composable
private fun MessageContent(
    icon: ImageVector,
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(actionLabel)
        }
    }
}

@Composable
private fun ActivityList(
    items: List<HistoryItem>,
    onOpenActivity: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = rememberLazyListState(),
    ) {
        items(items = items, key = { it.id }) { item ->
            ActivityRow(item = item, onClick = { onOpenActivity(item.id) })
            HorizontalDivider()
        }
    }
}

// ---- pieces ------------------------------------------------------------------------------

@Composable
private fun ActivityRow(
    item: HistoryItem,
    onClick: () -> Unit,
) {
    val typeLabel = item.type.label()
    val date = Format.date(item.startTime)
    // An unnamed activity falls back to its type as the title, so the subtitle drops the type
    // rather than repeating it.
    val title = item.name.ifBlank { typeLabel }
    val subtitle = if (item.name.isBlank()) {
        date
    } else {
        stringResource(R.string.history_row_subtitle, typeLabel, date)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 72.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            item.type.icon(),
            // The type is already announced as text in the title or subtitle.
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(
            // weight, not fillMaxWidth: inside a Row the latter would ignore the icon's width
            // and push the figures off the right edge.
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Metric(
                    label = stringResource(R.string.history_stat_distance),
                    value = item.distance?.let { Format.distance(it) },
                    modifier = Modifier.weight(1f),
                )
                Metric(
                    label = stringResource(R.string.history_stat_duration),
                    value = item.elapsedSeconds?.let { Format.duration(it) },
                    modifier = Modifier.weight(1f),
                )
                Metric(
                    label = stringResource(R.string.history_stat_elevation_gain),
                    value = item.elevationGain?.let { Format.elevation(it) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** One labelled figure in a row. A null value means "not computed yet", not zero. */
@Composable
private fun Metric(
    label: String,
    value: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            value ?: stringResource(R.string.history_stat_pending),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}
