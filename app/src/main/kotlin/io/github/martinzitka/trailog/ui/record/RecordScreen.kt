package io.github.martinzitka.trailog.ui.record

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
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.martinzitka.trailog.R
import io.github.martinzitka.trailog.core.model.ActivityType
import io.github.martinzitka.trailog.ui.format.LocalFormatter
import io.github.martinzitka.trailog.ui.format.icon
import io.github.martinzitka.trailog.ui.format.label
import io.github.martinzitka.trailog.ui.map.MapCameraMode
import io.github.martinzitka.trailog.ui.map.MapInteraction
import io.github.martinzitka.trailog.ui.map.RouteMap
import io.github.martinzitka.trailog.ui.map.TracePoint
import io.github.martinzitka.trailog.ui.settings.PrefsAppSettings

/**
 * The Record screen. Renders each of the seven [RecordUiState] cases and forwards intent to the
 * [RecordViewModel]. All Android-specific work — reading permissions, launching the system
 * permission dialogs and settings pages — lives here at the edge; the ViewModel stays platform-free.
 *
 * Permission and battery-optimisation state is re-read on every ON_RESUME (so returning from the
 * system settings page reflects immediately) and after each permission dialog result.
 */
@Composable
fun RecordScreen(
    viewModel: RecordViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by viewModel.uiState.collectAsState()
    val namingPrompt by viewModel.namingPrompt.collectAsState()

    // Opt-in only, and only while actually recording (ADR 0011: recording never depends on the
    // screen — the service does the work, so the default is to let the phone sleep and save the
    // battery for a long ride). Paused does not qualify: nothing is being captured to watch.
    val keepScreenOn by PrefsAppSettings.get(context).preferences.collectAsState()
    val holdScreenOn = keepScreenOn.keepScreenOnWhileRecording && state is RecordUiState.Recording
    val view = LocalView.current
    DisposableEffect(view, holdScreenOn) {
        view.keepScreenOn = holdScreenOn
        // Released on leaving the screen as well as on stopping, so the flag can never outlive
        // the composable that set it.
        onDispose { view.keepScreenOn = false }
    }

    val foregroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { viewModel.updateEnvironment(RecordPermissions.read(context)) }
    val backgroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.updateEnvironment(RecordPermissions.read(context)) }

    // Re-read the environment whenever the screen resumes (e.g. back from system settings).
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.updateEnvironment(RecordPermissions.read(context))
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { viewModel.updateEnvironment(RecordPermissions.read(context)) }

    var showStopConfirm by rememberSaveable { mutableStateOf(false) }

    // The live map is pannable, and it lives inside this scrolling column. Compose's scroll
    // gesture would otherwise claim every vertical drag before MapLibre saw it, so the map
    // could only be panned sideways — half a map. Suspending the page scroll while a finger is
    // on the map gives the gesture to whichever surface the user actually touched.
    var mapTouched by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState(), enabled = !mapTouched)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (val s = state) {
            is RecordUiState.PermissionsMissing -> PermissionsMissingContent(
                onGrant = { foregroundLauncher.launch(RecordPermissions.foregroundPermissions()) },
                onOpenSettings = { RecordPermissions.openAppSettings(context) },
            )

            is RecordUiState.Ready -> ReadyContent(
                onMapTouchedChange = { mapTouched = it },
                state = s,
                onSelectType = viewModel::selectType,
                onStart = viewModel::start,
                onRequestBackground = {
                    val perm = RecordPermissions.backgroundPermission()
                    if (perm != null) backgroundLauncher.launch(perm)
                    else RecordPermissions.openAppSettings(context)
                },
                onOpenSettings = { RecordPermissions.openAppSettings(context) },
                onFixBattery = { RecordPermissions.requestBatteryExemption(context) },
            )

            is RecordUiState.Recording -> ActiveContent(
                onMapTouchedChange = { mapTouched = it },
                type = s.activityType,
                live = s.live,
                segments = s.segments,
                warnings = s.environment.warnings,
                primaryLabel = stringResource(R.string.record_pause),
                onPrimary = viewModel::pause,
                onStop = { showStopConfirm = true },
                onFixBattery = { RecordPermissions.requestBatteryExemption(context) },
            )

            is RecordUiState.Paused -> ActiveContent(
                onMapTouchedChange = { mapTouched = it },
                type = s.activityType,
                live = s.live,
                segments = s.segments,
                warnings = s.environment.warnings,
                primaryLabel = stringResource(R.string.record_resume),
                onPrimary = viewModel::resume,
                onStop = { showStopConfirm = true },
                onFixBattery = { RecordPermissions.requestBatteryExemption(context) },
            )

            is RecordUiState.Saving -> SavingContent()

            is RecordUiState.InterruptedSessionFound -> InterruptedContent(
                gapSeconds = s.gapSeconds,
                onResume = viewModel::recoverResume,
                onFinish = viewModel::recoverFinish,
            )
        }
    }

    if (showStopConfirm) {
        StopConfirmDialog(
            onConfirm = {
                showStopConfirm = false
                viewModel.stop()
            },
            onDismiss = { showStopConfirm = false },
        )
    }

    // The ride is already on disk by the time this appears — the dialog only ever adds metadata,
    // so every way out of it (Skip, a tap outside, back, the process dying) keeps the activity.
    namingPrompt?.let { prompt ->
        NameActivityDialog(
            activityType = prompt.activityType,
            onSave = viewModel::saveName,
            onSkip = viewModel::skipNaming,
        )
    }
}

