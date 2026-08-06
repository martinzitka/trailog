package io.github.martinzitka.trailog.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.martinzitka.trailog.core.model.ActivityType
import io.github.martinzitka.trailog.core.recording.RecordingState
import io.github.martinzitka.trailog.recording.AndroidRecordingEngine
import io.github.martinzitka.trailog.recording.GpxExport
import io.github.martinzitka.trailog.recording.RecordingDiagnostics
import io.github.martinzitka.trailog.data.TrailogDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Interim M1.3 recording control + diagnostics screen. Its job is to drive the real recording
 * engine so the kill-matrix can be run on a device — start/pause/resume/stop, surface an
 * interrupted session, and dump GPX to verify the track. It is throwaway scaffolding, exempt
 * from the "Definition of done — any screen" checklist; the real Record/Sensors screens are M1.5.
 */
class MainActivity : ComponentActivity() {

    private val engine by lazy { AndroidRecordingEngine.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TrailogTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    RecordingScreen(engine, Modifier.padding(padding))
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Recovery is surfaced on next open: reconcile the persisted session against the policy.
        engine.reconcileOnAppOpen()
    }
}

@Composable
private fun TrailogTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (dark) darkColorScheme() else lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
@Suppress("InlinedApi")
private fun RecordingScreen(engine: AndroidRecordingEngine, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val uiScope = rememberCoroutineScope()

    val session by engine.session.collectAsState()
    val diag by RecordingDiagnostics.state.collectAsState()

    val db = remember { TrailogDatabase.get(context) }
    val dbCount by db.rawPointDao().countFlow().collectAsState(initial = 0)
    val latestFix by db.rawPointDao().latestFlow().collectAsState(initial = null)

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    var selectedType by remember { mutableStateOf(ActivityType.CYCLING) }
    var showStopConfirm by remember { mutableStateOf(false) }
    val exportResult = remember { MutableStateFlow<String?>(null) }
    val exportMsg by exportResult.collectAsState()

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { }
    val bgPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    val state = session?.state

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Trailog · recording (M1.3)", style = MaterialTheme.typography.titleLarge)

        // --- Session / diagnostics ---
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row2("State", state?.name ?: "IDLE")
                Row2("Segment", session?.currentSegmentIndex?.toString() ?: "—")
                val elapsed = session?.let { fmtElapsed((now - it.startTime.toEpochMilliseconds()) / 1000) } ?: "—"
                Row2("Elapsed", elapsed)
                Row2("Fixes in DB", dbCount.toString())
                Row2("Fixes this run", diag.fixesThisProcess.toString())
                val fixAge = latestFix?.let { "${(now - it.time) / 1000}s ago" } ?: "no fix yet"
                Row2("Last fix", fixAge)
                Row2("Provider", diag.provider)
                Row2("Accuracy", diag.lastAccuracy?.let { "%.0f m".format(it) } ?: "—")
                Row2("Satellites (used)", diag.satellitesUsed?.toString() ?: "—")
                Row2(
                    "Barometer",
                    if (!diag.hasBarometer) "not available on this device"
                    else diag.pressure?.let { "%.0f Pa".format(it) } ?: "…",
                )
            }
        }

        // --- Interrupted-session recovery prompt ---
        if (state == RecordingState.RECOVERING) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Interrupted activity found", style = MaterialTheme.typography.titleMedium)
                    val age = latestFix?.let { "${(now - it.time) / 1000}s since last fix" } ?: "no fixes recorded"
                    Text(age, style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { engine.recoverResume() }) { Text("Resume") }
                        OutlinedButton(onClick = { engine.finalize() }) { Text("Finish") }
                    }
                }
            }
        }

        // --- Controls ---
        when (state) {
            null, RecordingState.IDLE -> {
                Text("Activity type", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActivityType.entries.forEach { type ->
                        FilterChip(
                            selected = selectedType == type,
                            onClick = { selectedType = type },
                            label = { Text(type.name.lowercase()) },
                        )
                    }
                }
                Button(
                    onClick = { engine.start(selectedType) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Start recording") }
            }
            RecordingState.RECORDING -> {
                OutlinedButton(onClick = { engine.pause() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Pause")
                }
                Button(onClick = { showStopConfirm = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Stop")
                }
            }
            RecordingState.PAUSED -> {
                Button(onClick = { engine.resume() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Resume")
                }
                OutlinedButton(onClick = { showStopConfirm = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Stop")
                }
            }
            else -> Unit // STOPPING is transient; RECOVERING handled above.
        }

        Spacer(Modifier.height(8.dp))

        // --- Permissions ---
        Text("Permissions", style = MaterialTheme.typography.titleMedium)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row2("Fine location", permLabel(context, Manifest.permission.ACCESS_FINE_LOCATION))
                Row2("Background location", permLabel(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION))
                Row2("Battery-opt exempt", if (isIgnoringBatteryOptimizations(context)) "yes" else "NO")
            }
        }
        Button(
            onClick = {
                val perms = buildList {
                    add(Manifest.permission.ACCESS_FINE_LOCATION)
                    add(Manifest.permission.ACCESS_COARSE_LOCATION)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }.toTypedArray()
                permLauncher.launch(perms)
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("1 · Grant foreground location + notifications") }
        OutlinedButton(
            onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    bgPermLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("2 · Request background (\"Allow all the time\")") }
        OutlinedButton(
            onClick = { openAppSettings(context) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("   …or open app settings for \"Allow all the time\"") }
        OutlinedButton(
            onClick = { requestIgnoreBatteryOptimizations(context) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("3 · Battery optimisation exemption") }

        Spacer(Modifier.height(8.dp))

        // --- Export ---
        OutlinedButton(
            onClick = {
                uiScope.launch {
                    val msg = withContext(Dispatchers.IO) {
                        val dao = db.rawPointDao()
                        val id = dao.latestActivityId()
                        if (id == null) "nothing recorded yet"
                        else GpxExport.export(context, System.currentTimeMillis(), dao.pointsFor(id))
                    }
                    exportResult.value = msg
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Export latest activity to GPX") }
        exportMsg?.let { Text("Exported: $it", style = MaterialTheme.typography.bodySmall) }

        Text(
            "Interim M1.3 harness for the on-device kill-matrix. Real screens are M1.5.",
            style = MaterialTheme.typography.bodySmall,
        )
    }

    if (showStopConfirm) {
        AlertDialog(
            onDismissRequest = { showStopConfirm = false },
            title = { Text("Stop recording?") },
            text = { Text("This finishes the activity. An accidental stop loses a ride.") },
            confirmButton = {
                TextButton(onClick = {
                    showStopConfirm = false
                    engine.requestStop()
                    engine.finalize()
                }) { Text("Stop & save") }
            },
            dismissButton = {
                TextButton(onClick = { showStopConfirm = false }) { Text("Keep recording") }
            },
        )
    }
}

@Composable
private fun Row2(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = colorScheme.primary)
    }
}

private fun fmtElapsed(totalSeconds: Long): String {
    val t = totalSeconds.coerceAtLeast(0)
    val h = t / 3600
    val m = (t % 3600) / 60
    val s = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun permLabel(context: Context, perm: String): String =
    if (ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED) {
        "granted"
    } else {
        "NOT granted"
    }

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

private fun requestIgnoreBatteryOptimizations(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        },
    )
}

private fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        },
    )
}
