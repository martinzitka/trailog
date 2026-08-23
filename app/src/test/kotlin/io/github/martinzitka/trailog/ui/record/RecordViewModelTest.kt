package io.github.martinzitka.trailog.ui.record

import io.github.martinzitka.trailog.core.model.ActivityType
import io.github.martinzitka.trailog.core.model.RawPoint
import io.github.martinzitka.trailog.core.recording.RecordingEngine
import io.github.martinzitka.trailog.core.recording.RecordingSession
import io.github.martinzitka.trailog.core.recording.RecordingState
import io.github.martinzitka.trailog.ui.map.TracePoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
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
import java.util.UUID

/**
 * Unit tests for [RecordViewModel]'s state derivation — the pure [RecordViewModel.reduce] mapping
 * from (session, environment, selected type, live payload) to the seven-case [RecordUiState], and
 * the [RecordEnvironment] warning logic. No device, no Room, no Android types.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordViewModelTest {

    // viewModelScope needs a Main dispatcher; a standard test dispatcher queues the init coroutine
    // without running it, which is all these synchronous `reduce` calls need.
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(): RecordViewModel = RecordViewModel(
        engine = FakeEngine(),
        loadActivity = { null },
        settings = FakeSettings(ActivityType.RUNNING),
        now = { Instant.fromEpochMilliseconds(0) },
        ticker = emptyFlow(),
    )

    private val allGranted = RecordEnvironment(
        fineLocationGranted = true,
        backgroundLocationGranted = true,
        notificationsGranted = true,
        batteryOptimisationExempt = true,
    )

    private fun session(state: RecordingState, id: UUID = UUID.randomUUID()) = RecordingSession(
        activityId = id,
        type = ActivityType.CYCLING,
        state = state,
        startTime = Instant.fromEpochMilliseconds(0),
    )

    @Test fun `idle without fine location is permissions-missing`() {
        val state = viewModel().reduce(
            session = null,
            env = RecordEnvironment(fineLocationGranted = false),
            selected = ActivityType.RUNNING,
            live = null,
        )
        assertTrue(state is RecordUiState.PermissionsMissing)
    }

    @Test fun `idle with fine location is ready with the selected type`() {
        val state = viewModel().reduce(
            session = null,
            env = allGranted,
            selected = ActivityType.HIKING,
            live = null,
        )
        assertTrue(state is RecordUiState.Ready)
        assertEquals(ActivityType.HIKING, (state as RecordUiState.Ready).activityType)
    }

    @Test fun `recording maps to Recording with the session type and matching live stats`() {
        val id = UUID.randomUUID()
        val live = RecordViewModel.LivePayload(
            activityId = id.toString(),
            live = LiveStats(elapsedSeconds = 90, movingSeconds = 80, distance = 500.0, currentSpeed = 5.0, elevationGain = 12.0),
            segments = listOf(listOf(TracePoint(50.0, 14.0), TracePoint(50.1, 14.1))),
            gapSeconds = 0,
        )
        val state = viewModel().reduce(session(RecordingState.RECORDING, id), allGranted, ActivityType.CYCLING, live)
        assertTrue(state is RecordUiState.Recording)
        state as RecordUiState.Recording
        assertEquals(ActivityType.CYCLING, state.activityType)
        assertEquals(500.0, state.live.distance, 0.0)
        assertEquals(1, state.segments.size)
    }

    @Test fun `paused maps to Paused`() {
        val state = viewModel().reduce(session(RecordingState.PAUSED), allGranted, ActivityType.CYCLING, null)
        assertTrue(state is RecordUiState.Paused)
    }

    @Test fun `stopping maps to Saving`() {
        val state = viewModel().reduce(session(RecordingState.STOPPING), allGranted, ActivityType.CYCLING, null)
        assertTrue(state is RecordUiState.Saving)
    }

    @Test fun `recovering maps to InterruptedSessionFound with the gap`() {
        val id = UUID.randomUUID()
        val live = RecordViewModel.LivePayload(id.toString(), LiveStats.EMPTY, emptyList(), gapSeconds = 3600)
        val state = viewModel().reduce(session(RecordingState.RECOVERING, id), allGranted, ActivityType.CYCLING, live)
        assertTrue(state is RecordUiState.InterruptedSessionFound)
        assertEquals(3600L, (state as RecordUiState.InterruptedSessionFound).gapSeconds)
    }

    @Test fun `stale live payload from another activity is ignored`() {
        // A payload for a different activity id must not leak into the current session's stats.
        val stale = RecordViewModel.LivePayload(
            activityId = UUID.randomUUID().toString(),
            live = LiveStats(elapsedSeconds = 1, movingSeconds = 1, distance = 9999.0, currentSpeed = 9.0, elevationGain = 9.0),
            segments = listOf(listOf(TracePoint(0.0, 0.0))),
            gapSeconds = 5,
        )
        val state = viewModel().reduce(session(RecordingState.RECORDING), allGranted, ActivityType.CYCLING, stale)
        assertTrue(state is RecordUiState.Recording)
        state as RecordUiState.Recording
        assertEquals(LiveStats.EMPTY, state.live)
        assertTrue(state.segments.isEmpty())
    }

    @Test fun `environment surfaces partial-permission warnings only when recording is possible`() {
        assertTrue(RecordEnvironment(fineLocationGranted = false).warnings.isEmpty())

        val partial = RecordEnvironment(
            fineLocationGranted = true,
            backgroundLocationGranted = false,
            notificationsGranted = false,
            batteryOptimisationExempt = false,
        )
        assertTrue(partial.canRecord)
        assertEquals(
            setOf(
                RecordWarning.NO_BACKGROUND_LOCATION,
                RecordWarning.NO_NOTIFICATIONS,
                RecordWarning.BATTERY_NOT_EXEMPT,
            ),
            partial.warnings.toSet(),
        )
        assertFalse(allGranted.warnings.isNotEmpty())
    }

    // ---- fakes ----

    private class FakeEngine : RecordingEngine {
        override val session: StateFlow<RecordingSession?> = MutableStateFlow(null)
        override fun start(type: ActivityType) = Unit
        override fun pause() = Unit
        override fun resume() = Unit
        override fun requestStop() = Unit
        override fun finalize() = Unit
        override fun recoverResume() = Unit
        override fun record(point: RawPoint) = Unit
        override fun recoverInterruptedSession() = null
    }

    private class FakeSettings(private var type: ActivityType) : RecordSettings {
        override fun lastActivityType(): ActivityType = type
        override fun setLastActivityType(type: ActivityType) { this.type = type }
    }
}
