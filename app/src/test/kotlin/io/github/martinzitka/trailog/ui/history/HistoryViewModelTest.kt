package io.github.martinzitka.trailog.ui.history

import io.github.martinzitka.trailog.core.model.ActivityType
import io.github.martinzitka.trailog.data.ActivityEntity
import io.github.martinzitka.trailog.data.ActivityStatsEntity
import io.github.martinzitka.trailog.data.ActivityWithStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [HistoryViewModel]'s state mapping — the four screen states and the row
 * conversion from Room entities. No device and no database: the ViewModel takes its row flow as a
 * constructor argument precisely so this is a plain JVM test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {

    // viewModelScope needs a Main dispatcher; `uiState` is WhileSubscribed, so each test
    // subscribes via `collecting` and advances the scheduler to let the flow settle.
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `starts in loading until the first read arrives`() = runTest(dispatcher) {
        val vm = HistoryViewModel(emptyFlow())
        collecting(vm) {
            assertTrue(vm.uiState.value is HistoryUiState.Loading)
        }
    }

    @Test fun `no activities is the empty state, not an empty list`() = runTest(dispatcher) {
        val vm = HistoryViewModel(rows())
        collecting(vm) {
            assertTrue(vm.uiState.value is HistoryUiState.Empty)
        }
    }

    @Test fun `keeps the order the database returned - most recent first`() = runTest(dispatcher) {
        val vm = HistoryViewModel(
            rows(
                row("b", startTime = 2_000L, name = "Evening loop"),
                row("a", startTime = 1_000L),
            ),
        )
        collecting(vm) {
            assertEquals(listOf("b", "a"), populated(vm).items.map { it.id })
        }
    }

    @Test fun `carries type, name, start time and the three figures onto the row`() =
        runTest(dispatcher) {
            val vm = HistoryViewModel(
                rows(
                    row(
                        id = "a",
                        startTime = 1_700_000_000_000L,
                        name = "Morning ride",
                        type = ActivityType.MOUNTAIN_BIKING,
                        stats = stats("a", distance = 12_345.0, elapsedMillis = 3_723_400L, gain = 251.0),
                    ),
                ),
            )
            collecting(vm) {
                val item = populated(vm).items.single()
                assertEquals("a", item.id)
                assertEquals(ActivityType.MOUNTAIN_BIKING, item.type)
                assertEquals("Morning ride", item.name)
                assertEquals(1_700_000_000_000L, item.startTime)
                assertEquals(12_345.0, item.distance!!, 0.0)
                // Durations are millis at rest and whole seconds at the display edge.
                assertEquals(3_723L, item.elapsedSeconds)
                assertEquals(251.0, item.elevationGain!!, 0.0)
            }
        }

    @Test fun `an activity with no cached stats is listed with pending figures, never zeroes`() =
        runTest(dispatcher) {
            val vm = HistoryViewModel(rows(row("a", stats = null)))
            collecting(vm) {
                val item = populated(vm).items.single()
                assertNull(item.distance)
                assertNull(item.elapsedSeconds)
                assertNull(item.elevationGain)
            }
        }

    @Test fun `a failing read becomes the error state`() = runTest(dispatcher) {
        val vm = HistoryViewModel(flow { throw IllegalStateException("database unavailable") })
        collecting(vm) {
            assertTrue(vm.uiState.value is HistoryUiState.Error)
        }
    }

    @Test fun `retry re-reads the list after a failure`() = runTest(dispatcher) {
        var attempt = 0
        val vm = HistoryViewModel(
            flow {
                if (attempt++ == 0) throw IllegalStateException("database unavailable")
                emit(listOf(row("a")))
            },
        )
        collecting(vm) {
            assertTrue(vm.uiState.value is HistoryUiState.Error)

            vm.retry()
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("a"), populated(vm).items.map { it.id })
        }
    }

    // ---- helpers ---------------------------------------------------------------------------

    /** Subscribes to `uiState` (it is `WhileSubscribed`), settles the scheduler, then runs [body]. */
    private fun TestScope.collecting(vm: HistoryViewModel, body: () -> Unit) {
        val job: Job = launch { vm.uiState.collect { } }
        testScheduler.advanceUntilIdle()
        try {
            body()
        } finally {
            job.cancel()
        }
    }

    private fun populated(vm: HistoryViewModel) = vm.uiState.value as HistoryUiState.Populated

    private fun rows(vararg rows: ActivityWithStats): Flow<List<ActivityWithStats>> =
        MutableStateFlow(rows.toList())

    private fun row(
        id: String = "a",
        startTime: Long = 1_000L,
        name: String = "",
        type: ActivityType = ActivityType.CYCLING,
        stats: ActivityStatsEntity? = stats(id),
    ) = ActivityWithStats(
        activity = ActivityEntity(
            id = id,
            type = type.name,
            name = name,
            notes = null,
            startTime = startTime,
            createdAt = startTime,
            updatedAt = startTime,
        ),
        stats = stats,
    )

    private fun stats(
        activityId: String,
        distance: Double = 1_000.0,
        elapsedMillis: Long = 600_000L,
        gain: Double = 10.0,
    ) = ActivityStatsEntity(
        activityId = activityId,
        distance = distance,
        elapsedTime = elapsedMillis,
        movingTime = elapsedMillis,
        averageSpeed = 5.0,
        maxSpeed = 9.0,
        elevationGain = gain,
        elevationLoss = gain,
        segmentCount = 1,
        pointCount = 100,
        computedAt = 0L,
    )
}