// ---- states ------------------------------------------------------------------------------

@Composable
private fun PermissionsMissingContent(
    onGrant: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Text(
        stringResource(R.string.record_permissions_missing_title),
        style = MaterialTheme.typography.titleLarge,
    )
    Text(
        stringResource(R.string.record_permissions_missing_body),
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        stringResource(R.string.record_background_rationale),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(onClick = onGrant, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.record_grant_foreground))
    }
    OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.record_open_settings))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReadyContent(
    state: RecordUiState.Ready,
    onMapTouchedChange: (Boolean) -> Unit,
    onSelectType: (ActivityType) -> Unit,
    onStart: () -> Unit,
    onRequestBackground: () -> Unit,
    onOpenSettings: () -> Unit,
    onFixBattery: () -> Unit,
) {
    Text(stringResource(R.string.record_ready_hint), style = MaterialTheme.typography.bodyLarge)

    Text(stringResource(R.string.record_activity_type), style = MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActivityType.entries.forEach { type ->
            FilterChip(
                selected = state.activityType == type,
                onClick = { onSelectType(type) },
                leadingIcon = {
                    Icon(type.icon(), contentDescription = null, modifier = Modifier.sizeIn(maxWidth = 18.dp, maxHeight = 18.dp))
                },
                label = { Text(type.label()) },
            )
        }
    }

    LiveTraceMap(segments = emptyList(), onTouchedChange = onMapTouchedChange)

    Button(onClick = onStart, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(stringResource(R.string.record_start))
    }

    WarningCards(
        warnings = state.environment.warnings,
        onRequestBackground = onRequestBackground,
        onOpenSettings = onOpenSettings,
        onFixBattery = onFixBattery,
    )
}

@Composable
private fun ActiveContent(
    type: ActivityType,
    onMapTouchedChange: (Boolean) -> Unit,
    live: LiveStats,
    segments: List<List<TracePoint>>,
    warnings: List<RecordWarning>,
    primaryLabel: String,
    onPrimary: () -> Unit,
    onStop: () -> Unit,
    onFixBattery: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(type.icon(), contentDescription = null)
        Text(type.label(), style = MaterialTheme.typography.titleMedium)
    }

    LiveTraceMap(segments = segments, onTouchedChange = onMapTouchedChange)

    LiveStatsGrid(live)

    Button(
        onClick = onPrimary,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) { Text(primaryLabel) }
    OutlinedButton(
        onClick = onStop,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) { Text(stringResource(R.string.record_stop)) }

    // While recording, only the battery-exemption gap is worth nagging about; the others matter
    // before the ride, not during it.
    if (warnings.contains(RecordWarning.BATTERY_NOT_EXEMPT)) {
        BatteryWarningCard(onFixBattery)
    }
}

@Composable
private fun SavingContent() {
    Box(
        modifier = Modifier.fillMaxWidth().height(200.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator()
            Text(stringResource(R.string.record_saving), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun InterruptedContent(
    gapSeconds: Long,
    onResume: () -> Unit,
    onFinish: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Restore, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                Text(
                    stringResource(R.string.record_interrupted_title),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Text(
                stringResource(
                    R.string.record_interrupted_body,
                    LocalFormatter.current.elapsedSince(gapSeconds),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onResume, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.record_interrupted_resume))
                }
                OutlinedButton(onClick = onFinish, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.record_interrupted_finish))
                }
            }
        }
    }
}

// ---- pieces ------------------------------------------------------------------------------

