package io.github.martinzitka.trailog.ui.detail

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.annotation.StringRes
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.martinzitka.trailog.R
import io.github.martinzitka.trailog.core.model.ActivityType
import io.github.martinzitka.trailog.ui.chart.ProfileChart
import io.github.martinzitka.trailog.ui.format.LocalFormatter
import io.github.martinzitka.trailog.ui.format.label
import io.github.martinzitka.trailog.ui.map.MapInteraction
import io.github.martinzitka.trailog.ui.map.RouteMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Activity detail — the route on a map, the summary figures, elevation and speed profiles,
 * per-kilometre splits, and the edit, delete and export actions. Pushed from History.
 *
 * Everything on this screen is read from the on-device database and works with no network at all.
 * The map is a preview: it renders over self-hosted tiles when a region archive is installed and
 * over a blank surface when none is, and a tap opens the fullscreen map.
 *
 * Android-specific work stays here at the edge — the export destination is chosen through the
 * system document picker, so a GPX file lands wherever the user says and nothing is written to
 * shared storage behind their back.
 *
 * @param onBack pop back to History. Also called automatically once the activity is deleted.
 * @param onOpenMap open the fullscreen map, where panning, zooming and the linked charts live.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityDetailScreen(
    viewModel: ActivityDetailViewModel,
    onBack: () -> Unit,
    onOpenMap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Hoisted out of the composable scope so the export lambda below can capture it: a
    // CompositionLocal cannot be read from inside a plain onClick.
    val format = LocalFormatter.current
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }
    var showEdit by rememberSaveable { mutableStateOf(false) }

    val exportedMessage = stringResource(R.string.detail_export_done)
    val exportFailedMessage = stringResource(R.string.detail_export_failed)
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(GPX_MIME_TYPE),
    ) { uri ->
        // A null uri means the user backed out of the picker; that is not a failure.
        if (uri != null) {
            scope.launch {
                val written = writeGpx(context, uri, viewModel.buildGpx())
                snackbarHostState.showSnackbar(
                    if (written) exportedMessage else exportFailedMessage,
                )
            }
        }
    }

    val loaded = (state as? ActivityDetailUiState.Loaded)?.detail
    val title = loaded?.name?.ifBlank { null } ?: loaded?.type?.label()

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = title ?: stringResource(R.string.nav_activity_detail),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.detail_back),
                        )
                    }
                },
                actions = {
                    if (loaded != null) {
                        IconButton(onClick = { showEdit = true }) {
                            Icon(
                                Icons.Filled.Edit,
                                contentDescription = stringResource(R.string.detail_edit),
                            )
                        }
                        IconButton(
                            onClick = {
                                exportLauncher.launch(
                                    format.exportFileName(
                                        slug = loaded.name.ifBlank { loaded.type.name },
                                        epochMillis = loaded.startTime,
                                        extension = "gpx",
                                    ),
                                )
                            },
                        ) {
                            Icon(
                                Icons.Filled.FileDownload,
                                contentDescription = stringResource(R.string.detail_export_gpx),
                            )
                        }
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.detail_delete),
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when (val s = state) {
                is ActivityDetailUiState.Loading -> LoadingContent()

                is ActivityDetailUiState.Gone -> GoneContent(onBack = onBack)

                is ActivityDetailUiState.Loaded -> LoadedContent(
                    detail = s.detail,
                    onOpenMap = onOpenMap,
                    onSelectSplitLaps = viewModel::setSplitLaps,
                )
            }
        }
    }

    if (showEdit && loaded != null) {
        EditDialog(
            initialName = loaded.name,
            initialNotes = loaded.notes.orEmpty(),
            initialType = loaded.type,
            onDismiss = { showEdit = false },
            onSave = { name, notes, type ->
                viewModel.saveEdits(name, notes, type)
                showEdit = false
            },
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.detail_delete_confirm_title)) },
            text = { Text(stringResource(R.string.detail_delete_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        viewModel.delete()
                    },
                ) {
                    Text(stringResource(R.string.detail_delete_confirm_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.detail_delete_confirm_dismiss))
                }
            },
        )
    }
}

// ---- states ---------------------------------------------------------------------------------

@Composable
private fun LoadingContent() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        CircularProgressIndicator()
        Text(
            stringResource(R.string.detail_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The activity no longer exists. Navigating back is automatic — the user either just deleted it
 * or it went from under them — but the message renders first so the screen is never blank, and
 * the button covers the case where the automatic pop is somehow missed.
 */
@Composable
private fun GoneContent(onBack: () -> Unit) {
    LaunchedEffect(Unit) { onBack() }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(
            stringResource(R.string.detail_gone_title),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.detail_gone_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.detail_gone_action))
        }
    }
}

