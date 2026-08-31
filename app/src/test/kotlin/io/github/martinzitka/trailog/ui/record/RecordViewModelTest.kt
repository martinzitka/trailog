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
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    /** Every metadata write the ViewModel made, in order. Empty means nothing was written. */
    private val saved = mutableListOf<SavedMetadata>()

    private fun viewModel(
        engine: RecordingEngine = FakeEngine(),
        settings: RecordSettings = FakeSettings(ActivityType.RUNNING),
    ): RecordViewModel = RecordViewModel(
        engine = engine,
        loadActivity = { null },
        saveMetadata = { id, name, notes, type -> saved += SavedMetadata(id, name, notes, type) },
        settings = settings,
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

    // ---- naming the ride just saved (M1.8) ----

    @Test fun `stopping raises a naming prompt for the activity that was just finalised`() {
        val id = UUID.randomUUID()
        val engine = FakeEngine(session(RecordingState.RECORDING, id))
        val vm = viewModel(engine)

        vm.stop()

        // The ride is saved first: the prompt only ever appears over an activity already on disk.
        assertEquals(1, engine.finalizeCalls)
        assertEquals(NamingPrompt(id.toString(), ActivityType.CYCLING), vm.namingPrompt.value)
    }

    @Test fun `skipping the prompt closes it and writes nothing`() {
        val engine = FakeEngine(session(RecordingState.RECORDING))
        val vm = viewModel(engine)
        vm.stop()

        vm.skipNaming()

        assertNull(vm.namingPrompt.value)
        assertTrue(saved.isEmpty())
        // The ride was saved regardless — skipping costs a title, never an activity.
        assertEquals(1, engine.finalizeCalls)
    }

    @Test fun `saving a name writes it to the finished activity and closes the prompt`() = runTest(dispatcher) {
        val id = UUID.randomUUID()
        val vm = viewModel(FakeEngine(session(RecordingState.RECORDING, id)))
        vm.stop()

        vm.saveName("Bílá skála loop", "Wet roots after the rain")
        advanceUntilIdle()

        assertNull(vm.namingPrompt.value)
        assertEquals(
            listOf(SavedMetadata(id.toString(), "Bílá skála loop", "Wet roots after the rain", ActivityType.CYCLING)),
            saved,
        )
    }

    @Test fun `saving trims whitespace and keeps notes-only naming`() = runTest(dispatcher) {
        val id = UUID.randomUUID()
        val vm = viewModel(FakeEngine(session(RecordingState.RECORDING, id)))
        vm.stop()

        vm.saveName("   ", "  felt slow  ")
        advanceUntilIdle()

        assertEquals(listOf(SavedMetadata(id.toString(), "", "felt slow", ActivityType.CYCLING)), saved)
    }

    @Test fun `saving with both fields empty writes nothing`() = runTest(dispatcher) {
        val vm = viewModel(FakeEngine(session(RecordingState.RECORDING)))
        vm.stop()

        vm.saveName("  ", "   ")
        advanceUntilIdle()

        // Nothing to record, so the row keeps its original updatedAt rather than being touched.
        assertNull(vm.namingPrompt.value)
        assertTrue(saved.isEmpty())
    }

    @Test fun `finishing an interrupted session also offers naming`() {
        val id = UUID.randomUUID()
        val engine = FakeEngine(session(RecordingState.RECOVERING, id))
        val vm = viewModel(engine)

        vm.recoverFinish()

        assertEquals(1, engine.finalizeCalls)
        assertEquals(NamingPrompt(id.toString(), ActivityType.CYCLING), vm.namingPrompt.value)
    }

    @Test fun `resuming an interrupted session raises no prompt`() {
        val vm = viewModel(FakeEngine(session(RecordingState.RECOVERING)))

        vm.recoverResume()

        assertNull(vm.namingPrompt.value)
    }

    @Test fun `saving with no prompt open is a no-op`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.saveName("orphan", "orphan")
        advanceUntilIdle()

        assertTrue(saved.isEmpty())
    }

    // ---- fakes ----

    /** A metadata write the ViewModel asked for. */
    private data class SavedMetadata(
        val activityId: String,
        val name: String,
        val notes: String?,
        val type: ActivityType,
    )

    /**
     * Enough of the engine to finish a recording: the session is mutable, `requestStop` moves it
     * to STOPPING and `finalize` clears it, exactly as [AndroidRecordingEngine] does. That
     * sequence is what the naming prompt has to survive — it is raised *after* the session is
     * already gone.
     */
    private class FakeEngine(initial: RecordingSession? = null) : RecordingEngine {
        private val _session = MutableStateFlow(initial)
        override val session: StateFlow<RecordingSession?> = _session
        var finalizeCalls = 0
            private set

        override fun start(type: ActivityType) = Unit
        override fun pause() = Unit
        override fun resume() = Unit
        override fun requestStop() {
            _session.value = _session.value?.copy(state = RecordingState.STOPPING)
        }

        override fun finalize() {
            finalizeCalls++
            _session.value = null
        }

        override fun recoverResume() = Unit
        override fun record(point: RawPoint) = Unit
        override fun recoverInterruptedSession() = null
    }

    /**
     * Mirrors PrefsRecordSettings' contract: a complete most-recently-used ordering, seeded with
     * the given type at the front. Returning a short list instead would let a test pass against a
     * picker that silently offered fewer chips than the real one.
     */
    private class FakeSettings(type: ActivityType) : RecordSettings {
        var recent: List<ActivityType> =
            listOf(type) + ActivityType.entries.filterNot { it == type }
            private set

        override fun recentActivityTypes(): List<ActivityType> = recent
        override fun noteActivityTypeUsed(type: ActivityType) {
            recent = listOf(type) + recent.filterNot { it == type }
        }
    }

    // ---- activity type picker ---------------------------------------------------------------

    /**
     * Ready state straight from [RecordViewModel.reduce], matching how the rest of this class
     * tests state. Going through `uiState` would not work: it is a `WhileSubscribed` StateFlow,
     * so its value does not recompute without a collector.
     */
    private fun readyState(
        selected: ActivityType,
        recent: List<ActivityType> = FakeSettings(selected).recentActivityTypes(),
    ): RecordUiState.Ready =
        viewModel().reduce(
            session = null,
            env = allGranted,
            selected = selected,
            live = null,
            recent = recent,
        ) as RecordUiState.Ready

    @Test fun `the picker offers the five most recently used types`() {
        val recent = FakeSettings(ActivityType.RUNNING).recentActivityTypes()
        val offered = readyState(ActivityType.RUNNING, recent).offeredTypes
        assertEquals(5, offered.size)
        assertEquals(recent.take(5), offered)
    }

    @Test fun `a type chosen from the overflow is offered without reshuffling the rest`() {
        // Bobsleigh is nowhere near the recent five. It must appear so the selection is visible,
        // and it must displace the least recent rather than jumping to the front, which would
        // move every other chip under the user's finger.
        val recent = FakeSettings(ActivityType.RUNNING).recentActivityTypes()
        val before = readyState(ActivityType.RUNNING, recent).offeredTypes
        val after = readyState(ActivityType.BOBSLEIGH, recent).offeredTypes

        assertEquals(5, after.size)
        assertTrue("the selected type must be visible", ActivityType.BOBSLEIGH in after)
        assertEquals("the leading chips must not move", before.dropLast(1), after.dropLast(1))
        assertEquals(ActivityType.BOBSLEIGH, after.last())
    }

    @Test fun `selecting an already-offered type does not change the offered set`() {
        val recent = FakeSettings(ActivityType.RUNNING).recentActivityTypes()
        val before = readyState(ActivityType.RUNNING, recent).offeredTypes
        assertEquals(before, readyState(before[2], recent).offeredTypes)
    }

    @Test fun `every activity type is visible once selected, however the chips are trimmed`() {
        // 15 types, 5 chips: the guard is that selecting any of the other 10 still shows it.
        val recent = FakeSettings(ActivityType.RUNNING).recentActivityTypes()
        assertTrue("otherwise there is no overflow", ActivityType.entries.size > 5)
        ActivityType.entries.forEach { type ->
            val offered = readyState(type, recent).offeredTypes
            assertTrue("$type must be visible once selected", type in offered)
            assertEquals(5, offered.size)
        }
    }

    @Test fun `starting a recording promotes the type to most recent`() {
        // Selection alone is not "use" — the existing contract persists the choice on start.
        val settings = FakeSettings(ActivityType.RUNNING)
        val vm = viewModel(settings = settings)
        vm.updateEnvironment(allGranted)

        vm.selectType(ActivityType.SNOWKITING)
        assertEquals(ActivityType.RUNNING, settings.recentActivityTypes().first())

        vm.start()
        assertEquals(ActivityType.SNOWKITING, settings.recentActivityTypes().first())
    }
}
