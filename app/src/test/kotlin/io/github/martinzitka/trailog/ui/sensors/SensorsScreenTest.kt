package io.github.martinzitka.trailog.ui.sensors

import android.Manifest
import android.content.Context
import android.os.PowerManager
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.martinzitka.trailog.core.model.ActivityType
import io.github.martinzitka.trailog.core.recording.RecordingSession
import io.github.martinzitka.trailog.core.recording.RecordingState
import io.github.martinzitka.trailog.ui.theme.TrailogTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.datetime.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Compose UI test for the Sensors and diagnostics screen. Robolectric drives Compose as a JVM test,
 * so this runs on `./gradlew check` and CI with no emulator.
 *
 * It covers what the plan asks the screen to show — GPS readings, an explicit "not available" for a
 * missing barometer, service and permission state — plus navigation into the tab and back out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SensorsScreenTest {

    @get:Rule val composeRule = createComposeRule()

    private val now = Instant.fromEpochMilliseconds(1_000_000)

    private fun viewModel(
        snapshot: SensorSnapshot = SensorSnapshot(),
        session: RecordingSession? = null,
        serviceRunning: Boolean = false,
    ) = SensorsViewModel(
        probe = SensorProbe { flowOf(snapshot) },
        session = MutableStateFlow(session),
        serviceRunning = { serviceRunning },
        now = { now },
        ticker = emptyFlow(),
    )

    @Test fun `every diagnostic row is labelled, grouped and rendered`() {
        composeRule.setContent { TrailogTheme { SensorsScreen(viewModel()) } }

        // The three group headings.
        composeRule.onNodeWithText("GPS").assertIsDisplayed()
        composeRule.onNodeWithText("Sensors").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Recording & permissions").performScrollTo().assertIsDisplayed()

        // Each row is one merged semantics node, so it is addressed as "label: value" — which is
        // also exactly what a screen reader announces. Nothing is blank before a reading arrives.
        listOf(
            "Provider: Waiting…",
            "Accuracy: Waiting…",
            "Satellites: Waiting…",
            "Last fix: Waiting…",
            "Barometer: Not available on this device",
            "Recording session: No session",
            "Foreground service: Not running",
            "Precise location: Not granted",
            "Location “Allow all the time”: Not granted",
            "Notifications: Not granted",
            "Battery optimisation: Not exempt",
        ).forEach { row ->
            composeRule.onNodeWithContentDescription(row).performScrollTo().assertIsDisplayed()
        }
    }

    @Test fun `live GPS readings are formatted at the display edge`() {
        grantAllPermissions()
        val snapshot = SensorSnapshot(
            provider = "gps",
            accuracy = 4.5,
            satellitesUsed = 9,
            satellitesVisible = 21,
            lastFixAtMillis = now.toEpochMilliseconds() - 3_000,
            hasBarometer = true,
            pressure = 98_320.0,
        )
        composeRule.setContent { TrailogTheme { SensorsScreen(viewModel(snapshot)) } }

        composeRule.onNodeWithContentDescription("Provider: gps").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Accuracy: 4.5 m").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Satellites: 9 used · 21 visible").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Last fix: 3s ago").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Barometer: 983.2 hPa")
            .performScrollTo().assertIsDisplayed()
    }

    @Test fun `a device with no barometer says so rather than showing nothing`() {
        composeRule.setContent {
            TrailogTheme { SensorsScreen(viewModel(SensorSnapshot(hasBarometer = false))) }
        }
        composeRule.onNodeWithContentDescription("Barometer: Not available on this device")
            .performScrollTo().assertIsDisplayed()
    }

    @Test fun `recording state and service liveness are both shown`() {
        val session = RecordingSession(
            activityId = UUID.randomUUID(),
            type = ActivityType.CYCLING,
            state = RecordingState.RECORDING,
            startTime = now,
        )
        composeRule.setContent {
            TrailogTheme { SensorsScreen(viewModel(session = session, serviceRunning = true)) }
        }
        composeRule.onNodeWithContentDescription("Recording session: Recording")
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Foreground service: Running")
            .performScrollTo().assertIsDisplayed()
    }

    @Test fun `missing location permission is explained instead of leaving GPS rows unexplained`() {
        composeRule.setContent { TrailogTheme { SensorsScreen(viewModel()) } }

        composeRule.onNodeWithText("GPS readings need location permission").assertIsDisplayed()
        composeRule.onNodeWithText("Grant location").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Precise location: Not granted")
            .performScrollTo().assertIsDisplayed()
    }

    @Test fun `granted permissions drop the explanation card`() {
        grantAllPermissions()
        composeRule.setContent { TrailogTheme { SensorsScreen(viewModel()) } }

        composeRule.onNodeWithText("GPS readings need location permission").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Precise location: Granted")
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Battery optimisation: Exempt")
            .performScrollTo().assertIsDisplayed()
    }

    @Test fun `navigating into Sensors and back again works through a real NavHost`() {
        val vm = viewModel()
        lateinit var navController: NavHostController
        composeRule.setContent {
            navController = rememberNavController()
            TrailogTheme {
                NavHost(navController = navController, startDestination = "record") {
                    composable("record") {
                        Button(onClick = { navController.navigate("sensors") }) { Text("To sensors") }
                    }
                    composable("sensors") { SensorsScreen(vm) }
                }
            }
        }

        composeRule.onNodeWithText("To sensors").performClick()
        composeRule.onNodeWithText("Sensors & diagnostics").assertIsDisplayed()

        // Sensors is a bottom-bar tab, so leaving it is a back press, not an in-screen control.
        composeRule.runOnUiThread { navController.popBackStack() }
        composeRule.onNodeWithText("To sensors").assertIsDisplayed()
        composeRule.onNodeWithText("Sensors & diagnostics").assertDoesNotExist()
    }

    // ---- helpers -----------------------------------------------------------------------------

    private fun grantAllPermissions() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        shadowOf(pm).setIgnoringBatteryOptimizations(app.packageName, true)
    }
}