@Composable
private fun LoadedContent(
    detail: ActivityDetail,
    onOpenMap: () -> Unit,
    onSelectSplitLaps: (Int) -> Unit,
) {
    val format = LocalFormatter.current
    // Hoisted so the map preview can drive it: a drag over the map has to scroll the page, and
    // the MapView will not pass one on by itself (see MapInteraction.Tap).
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            format.dateTime(detail.startTime),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // A preview, not a pannable map: a drag over it scrolls the page like a drag anywhere
        // else, and a tap opens the fullscreen map where zooming into a climb or a junction
        // belongs. Panning here instead used to mean the page could not be scrolled from the
        // map, and that a single-finger drag was claimed by whichever of the two gesture
        // handlers happened to win.
        RouteMap(
            segments = detail.segments,
            contentDescription = stringResource(R.string.detail_map_cd),
            showEndMarker = false,
            emptyLabel = stringResource(R.string.detail_map_empty),
            interaction = MapInteraction.Tap(onClick = onOpenMap, hostScroll = scrollState),
            modifier = Modifier
                .fillMaxWidth()
                .height(MAP_HEIGHT),
        )

        if (detail.statsPending) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Text(
                    stringResource(R.string.detail_stats_pending),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        SummarySection(detail)

        if (detail.notes != null) {
            Text(detail.notes, style = MaterialTheme.typography.bodyMedium)
        }

        ProfileChart(
            series = detail.elevationProfile,
            title = stringResource(R.string.detail_chart_elevation),
            minLabel = detail.elevationProfile.minValue?.let { format.elevation(it) }.orEmpty(),
            maxLabel = detail.elevationProfile.maxValue?.let { format.elevation(it) }.orEmpty(),
            emptyLabel = stringResource(R.string.detail_chart_no_elevation),
            contentDescription = stringResource(R.string.detail_chart_elevation_cd),
        )

        ProfileChart(
            series = detail.speedProfile,
            title = stringResource(R.string.detail_chart_speed),
            minLabel = detail.speedProfile.minValue?.let { format.speed(it) }.orEmpty(),
            maxLabel = detail.speedProfile.maxValue?.let { format.speed(it) }.orEmpty(),
            emptyLabel = stringResource(R.string.detail_chart_no_speed),
            contentDescription = stringResource(R.string.detail_chart_speed_cd),
        )

        SplitsSection(detail, onSelectSplitLaps)
    }
}