@Composable
private fun LiveTraceMap(
    segments: List<List<TracePoint>>,
    onTouchedChange: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().height(220.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        RouteMap(
            segments = segments,
            modifier = Modifier.fillMaxSize(),
            contentDescription = stringResource(R.string.record_title),
            emptyLabel = stringResource(R.string.record_trace_empty),
            // The live map follows the newest fix; panning away offers a re-centre control.
            cameraMode = MapCameraMode.FOLLOW_END,
            // The live map sits in a scrolling page, so the scroll is suspended while a finger
            // is on it — otherwise Compose and MapLibre both chase the same drag.
            interaction = MapInteraction.Gestures(onTouchedChange = onTouchedChange),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LiveStatsGrid(live: LiveStats) {
    val format = LocalFormatter.current
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatTile(stringResource(R.string.record_stat_elapsed), format.duration(live.elapsedSeconds))
        StatTile(stringResource(R.string.record_stat_moving), format.duration(live.movingSeconds))
        StatTile(stringResource(R.string.record_stat_distance), format.distance(live.distance))
        StatTile(stringResource(R.string.record_stat_speed), format.speed(live.currentSpeed))
        StatTile(stringResource(R.string.record_stat_elevation_gain), format.elevation(live.elevationGain))
    }
}

@Composable
private fun StatTile(label: String, value: String) {
    Card(
        modifier = Modifier.sizeIn(minWidth = 150.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                value,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun WarningCards(
    warnings: List<RecordWarning>,
    onRequestBackground: () -> Unit,
    onOpenSettings: () -> Unit,
    onFixBattery: () -> Unit,
) {
    warnings.forEach { warning ->
        when (warning) {
            RecordWarning.NO_BACKGROUND_LOCATION -> WarningCard(
                icon = Icons.Filled.WarningAmber,
                title = stringResource(R.string.record_permission_background),
                body = stringResource(R.string.record_background_rationale),
                actionLabel = stringResource(R.string.record_request_background),
                onAction = onRequestBackground,
                secondaryLabel = stringResource(R.string.record_open_settings),
                onSecondary = onOpenSettings,
            )

            RecordWarning.NO_NOTIFICATIONS -> WarningCard(
                icon = Icons.Filled.NotificationsOff,
                title = stringResource(R.string.record_permission_notifications),
                body = stringResource(R.string.record_notifications_rationale),
                actionLabel = stringResource(R.string.record_open_settings),
                onAction = onOpenSettings,
            )

            RecordWarning.BATTERY_NOT_EXEMPT -> BatteryWarningCard(onFixBattery)
        }
    }
}

@Composable
private fun BatteryWarningCard(onFixBattery: () -> Unit) {
    WarningCard(
        icon = Icons.Filled.BatteryAlert,
        title = stringResource(R.string.record_battery_warning_title),
        body = stringResource(R.string.record_battery_warning_body),
        actionLabel = stringResource(R.string.record_battery_fix),
        onAction = onFixBattery,
    )
}

@Composable
private fun WarningCard(
    icon: ImageVector,
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(actionLabel)
                }
                if (secondaryLabel != null && onSecondary != null) {
                    OutlinedButton(onClick = onSecondary, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(secondaryLabel)
                    }
                }
            }
        }
    }
}

/**
 * "Name this ride?" — raised once a recording has been finalised (M1.8).
 *
 * The name field starts *empty* rather than pre-filled with the derived title: History already
 * falls back to the type and date for an unnamed activity, and writing that derived string into
 * the row would store a formatted value (ADR 0014). The type label is a placeholder — a hint,
 * never a value, and Material 3 only reveals it once the field has focus, which is why the body
 * text says in prose what an unnamed ride will be listed as.
 *
 * Dismissing by tapping outside is routed to [onSkip] and not to a separate "cancel" path,
 * because there is nothing to cancel — skipping is the same thing.
 */
@Composable
private fun NameActivityDialog(
    activityType: ActivityType,
    onSave: (String, String?) -> Unit,
    onSkip: () -> Unit,
) {
    // rememberSaveable so a rotation mid-typing does not discard what was typed; the prompt
    // itself survives in the ViewModel.
    var name by rememberSaveable { mutableStateOf("") }
    var notes by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onSkip,
        title = { Text(stringResource(R.string.record_name_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.record_name_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.record_name_name)) },
                    placeholder = { Text(activityType.label()) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text(stringResource(R.string.record_name_notes)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, notes.ifBlank { null }) }) {
                Text(stringResource(R.string.record_name_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onSkip) {
                Text(stringResource(R.string.record_name_skip))
            }
        },
    )
}

@Composable
private fun StopConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.record_stop_confirm_title)) },
        text = { Text(stringResource(R.string.record_stop_confirm_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.record_stop_confirm_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.record_stop_confirm_dismiss))
            }
        },
    )
}
