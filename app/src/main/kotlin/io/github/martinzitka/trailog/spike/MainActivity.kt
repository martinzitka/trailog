package io.github.martinzitka.trailog.spike

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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.martinzitka.trailog.spike.RecordingService
import io.github.martinzitka.trailog.spike.RecordingState
import io.github.martinzitka.trailog.spike.GpxExporter
import io.github.martinzitka.trailog.spike.data.SpikeDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * M0 spike: a single Activity. Permission flow + live diagnostics + a GPX dump button.
 * No architecture on purpose (IMPLEMENTATION_PLAN.md M0). Throwaway.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            M0Theme {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    M0Screen(Modifier.padding(padding))
                }
            }
        }
    }
}

@Composable
private fun M0Theme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (dark) darkColorScheme() else lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
@Suppress("InlinedApi") // ACCESS_BACKGROUND_LOCATION (API 29) is only used as a permission-check string; harmless pre-29.
private fun M0Screen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val uiScope = rememberCoroutineScope()
    val state by RecordingState.state.collectAsState()

    val dao = remember { SpikeDatabase.get(context).rawFixDao() }
    val dbCount by dao.countFlow().collectAsState(initial = 0)
    val latest by dao.latestFlow().collectAsState(initial = null)

    // Ticking "now" so time-since-last-fix updates every second.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    val exportResult = remember { MutableStateFlow<String?>(null) }
    val exportMsg by exportResult.collectAsState()

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* result reflected via checkSelfPermission below */ }

    val bgPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Trailog · M0 recording spike", style = MaterialTheme.typography.titleLarge)

        DiagCard {
            val sinceFix = if (state.lastFixTime > 0) {
                "${(now - state.lastFixTime) / 1000}s ago"
            } else if (latest != null) {
                "${(now - latest!!.fixTime) / 1000}s ago (from db)"
            } else "no fix yet"

            Row2("Service", if (state.serviceRunning) "RUNNING" else "stopped")
            Row2("Session", state.sessionId.toString())
            Row2("Fixes in DB", dbCount.toString())
            Row2("Fixes this run", state.fixesThisProcess.toString())
            Row2("Last fix", sinceFix)
            Row2("Provider", state.provider)
            Row2("Accuracy", state.lastAccuracy?.let { "%.0f m".format(it) } ?: "-")
            Row2("Satellites (used)", state.satellites?.toString() ?: "-")
            Row2(
                "Barometer",
                if (!state.hasBarometer) "not available on this device"
                else state.pressure?.let { "%.0f Pa".format(it) } ?: "…",
            )
        }

        Text("Permissions", style = MaterialTheme.typography.titleMedium)
        DiagCard {
            Row2("Notifications", permLabel(context, notificationsPerm()))
            Row2("Fine location", permLabel(context, Manifest.permission.ACCESS_FINE_LOCATION))
            Row2(
                "Background location",
                permLabel(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION),
            )
            Row2("Battery-opt exempt", if (isIgnoringBatteryOptimizations(context)) "yes" else "NO")
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
                // Background location must be requested separately, after fine is granted.
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

        Button(
            onClick = { RecordingService.start(context) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Start recording") }

        OutlinedButton(
            onClick = { RecordingService.stop(context) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Stop recording") }

        Button(
            onClick = {
                uiScope.launch {
                    val fixes = withContext(Dispatchers.IO) {
                        SpikeDatabase.get(context).rawFixDao().all()
                    }
                    exportResult.value = if (fixes.isEmpty()) {
                        "nothing recorded yet"
                    } else {
                        withContext(Dispatchers.IO) { GpxExporter.export(context, fixes) }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Export GPX to Downloads") }

        exportMsg?.let { Text("Exported: $it", style = MaterialTheme.typography.bodySmall) }

        Text(
            "M0 is a throwaway spike. See IMPLEMENTATION_PLAN.md. Run two 2h outdoor " +
                "recordings (one with poor signal), then check the exported GPX.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun DiagCard(content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            content()
        }
    }
}

@Composable
private fun Row2(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = colorScheme.primary)
    }
}

private fun notificationsPerm(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.POST_NOTIFICATIONS
    } else {
        Manifest.permission.ACCESS_FINE_LOCATION // pre-13: always effectively granted
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
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = Uri.parse("package:${context.packageName}")
    }
    context.startActivity(intent)
}

private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.parse("package:${context.packageName}")
    }
    context.startActivity(intent)
}