// ---- pieces ---------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SummarySection(detail: ActivityDetail) {
    val format = LocalFormatter.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.detail_summary_title), style = MaterialTheme.typography.titleMedium)

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Figure(R.string.detail_stat_distance, format.distance(detail.distance))
            Figure(R.string.detail_stat_elapsed, format.duration(detail.elapsedSeconds))
            Figure(R.string.detail_stat_moving, format.duration(detail.movingSeconds))
            Figure(R.string.detail_stat_elevation_gain, format.elevation(detail.elevationGain))
            Figure(R.string.detail_stat_elevation_loss, format.elevation(detail.elevationLoss))
            Figure(R.string.detail_stat_average_speed, format.speed(detail.averageSpeed))
            Figure(R.string.detail_stat_max_speed, format.speed(detail.maxSpeed))
        }

        if (detail.segmentCount > 0) {
            Text(
                pluralStringResource(
                    R.plurals.detail_segment_note,
                    detail.segmentCount,
                    detail.segmentCount,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One labelled figure. The value is already formatted by [Format]; nothing converts here. */
@Composable
private fun Figure(@StringRes labelRes: Int, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1)
        Text(
            stringResource(labelRes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun SplitsSection(detail: ActivityDetail, onSelectSplitLaps: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // The heading no longer names the interval, because the chips below it do — and at 5 km
        // a "per-kilometre splits" heading would be a lie. The chips carry the unit, so an
        // imperial reader is never shown kilometres either.
        Text(
            stringResource(R.string.detail_splits_title),
            style = MaterialTheme.typography.titleMedium,
        )

        SplitIntervalChips(selected = detail.splitLaps, onSelect = onSelectSplitLaps)

        if (detail.splits.isEmpty()) {
            Text(
                stringResource(R.string.detail_splits_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        SplitHeaderRow()
        HorizontalDivider()
        // Not a LazyColumn: this sits inside a vertically scrolling column, and a ride's worth
        // of splits is tens of rows, not thousands.
        detail.splits.forEach { split -> SplitRowContent(split) }
    }
}

/**
 * The interval picker: 1, 2, 5 or 10 laps of the display unit.
 *
 * Always shown, even when the ride is too short to split at the chosen interval. Hiding them
 * there would strand the reader: picking 10 km on a 3 km ride is exactly the moment they need a
 * way back to 1 km.
 */
@Composable
private fun SplitIntervalChips(selected: Int, onSelect: (Int) -> Unit) {
    val format = LocalFormatter.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActivityDetailViewModel.SPLIT_LAP_OPTIONS.forEach { laps ->
            val label = format.laps(laps)
            FilterChip(
                selected = laps == selected,
                onClick = { onSelect(laps) },
                label = { Text(label) },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics { this.contentDescription = label },
            )
        }
    }
}

@Composable
private fun SplitHeaderRow() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val style = MaterialTheme.typography.labelSmall
        val color = MaterialTheme.colorScheme.onSurfaceVariant
        // "Split", not "km": the column counts splits, and at a 5 km interval row 3 is the third
        // split rather than the third kilometre.
        Text(
            stringResource(R.string.detail_splits_column_split),
            style = style,
            color = color,
            modifier = Modifier.weight(0.8f),
        )
        Text(stringResource(R.string.detail_splits_column_time), style = style, color = color, modifier = Modifier.weight(1f))
        Text(stringResource(R.string.detail_splits_column_speed), style = style, color = color, modifier = Modifier.weight(1.2f))
        Text(stringResource(R.string.detail_splits_column_gain), style = style, color = color, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SplitRowContent(split: SplitRow) {
    val format = LocalFormatter.current
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val style = MaterialTheme.typography.bodyMedium
        Column(modifier = Modifier.weight(0.8f)) {
            Text(split.number.toString(), style = style, maxLines = 1)
            // A short final split is labelled with its real length rather than pretending to be
            // a full kilometre, which would make its pace meaningless.
            if (split.isPartial) {
                Text(
                    format.distance(split.distance),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        Text(format.duration(split.movingSeconds), style = style, maxLines = 1, modifier = Modifier.weight(1f))
        Text(format.speed(split.averageSpeed), style = style, maxLines = 1, modifier = Modifier.weight(1.2f))
        Text(format.elevation(split.elevationGain), style = style, maxLines = 1, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun EditDialog(
    initialName: String,
    initialNotes: String,
    initialType: ActivityType,
    onDismiss: () -> Unit,
    onSave: (String, String?, ActivityType) -> Unit,
) {
    // rememberSaveable so a rotation mid-edit does not discard what was typed.
    var name by rememberSaveable(initialName) { mutableStateOf(initialName) }
    var notes by rememberSaveable(initialNotes) { mutableStateOf(initialNotes) }
    var type by rememberSaveable(initialType) { mutableStateOf(initialType) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.detail_edit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.detail_edit_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text(stringResource(R.string.detail_edit_notes)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp),
                )
                Text(
                    stringResource(R.string.detail_edit_type),
                    style = MaterialTheme.typography.labelMedium,
                )
                TypeChips(selected = type, onSelect = { type = it })
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, notes.ifBlank { null }, type) }) {
                Text(stringResource(R.string.detail_edit_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.detail_edit_cancel))
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TypeChips(selected: ActivityType, onSelect: (ActivityType) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActivityType.entries.forEach { candidate ->
            FilterChip(
                selected = candidate == selected,
                onClick = { onSelect(candidate) },
                label = { Text(candidate.label()) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
}

// ---- Android edge -----------------------------------------------------------------------------

/**
 * Writes the serialised GPX to the destination the user picked, returning whether it worked.
 *
 * Failures here are ordinary and expected — a removed SD card, a revoked grant — and are reported
 * to the user as a message rather than thrown. Nothing is logged: the document would carry
 * coordinates and a failure message must not become a route in a bug report (CLAUDE.md).
 */
private suspend fun writeGpx(context: Context, uri: Uri, gpx: String?): Boolean {
    if (gpx == null) return false
    return runCatching {
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri)?.use { it.write(gpx.toByteArray()) }
                ?: error("no output stream")
        }
        true
    }.getOrDefault(false)
}

private const val GPX_MIME_TYPE = "application/gpx+xml"

private val MAP_HEIGHT = 240.dp
