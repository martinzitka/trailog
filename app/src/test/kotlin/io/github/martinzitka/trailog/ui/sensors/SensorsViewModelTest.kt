package io.github.martinzitka.trailog.ui.sensors

import io.github.martinzitka.trailog.core.recording.RecordingState
import io.github.martinzitka.trailog.ui.record.RecordEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [SensorsViewModel.reduce] — the pure mapping from a [SensorSnapshot], the
 * permission environment and the recording session to the screen's diagnostic rows, including the
 * health thresholds. No device, no sensors, no Android types.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SensorsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private val now = Instant.fromEpochMilliseconds(1_000_000)

    private fun viewModel() = SensorsViewModel(
        probe = SensorProbe { emptyFlow() },
        session = flowOf(null),
        serviceRunning = { false },
        now = { now },
        ticker = emptyFlow(),
    )

    private fun reduce(
        snapshot: SensorSnapshot = SensorSnapshot(),
        env: RecordEnvironment = RecordEnvironment(),
        recordingState: RecordingState? = null,
        serviceAlive: Boolean = false,
    ) = viewModel().reduce(snapshot, env, recordingState, serviceAlive, now)

    private fun SensorsUiState.row(id: DiagnosticId): DiagnosticRow =
        groups.flatMap { it.rows }.single { it.id == id }

    // ---- structure ----

    @Test fun `every diagnostic id appears exactly once, in a group`() {
        val rows = reduce().groups.flatMap { it.rows }
        assertEquals(DiagnosticId.entries.size, rows.size)
        assertEquals(DiagnosticId.entries.toSet(), rows.map { it.id }.toSet())
        assertEquals(DiagnosticGroupId.entries, reduce().groups.map { it.id })
    }

    @Test fun `a snapshot with nothing observed yet is waiting, never blank`() {
        val state = reduce()
        listOf(
            DiagnosticId.PROVIDER,
            DiagnosticId.ACCURACY,
            DiagnosticId.SATELLITES,
            DiagnosticId.FIX_AGE,
        ).forEach { id ->
            assertEquals(DiagnosticValue.Waiting, state.row(id).value)
            assertEquals(DiagnosticStatus.NEUTRAL, state.row(id).status)
        }
    }

    // ---- GPS ----

    @Test fun `a fix fills provider, accuracy and satellite counts`() {
        val state = reduce(
            SensorSnapshot(
                provider = "gps",
                accuracy = 4.5,
                satellitesUsed = 9,
                satellitesVisible = 21,
                lastFixAtMillis = now.toEpochMilliseconds(),
            ),
        )
        assertEquals(DiagnosticValue.Text("gps"), state.row(DiagnosticId.PROVIDER).value)
        assertEquals(DiagnosticValue.Length(4.5), state.row(DiagnosticId.ACCURACY).value)
        assertEquals(DiagnosticValue.Ratio(9, 21), state.row(DiagnosticId.SATELLITES).value)
        assertEquals(DiagnosticValue.Age(0), state.row(DiagnosticId.FIX_AGE).value)
    }

    @Test fun `fix age is derived from the clock, so it grows while nothing arrives`() {
        val snapshot = SensorSnapshot(lastFixAtMillis = now.toEpochMilliseconds() - 90_000)
        assertEquals(DiagnosticValue.Age(90), reduce(snapshot).row(DiagnosticId.FIX_AGE).value)
        assertEquals(DiagnosticStatus.WARN, reduce(snapshot).row(DiagnosticId.FIX_AGE).status)
    }

    @Test fun `a fix timestamp ahead of the device clock clamps to zero rather than going negative`() {
        val snapshot = SensorSnapshot(lastFixAtMillis = now.toEpochMilliseconds() + 5_000)
        assertEquals(DiagnosticValue.Age(0), reduce(snapshot).row(DiagnosticId.FIX_AGE).value)
    }

    @Test fun `accuracy thresholds grade the fix`() {
        assertEquals(DiagnosticStatus.GOOD, SensorsViewModel.accuracyStatus(4.0))
        assertEquals(DiagnosticStatus.GOOD, SensorsViewModel.accuracyStatus(SensorsViewModel.ACCURACY_GOOD))
        assertEquals(DiagnosticStatus.NEUTRAL, SensorsViewModel.accuracyStatus(35.0))
        assertEquals(DiagnosticStatus.WARN, SensorsViewModel.accuracyStatus(120.0))
    }

    @Test fun `fix age thresholds grade freshness`() {
        assertEquals(DiagnosticStatus.GOOD, SensorsViewModel.fixAgeStatus(2))
        assertEquals(DiagnosticStatus.NEUTRAL, SensorsViewModel.fixAgeStatus(30))
        assertEquals(DiagnosticStatus.WARN, SensorsViewModel.fixAgeStatus(3600))
    }

    @Test fun `satellite thresholds grade the constellation, and none in fix is a warning`() {
        assertEquals(DiagnosticStatus.GOOD, SensorsViewModel.satelliteStatus(12))
        assertEquals(DiagnosticStatus.NEUTRAL, SensorsViewModel.satelliteStatus(3))
        assertEquals(DiagnosticStatus.WARN, SensorsViewModel.satelliteStatus(0))
    }

    @Test fun `satellite count without a visible total is still shown`() {
        val state = reduce(SensorSnapshot(satellitesUsed = 5))
        assertEquals(DiagnosticValue.Ratio(5, null), state.row(DiagnosticId.SATELLITES).value)
    }

    // ---- barometer ----

    @Test fun `a device without a barometer says so explicitly`() {
        val state = reduce(SensorSnapshot(hasBarometer = false))
        assertEquals(DiagnosticValue.Unavailable, state.row(DiagnosticId.BAROMETER).value)
    }

    @Test fun `a barometer that has not reported yet is waiting, not unavailable`() {
        val state = reduce(SensorSnapshot(hasBarometer = true))
        assertEquals(DiagnosticValue.Waiting, state.row(DiagnosticId.BAROMETER).value)
    }

    @Test fun `barometer pressure is carried through in Pascals`() {
        val state = reduce(SensorSnapshot(hasBarometer = true, pressure = 98_320.0))
        assertEquals(DiagnosticValue.Pressure(98_320.0), state.row(DiagnosticId.BAROMETER).value)
    }

    // ---- recording and permissions ----

    @Test fun `no session reports no session and no warning about the dead service`() {
        val state = reduce(recordingState = null, serviceAlive = false)
        assertEquals(DiagnosticValue.Session(null), state.row(DiagnosticId.SESSION_STATE).value)
        assertEquals(DiagnosticValue.Running(false), state.row(DiagnosticId.SERVICE_RUNNING).value)
        assertEquals(DiagnosticStatus.NEUTRAL, state.row(DiagnosticId.SERVICE_RUNNING).status)
    }

    @Test fun `a recording session with a live service is healthy`() {
        val state = reduce(recordingState = RecordingState.RECORDING, serviceAlive = true)
        assertEquals(
            DiagnosticValue.Session(RecordingState.RECORDING),
            state.row(DiagnosticId.SESSION_STATE).value,
        )
        assertEquals(DiagnosticStatus.GOOD, state.row(DiagnosticId.SERVICE_RUNNING).status)
    }

    @Test fun `a recording session with a dead service is the warning this screen exists for`() {
        val state = reduce(recordingState = RecordingState.RECORDING, serviceAlive = false)
        assertEquals(DiagnosticStatus.WARN, state.row(DiagnosticId.SERVICE_RUNNING).status)
    }

    @Test fun `permission and battery rows mirror the environment`() {
        val env = RecordEnvironment(
            fineLocationGranted = true,
            backgroundLocationGranted = false,
            notificationsGranted = true,
            batteryOptimisationExempt = false,
        )
        val state = reduce(env = env)
        assertEquals(DiagnosticValue.Granted(true), state.row(DiagnosticId.PERMISSION_FINE).value)
        assertEquals(DiagnosticStatus.GOOD, state.row(DiagnosticId.PERMISSION_FINE).status)
        assertEquals(DiagnosticValue.Granted(false), state.row(DiagnosticId.PERMISSION_BACKGROUND).value)
        assertEquals(DiagnosticStatus.WARN, state.row(DiagnosticId.PERMISSION_BACKGROUND).status)
        assertEquals(DiagnosticValue.Granted(true), state.row(DiagnosticId.PERMISSION_NOTIFICATIONS).value)
        assertEquals(DiagnosticValue.Granted(false), state.row(DiagnosticId.BATTERY_OPTIMISATION).value)
        assertEquals(DiagnosticStatus.WARN, state.row(DiagnosticId.BATTERY_OPTIMISATION).status)
    }

    @Test fun `missing location permission is flagged so the empty GPS rows are explained`() {
        assertTrue(reduce(env = RecordEnvironment(fineLocationGranted = false)).locationPermissionMissing)
        assertFalse(reduce(env = RecordEnvironment(fineLocationGranted = true)).locationPermissionMissing)
    }
}
