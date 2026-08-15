package io.github.martinzitka.trailog.ui.sensors

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.martinzitka.trailog.R
import io.github.martinzitka.trailog.ui.common.ComingSoonScreen

/**
 * Sensors and diagnostics — live GPS accuracy, satellites, fix age, barometer, service and
 * permission state. Placeholder for now; the live diagnostics screen lands later in M1.5. The
 * data already exists in [io.github.martinzitka.trailog.recording.RecordingDiagnostics].
 */
@Composable
fun SensorsScreen(modifier: Modifier = Modifier) {
    ComingSoonScreen(screenName = stringResource(R.string.nav_sensors), modifier = modifier)
}
