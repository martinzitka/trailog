package io.github.martinzitka.trailog.ui.sensors

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.martinzitka.trailog.R
import io.github.martinzitka.trailog.core.recording.RecordingState
import io.github.martinzitka.trailog.ui.format.LocalFormatter
import io.github.martinzitka.trailog.ui.record.RecordPermissions

/**
 * Sensors and diagnostics: what the phone's receiver and sensors are reporting right now, and
 * whether the recorder is in a state to use them.
 *
 * The screen is a list of label/value rows grouped into cards, driven entirely by the
 * [SensorsUiState] the ViewModel produces. Adding a sensor later is a new [DiagnosticId], a branch
 * in [label] and [valueText], and a string — the layout never changes (M1.5 acceptance criterion).
 *
 * Values update every second while the screen is open, and the probe is released when it is not.
 * As on the Record screen, all Android work — reading permissions, launching the system dialog —
 * happens here at the edge; the ViewModel stays platform-free.
 */
@Composable
fun SensorsScreen(
    viewModel: SensorsViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // Lifecycle-aware on purpose, and load-bearing: backgrounding the app does not dispose the
    // composition, so a plain collectAsState would keep a subscriber alive, keep WhileSubscribed
    // from ever firing, and leave the receiver running at 1 Hz behind the home screen. Verified on
    // device — with this, the registration is dropped a few seconds after ON_STOP.
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { viewModel.updateEnvironment(RecordPermissions.read(context)) }

    // Re-read permissions on every resume, so returning from system settings shows the new truth.
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.sensors_title), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.sensors_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (state.locationPermissionMissing) {
            PermissionCard(
                onGrant = { permissionLauncher.launch(RecordPermissions.foregroundPermissions()) },
            )
        }

        state.groups.forEach { group -> DiagnosticGroupCard(group) }
    }
}

// ---- pieces ------------------------------------------------------------------------------

@Composable
private fun PermissionCard(onGrant: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.sensors_permission_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                stringResource(R.string.sensors_permission_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Button(onClick = onGrant, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.sensors_permission_grant))
            }
        }
    }
}

@Composable
private fun DiagnosticGroupCard(group: DiagnosticGroup) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(
                text = title(group.id),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            group.rows.forEachIndexed { index, row ->
                if (index > 0) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }
                DiagnosticRowItem(row)
            }
        }
    }
}

@Composable
private fun DiagnosticRowItem(row: DiagnosticRow) {
    val label = label(row.id)
    val value = valueText(row.id, row.value)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 48dp minimum touch/where-your-eye-lands target, and one semantics node per row so a
            // screen reader announces "Accuracy, 4.2 m" rather than two disconnected fragments.
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clearAndSetSemantics { contentDescription = "$label: $value" },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        StatusDot(row.status)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = statusColor(row.status),
            textAlign = TextAlign.End,
        )
    }
}

/**
 * A small tick or warning triangle beside a value. Purely redundant decoration — the row's own
 * semantics carry the label and value — so it is left out of the accessibility tree entirely
 * rather than given a content description that would be read twice.
 */
@Composable
private fun StatusDot(status: DiagnosticStatus) {
    when (status) {
        DiagnosticStatus.GOOD -> Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = statusColor(status),
            modifier = Modifier.size(16.dp),
        )

        DiagnosticStatus.WARN -> Icon(
            Icons.Filled.WarningAmber,
            contentDescription = null,
            tint = statusColor(status),
            modifier = Modifier.size(16.dp),
        )

        DiagnosticStatus.NEUTRAL -> Unit
    }
}

@Composable
private fun statusColor(status: DiagnosticStatus): Color = when (status) {
    DiagnosticStatus.GOOD -> MaterialTheme.colorScheme.primary
    DiagnosticStatus.WARN -> MaterialTheme.colorScheme.error
    DiagnosticStatus.NEUTRAL -> MaterialTheme.colorScheme.onSurface
}

// ---- id -> text mappings -------------------------------------------------------------------

@Composable
private fun title(id: DiagnosticGroupId): String = stringResource(
    when (id) {
        DiagnosticGroupId.GPS -> R.string.sensors_group_gps
        DiagnosticGroupId.SENSORS -> R.string.sensors_group_sensors
        DiagnosticGroupId.RECORDING -> R.string.sensors_group_recording
    },
)

@Composable
private fun label(id: DiagnosticId): String = stringResource(
    when (id) {
        DiagnosticId.PROVIDER -> R.string.sensors_row_provider
        DiagnosticId.ACCURACY -> R.string.sensors_row_accuracy
        DiagnosticId.SATELLITES -> R.string.sensors_row_satellites
        DiagnosticId.FIX_AGE -> R.string.sensors_row_fix_age
        DiagnosticId.BAROMETER -> R.string.sensors_row_barometer
        DiagnosticId.SESSION_STATE -> R.string.sensors_row_session_state
        DiagnosticId.SERVICE_RUNNING -> R.string.sensors_row_service_running
        DiagnosticId.PERMISSION_FINE -> R.string.sensors_row_permission_fine
        DiagnosticId.PERMISSION_BACKGROUND -> R.string.sensors_row_permission_background
        DiagnosticId.PERMISSION_NOTIFICATIONS -> R.string.sensors_row_permission_notifications
        DiagnosticId.BATTERY_OPTIMISATION -> R.string.sensors_row_battery
    },
)

/**
 * Renders a value for display. Numbers go through the ambient [Formatter] — the single conversion
 * point, carrying the user's unit preference — and prose comes from string resources. [id] is
 * passed because one value type reads differently in two places: battery optimisation is "exempt",
 * not "granted".
 */
@Composable
private fun valueText(id: DiagnosticId, value: DiagnosticValue): String {
    val format = LocalFormatter.current
    return when (value) {
        DiagnosticValue.Waiting -> stringResource(R.string.sensors_value_waiting)
        DiagnosticValue.Unavailable -> stringResource(R.string.sensors_value_unavailable)
        is DiagnosticValue.Text -> value.value
        is DiagnosticValue.Length -> format.length(value.meters)
        is DiagnosticValue.Age -> stringResource(
            R.string.sensors_value_fix_age,
            format.elapsedSince(value.seconds),
        )

        is DiagnosticValue.Pressure -> format.pressure(value.pascals)
        is DiagnosticValue.Ratio -> if (value.outOf != null) {
            stringResource(R.string.sensors_value_satellites, value.value, value.outOf)
        } else {
            stringResource(R.string.sensors_value_satellites_used_only, value.value)
        }

        is DiagnosticValue.Granted -> if (id == DiagnosticId.BATTERY_OPTIMISATION) {
            stringResource(
                if (value.granted) R.string.sensors_value_exempt else R.string.sensors_value_not_exempt,
            )
        } else {
            stringResource(
                if (value.granted) R.string.sensors_value_granted else R.string.sensors_value_denied,
            )
        }

        is DiagnosticValue.Running -> stringResource(
            if (value.running) {
                R.string.sensors_value_service_running
            } else {
                R.string.sensors_value_service_stopped
            },
        )

        is DiagnosticValue.Session -> stringResource(
            when (value.state) {
                null -> R.string.sensors_session_none
                RecordingState.IDLE -> R.string.sensors_session_idle
                RecordingState.RECORDING -> R.string.sensors_session_recording
                RecordingState.PAUSED -> R.string.sensors_session_paused
                RecordingState.STOPPING -> R.string.sensors_session_stopping
                RecordingState.RECOVERING -> R.string.sensors_session_recovering
            },
        )
    }
}
